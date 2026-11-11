package com.stackcorp.astra.files.filebox;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.stackcorp.astra.files.filebox.ZipJobRecords.Claimed;
import com.stackcorp.astra.files.filebox.ZipJobRecords.Entry;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 파일함 ZIP을 만들고 TTL이 지난 생성물을 지운다(EP-0263, FILES-08). 작업은 하나씩 SKIP LOCKED와 lease로 잡으므로 worker가
 * 둘이어도 같은 작업을 함께 만들지 않는다. 파일을 하나 넣을 때마다 lease를 늘리고, lease를 잃으면 멈추고 쓰던 객체를 버린다.
 * <p>
 * 원본은 content bucket에서 읽어 바로 압축해 content bucket의 zips/{작업 ID}/{임의 값}.zip에 multipart upload로 쓴다. 메모리에는
 * part 하나만 두고 로컬 디스크에는 쓰지 않는다. ZIP 항목 이름은 {@link ZipEntryNames}가 정한다.
 * <p>
 * 실패는 다시 할 수 있는 것(저장소, DB)과 없는 것(항목이 휴지통에 갔거나 내용이 사라짐)으로 나눈다. 다시 할 수 있으면 lease를 풀고
 * 기다림으로 되돌리며, 시도 수를 다 쓰면 failed로 닫는다. 상태 전이는 READ COMMITTED transaction에서 lease 소유자를 조건으로 한
 * compare-and-set이고 잠금 충돌로 되돌려지면 몇 번 다시 한다. 다시 해도 기록하지 못한 전이는 예외로 올린다. 로그에는 작업 ID, 시도 수,
 * 사유 코드만 남긴다.
 */
@Component
@Profile("worker")
class ZipJobWorker {

	private static final Logger log = LoggerFactory.getLogger(ZipJobWorker.class);

	private static final int TRANSITION_ATTEMPTS = 3;

	private static final int BATCH = 20;

	private static final SecureRandom RANDOM = new SecureRandom();

	private final ZipJobRecords records;

	private final ObjectStore store;

	private final TransactionTemplate transaction;

	private final FileboxProperties.Zip properties;

	private final String owner;

	@Autowired
	ZipJobWorker(ZipJobRecords records, ObjectStore store, PlatformTransactionManager transactionManager,
			FileboxProperties properties) {
		this(records, store, transactionManager, properties,
				properties.zip().workerId() != null ? properties.zip().workerId() : defaultOwner());
	}

	ZipJobWorker(ZipJobRecords records, ObjectStore store, PlatformTransactionManager transactionManager,
			FileboxProperties properties, String owner) {
		this.records = records;
		this.store = store;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.properties = properties.zip();
		this.owner = owner;
	}

	@Scheduled(fixedDelayString = "${astra.filebox.zip.poll-interval:10s}",
			initialDelayString = "${astra.filebox.zip.poll-interval:10s}")
	void poll() {
		step("build", this::runOnce);
		step("expire", this::expireArchives);
	}

	/** @return 이번에 잡아 만들기를 시도한 작업 수 */
	int runOnce() {
		int attempted = 0;
		while (attempted < BATCH) {
			Optional<Claimed> claimed = claim();
			if (claimed.isEmpty()) {
				break;
			}
			attempted++;
			build(claimed.get());
		}
		return attempted;
	}

	/** 시도 수를 다 쓴 작업을 닫고, 만들 작업 하나를 잡는다. */
	Optional<Claimed> claim() {
		return inTransaction((tx) -> {
			int exhausted = this.records.failExhausted(this.properties.maxAttempts(), this.properties.ttl());
			if (exhausted > 0) {
				log.warn("zip jobs failed after max attempts count={}", exhausted);
			}
			return this.records.claim(this.owner, this.properties.lease(), this.properties.maxAttempts());
		});
	}

	void build(Claimed job) {
		List<Entry> entries = inTransaction((tx) -> this.records.entries(job.id(), job.owner()));
		if (entries.isEmpty() || entries.stream().anyMatch((e) -> !e.available())) {
			close(job, "item_unavailable");
			return;
		}
		List<String> names = ZipEntryNames.of(entries.stream().map(Entry::name).toList());
		String key = prefix(job.id()) + randomHex() + ".zip";
		long size;
		try (ObjectStore.Upload out = this.store.upload(Bucket.CONTENT, key, "application/zip");
				ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
			zip.setLevel(Deflater.BEST_SPEED);
			for (int i = 0; i < entries.size(); i++) {
				Entry entry = entries.get(i);
				Optional<InputStream> content = this.store.open(Bucket.CONTENT, entry.storageKey());
				if (content.isEmpty()) {
					throw new Unavailable();
				}
				try (InputStream in = content.get()) {
					ZipEntry zipEntry = new ZipEntry(names.get(i));
					zipEntry.setTimeLocal(entry.createdAt());
					zip.putNextEntry(zipEntry);
					in.transferTo(zip);
					zip.closeEntry();
				}
				if (!transition(() -> this.records.renew(job.id(), this.owner, this.properties.lease()))) {
					throw new LeaseLost();
				}
			}
			zip.finish();
			size = out.complete();
		}
		catch (LeaseLost ex) {
			log.info("zip job lease lost jobId={} attempt={}", job.id(), job.attempt());
			return;
		}
		catch (Unavailable ex) {
			close(job, "item_unavailable");
			return;
		}
		catch (IOException | RuntimeException ex) {
			log.warn("zip job build failed jobId={} attempt={} error={}", job.id(), job.attempt(),
					ex.getClass().getSimpleName());
			retryOrFail(job);
			return;
		}
		if (!transition(() -> this.records.complete(job.id(), this.owner, key, size, this.properties.ttl()))) {
			// 그 사이 다른 worker가 이 작업을 잡았다. 그 worker의 결과를 쓰므로 이 객체는 남기지 않는다.
			this.store.delete(Bucket.CONTENT, key);
			log.info("zip job result discarded, lease lost jobId={}", job.id());
			return;
		}
		log.info("zip job ready jobId={} attempt={} entries={}", job.id(), job.attempt(), entries.size());
	}

	private void retryOrFail(Claimed job) {
		if (job.attempt() >= this.properties.maxAttempts()) {
			close(job, "build_failed");
		}
		else if (!transition(() -> this.records.retry(job.id(), this.owner))) {
			log.info("zip job retry skipped, lease lost jobId={}", job.id());
		}
	}

	private void close(Claimed job, String reason) {
		if (transition(() -> this.records.fail(job.id(), this.owner, reason, this.properties.ttl()))) {
			log.warn("zip job failed jobId={} attempt={} reason={}", job.id(), job.attempt(), reason);
		}
		else {
			log.info("zip job failure skipped, lease lost jobId={}", job.id());
		}
	}

	/**
	 * TTL이 지난 작업을 expired로 바꾸고 commit한 뒤 zips/{작업 ID}/ 아래 객체를 모두 지운다. 지난 시도가 남긴 객체도 함께 지운다.
	 * 지우지 못한 작업은 purged_at을 쓰지 않으므로 다음 실행에서 다시 지운다.
	 *
	 * @return 객체를 지운 작업 수
	 * @throws IllegalStateException 지우지 못한 작업이 있을 때. 나머지는 처리한 뒤 올린다
	 */
	int expireArchives() {
		List<Long> expired = inTransaction((tx) -> this.records.expire(BATCH));
		int purged = 0;
		List<Long> failed = new ArrayList<>();
		for (long jobId : expired) {
			try {
				this.store.deletePrefix(Bucket.CONTENT, prefix(jobId));
				inTransaction((tx) -> {
					this.records.purged(jobId);
					return null;
				});
				purged++;
			}
			catch (RuntimeException ex) {
				log.warn("zip archive purge failed jobId={} error={}", jobId, ex.getClass().getSimpleName());
				failed.add(jobId);
			}
		}
		if (!failed.isEmpty()) {
			throw new IllegalStateException("zip gc could not delete archives ids=" + failed + " (completed " + purged + ")");
		}
		return purged;
	}

	static String prefix(long jobId) {
		return "zips/" + jobId + "/";
	}

	private boolean transition(java.util.function.BooleanSupplier write) {
		return Boolean.TRUE.equals(inTransaction((tx) -> write.getAsBoolean()));
	}

	// 교착, 잠금 대기 초과처럼 다시 하면 될 수 있는 실패만 다시 한다. transaction은 실패할 때마다 통째로 되돌아간다.
	private <T> T inTransaction(TransactionCallback<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute(work);
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= TRANSITION_ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	private static void step(String name, IntSupplier work) {
		try {
			int count = work.getAsInt();
			if (count > 0) {
				log.info("zip worker step={} count={}", name, count);
			}
		}
		catch (RuntimeException ex) {
			log.error("zip worker step failed step={}", name, ex);
		}
	}

	private static String randomHex() {
		byte[] bytes = new byte[16];
		RANDOM.nextBytes(bytes);
		return HexFormat.of().formatHex(bytes);
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

	// 항목이 휴지통에 갔거나 내용 객체가 사라졌다. 다시 해도 같다.
	private static final class Unavailable extends RuntimeException {

		Unavailable() {
			super(null, null, false, false);
		}

	}

	private static final class LeaseLost extends RuntimeException {

		LeaseLost() {
			super(null, null, false, false);
		}

	}

}
