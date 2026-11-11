package com.stackcorp.astra.files;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import com.stackcorp.astra.files.FileRecords.FileRow;
import com.stackcorp.astra.files.HlsPackager.Published;
import com.stackcorp.astra.files.VideoAssetRecords.Claimed;
import com.stackcorp.astra.files.VideoAssetRecords.Expired;
import com.stackcorp.astra.files.VideoAssetRecords.Purge;
import com.stackcorp.astra.files.api.TranscodeException;
import com.stackcorp.astra.files.api.TranscodeException.Code;
import com.stackcorp.astra.files.api.VideoAssetListener;
import com.stackcorp.astra.files.api.VideoTranscoder;
import com.stackcorp.astra.files.api.VideoTranscoder.Transcoded;
import com.stackcorp.astra.files.api.VideoTranscoder.VideoSource;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * worker 프로필에서 영상 변환 작업을 하나씩 실행한다(ADR-0016). 여러 worker가 떠도 원장의 SKIP LOCKED로 한 곳만 잡는다.
 * <p>
 * 흐름: 작업을 잡는다 → 원본을 transcoder로 보낸다 → segment마다 암호화해 vod/{assetKey}/에 쓴다 → 재생 목록을 쓴다 → 작업 성공,
 * asset ready, 부모 모듈 알림, 감사 기록을 한 transaction으로 commit한다. 중간에 실패하거나 취소되면 vod/{assetKey}/를 비운다.
 * 일시 오류는 대기 시간을 늘려 가며 다시 시도하고, 횟수를 다 쓰거나 입력이 잘못됐으면 실패로 닫는다.
 * <p>
 * 원본 파일이 삭제되거나 검사에서 거부되면(infected) asset을 거두고(재생 경로 닫기, 대기 작업 취소, 실행 중 작업에 취소 요청), 실행
 * 중 작업이 끝난 뒤 HLS와 key를 지운다. 거두기와 지우기는 각각 상태 조건 UPDATE로 한 번만 일어난다. 원본 blob(blobs/stream/…)은
 * 저장소 GC(StorageGc)가 참조가 0이 된 뒤 지운다.
 * <p>
 * transaction은 READ COMMITTED다. 교착이나 잠금 대기 초과는 정해진 횟수만큼 다시 하고, 끝내 기록하지 못하면 lease를 놓아 다음
 * 실행이나 다른 worker가 이어받게 한다. 잠금 순서는 video_transcode_jobs → video_assets이고, files는 잠그지 않고 읽는다.
 * 로그에는 ID, 개수, 코드만 남긴다. key와 영상 내용은 남기지 않는다(ADR-0007 9번).
 */
@Component
@Profile("worker")
class VideoTranscodeRunner implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(VideoTranscodeRunner.class);

	private static final int BATCH = 50;

	private static final int ATTEMPTS = 3;

	private final VideoAssetRecords records;

	private final FileRecords files;

	private final ObjectStore store;

	private final VideoKeyStore keys;

	private final VideoTranscoder transcoder;

	private final List<VideoAssetListener> listeners;

	private final TransactionTemplate transaction;

	private final AuditLog audit;

	private final VideoWorkerProperties properties;

	private final String owner;

	private final ScheduledExecutorService leaseRenewer = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("transcode-lease").factory());

	VideoTranscodeRunner(VideoAssetRecords records, FileRecords files, ObjectStore store, VideoKeyStore keys,
			VideoTranscoder transcoder, List<VideoAssetListener> listeners, PlatformTransactionManager transactionManager,
			AuditLog audit, VideoWorkerProperties properties) {
		this.records = records;
		this.files = files;
		this.store = store;
		this.keys = keys;
		this.transcoder = transcoder;
		this.listeners = listeners;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.audit = audit;
		this.properties = properties;
		this.owner = properties.workerId() != null ? properties.workerId() : defaultOwner();
	}

	@Scheduled(fixedDelayString = "${astra.transcoder.worker.poll-interval:10s}",
			initialDelayString = "${astra.transcoder.worker.poll-interval:10s}")
	void poll() {
		withdrawRemovedFiles();
		while (runOnce()) {
			// 대기 작업이 없을 때까지 이어서 실행한다.
		}
	}

	@Scheduled(fixedDelayString = "${astra.transcoder.worker.lease-renewal:1m}")
	void recoverExpired() {
		List<Expired> expired = inTransaction((tx) -> {
			List<Expired> rows = this.records.lockExpired(BATCH);
			for (Expired row : rows) {
				if (row.cancelRequested()) {
					this.records.closeExpired(row.jobId(), "cancelled", Duration.ZERO);
				}
				else if (row.attempts() < this.properties.maxAttempts()) {
					this.records.closeExpired(row.jobId(), "queued", this.properties.backoff(row.attempts()));
				}
				else {
					this.records.closeExpired(row.jobId(), "failed", Duration.ZERO);
					assetFailed(row.assetId());
				}
			}
			return rows;
		});
		if (!expired.isEmpty()) {
			log.warn("transcode jobs interrupted by expired lease count={}", expired.size());
		}
	}

	/**
	 * 원본이 지워졌거나 거부된 asset을 거두고, 실행 중 작업이 없는 것은 HLS와 key를 지운다. 거두기는 asset마다 짧은 transaction에서
	 * 상태 조건 UPDATE로, 지우기는 purged_at을 먼저 쓴 쪽만 객체를 지운다. 두 worker가 함께 불러도 각각 한 번만 일어난다.
	 */
	void withdrawRemovedFiles() {
		int withdrawn = 0;
		for (long assetId : this.records.assetsOfRemovedFiles(BATCH)) {
			try {
				boolean removed = Boolean.TRUE.equals(inTransaction((tx) -> {
					if (!this.records.remove(assetId)) {
						return false;
					}
					this.listeners.forEach((l) -> l.withdrawn(assetId));
					this.audit.record(null, null, "video.withdraw", "video_asset", assetId, null, AuditLog.OK);
					return true;
				}));
				if (removed) {
					withdrawn++;
				}
			}
			catch (RuntimeException ex) {
				// 바꾸지 못했으므로 다음 실행에서 다시 고른다.
				log.warn("failed to withdraw video asset assetId={}", assetId, ex);
			}
		}
		int purged = 0;
		for (Purge purge : this.records.purgeable(BATCH)) {
			boolean claimed;
			try {
				claimed = Boolean.TRUE.equals(inTransaction((tx) -> this.records.claimPurge(purge.assetId())));
			}
			catch (RuntimeException ex) {
				log.warn("failed to mark video asset purged assetId={}", purge.assetId(), ex);
				continue;
			}
			if (!claimed) {
				continue;
			}
			try {
				this.store.deletePrefix(Bucket.DELIVERY, HlsPackager.prefix(purge.assetKey()));
				this.keys.delete(purge.assetKey());
				purged++;
			}
			catch (RuntimeException ex) {
				// 지운 것으로 표시했으므로 다시 고르지 않는다. 남은 객체는 재생 경로가 닫혀 닿지 않는다. 운영자가 지운다.
				log.error("video asset marked purged but failed to delete objects assetId={}", purge.assetId(), ex);
			}
		}
		if (withdrawn > 0 || purged > 0) {
			log.info("video assets withdrawn={} purged={}", withdrawn, purged);
		}
	}

	/** @return 작업 하나를 처리했으면 true */
	boolean runOnce() {
		return runOnce(this.transcoder);
	}

	boolean runOnce(VideoTranscoder transcoder) {
		Optional<Claimed> claimed = inTransaction((tx) -> this.records.claimNext(this.owner, this.properties.lease()));
		if (claimed.isEmpty()) {
			return false;
		}
		Claimed job = claimed.get();
		Optional<FileRow> file = this.files.find(job.fileId());
		if (file.isEmpty() || "infected".equals(file.get().scanState())) {
			// 원본이 지워졌거나 거부됐다. asset은 withdrawRemovedFiles가 거둔다.
			closeQuietly(job, "cancelled", null);
			return true;
		}
		Optional<String> container = VideoAssetService.container(file.get().detectedMimeType());
		if (container.isEmpty()) {
			failed(job, "invalid_media", false);
			return true;
		}
		if (!file.get().transcodable()) {
			// 같은 내용을 다른 용도로 검사하는 중이다(ADR-0016 12번). 판정을 기다린다.
			failed(job, "not_ready", true);
			return true;
		}
		AtomicBoolean leaseLost = new AtomicBoolean();
		long renewal = this.properties.leaseRenewal().toMillis();
		ScheduledFuture<?> renewer = this.leaseRenewer.scheduleAtFixedRate(() -> {
			try {
				if (!this.records.renewLease(job.jobId(), this.owner, this.properties.lease())) {
					leaseLost.set(true);
				}
			}
			catch (RuntimeException ex) {
				log.warn("transcode lease renewal failed jobId={}", job.jobId(), ex);
			}
		}, renewal, renewal, TimeUnit.MILLISECONDS);
		try {
			execute(job, file.get(), container.get(), transcoder,
					() -> leaseLost.get() || this.records.cancelRequested(job.jobId()));
		}
		finally {
			renewer.cancel(false);
		}
		return true;
	}

	@Override
	public void close() {
		this.leaseRenewer.shutdownNow();
	}

	private void execute(Claimed job, FileRow file, String container, VideoTranscoder transcoder,
			BooleanSupplier cancelled) {
		long started = System.nanoTime();
		byte[] key = null;
		try {
			// 앞선 실행이 멈추며 남긴 결과를 지운다.
			this.store.deletePrefix(Bucket.DELIVERY, HlsPackager.prefix(job.assetKey()));
			key = this.keys.ensure(job.assetKey());
			HlsPackager packager = new HlsPackager(this.store, job.assetKey(), key, job.keyUri());
			VideoSource source = new VideoSource(container, file.sizeBytes(), () -> this.store
				.open(Bucket.CONTENT, file.storageKey())
				.orElseThrow(() -> new IOException("원본 객체가 없습니다.")));
			Transcoded transcoded = transcoder.transcode(source, packager::accept, cancelled);
			if (cancelled.getAsBoolean()) {
				throw new TranscodeException(Code.CANCELLED, "변환이 취소되었습니다.");
			}
			Published published = packager.publish();
			boolean committed = Boolean.TRUE.equals(inTransaction((tx) -> {
				if (!this.records.succeed(job.jobId(), this.owner)) {
					return false;
				}
				if (!this.records.ready(job.assetId(), published.playlistPath(), published.durationSec(),
						published.segmentCount(), published.outputBytes())) {
					tx.setRollbackOnly();
					return false;
				}
				this.listeners.forEach((l) -> l.ready(job.assetId(), published.playlistPath(), published.durationSec()));
				this.audit.record(null, null, "video.publish", "video_asset", job.assetId(), null, AuditLog.OK);
				return true;
			}));
			if (!committed) {
				discardOutput(job);
				closeQuietly(job, "cancelled", null);
				log.info("transcode job cancelled before publish jobId={}", job.jobId());
				return;
			}
			log.info("transcode job succeeded jobId={} assetId={} segments={} durationSec={} elapsedMs={}", job.jobId(),
					job.assetId(), transcoded.segmentCount(), published.durationSec(),
					(System.nanoTime() - started) / 1_000_000);
		}
		catch (TranscodeException ex) {
			discardOutput(job);
			if (ex.code() == Code.CANCELLED) {
				closeQuietly(job, "cancelled", null);
				log.info("transcode job cancelled jobId={}", job.jobId());
			}
			else {
				failed(job, ex.code().value(), ex.code().retryable());
			}
		}
		catch (RuntimeException ex) {
			discardOutput(job);
			log.error("transcode job failed jobId={} error={}", job.jobId(), ex.getClass().getName());
			failed(job, "internal", true);
		}
		finally {
			if (key != null) {
				Arrays.fill(key, (byte) 0);
			}
		}
	}

	// lease를 다른 worker가 가져갔으면 그 worker가 같은 경로에 쓰는 중일 수 있어 지우지 않는다.
	private void discardOutput(Claimed job) {
		try {
			if (this.records.renewLease(job.jobId(), this.owner, this.properties.lease())) {
				this.store.deletePrefix(Bucket.DELIVERY, HlsPackager.prefix(job.assetKey()));
			}
		}
		catch (RuntimeException ex) {
			// 재생 경로(content_path)는 채우지 않았으므로 남은 segment에는 닿지 않는다. 다음 실행이 시작할 때 지운다.
			log.warn("failed to discard transcode output jobId={}", job.jobId(), ex);
		}
	}

	private void failed(Claimed job, String code, boolean retryable) {
		if (retryable && job.attempt() < this.properties.maxAttempts()) {
			Duration delay = this.properties.backoff(job.attempt());
			try {
				if (Boolean.TRUE.equals(inTransaction((tx) -> this.records.requeue(job.jobId(), this.owner, code, delay)))) {
					log.warn("transcode job will retry jobId={} attempt={} code={} delaySec={}", job.jobId(),
							job.attempt(), code, delay.toSeconds());
				}
			}
			catch (RuntimeException ex) {
				// 기록하지 못했다. lease가 끝나면 recoverExpired가 이어받는다.
				log.error("failed to requeue transcode job jobId={}", job.jobId(), ex);
			}
			return;
		}
		try {
			inTransaction((tx) -> {
				if (this.records.close(job.jobId(), this.owner, "failed", code)) {
					assetFailed(job.assetId());
				}
				return null;
			});
			log.warn("transcode job failed jobId={} attempt={} code={}", job.jobId(), job.attempt(), code);
		}
		catch (RuntimeException ex) {
			log.error("failed to close transcode job jobId={}", job.jobId(), ex);
			releaseLease(job);
		}
	}

	private void closeQuietly(Claimed job, String state, String code) {
		try {
			inTransaction((tx) -> this.records.close(job.jobId(), this.owner, state, code));
		}
		catch (RuntimeException ex) {
			log.error("failed to close transcode job jobId={} state={}", job.jobId(), state, ex);
			releaseLease(job);
		}
	}

	// 끝내 기록하지 못한 작업의 lease를 바로 놓는다. lease가 끝날 때까지 기다리지 않고 recoverExpired가 다음 실행에서 이어받는다.
	private void releaseLease(Claimed job) {
		try {
			this.records.expireLease(job.jobId(), this.owner);
		}
		catch (RuntimeException ex) {
			log.warn("failed to release transcode lease jobId={}", job.jobId(), ex);
		}
	}

	private void assetFailed(long assetId) {
		if (this.records.failed(assetId)) {
			this.listeners.forEach((l) -> l.failed(assetId));
		}
	}

	// 교착, 잠금 대기 초과처럼 다시 하면 될 수 있는 실패만 다시 한다. transaction은 실패할 때마다 통째로 되돌아간다.
	private <T> T inTransaction(TransactionCallback<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute(work);
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= ATTEMPTS) {
					throw ex;
				}
				log.info("transcode transaction retried attempt={} error={}", attempt, ex.getClass().getSimpleName());
			}
		}
	}

	private static String defaultOwner() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			host = "unknown";
		}
		return host + ":" + ProcessHandle.current().pid();
	}

}
