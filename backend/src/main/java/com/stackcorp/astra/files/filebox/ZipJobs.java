package com.stackcorp.astra.files.filebox;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import com.stackcorp.astra.files.Downloads;
import com.stackcorp.astra.files.filebox.FileboxRecords.Item;
import com.stackcorp.astra.files.filebox.ZipJobRecords.Job;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.core.io.InputStreamResource;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 파일함 ZIP 일괄 다운로드(EP-0262~0264의 대체 계약, FILES-08). 요청은 검사와 기록만 하고 202로 끝난다. 생성은 worker의
 * {@link ZipJobWorker}가 한다. 작업과 생성물은 요청한 사람만 본다.
 * <ul>
 * <li>모든 항목이 요청한 사람의 활성 파일이고 검사를 통과했어야 한다. 남의 항목이 하나라도 섞이면 404로 거부한다(SEC-08).</li>
 * <li>항목 수, 크기 합, 사용자당 기다리거나 만드는 중인 작업 수에 상한이 있다(SEC-18). 상한은 사용량 행을 잠근 뒤 센다.</li>
 * <li>같은 항목 집합으로 기다리는 중이거나 만드는 중이거나 아직 내려받을 수 있는 작업이 있으면 새로 만들지 않고 그 작업을 돌려준다.</li>
 * <li>Idempotency-Key가 필요하다. 재전송은 처음 응답을 돌려준다.</li>
 * </ul>
 */
@Service
class ZipJobs {

	private static final int ATTEMPTS = 3;

	private final ZipJobRecords jobs;

	private final FileboxRecords items;

	private final ObjectStore store;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final TransactionTemplate transaction;

	private final FileboxProperties.Zip properties;

	ZipJobs(ZipJobRecords jobs, FileboxRecords items, ObjectStore store, RateLimiter rateLimiter,
			Idempotency idempotency, AuditLog audit, PlatformTransactionManager transactionManager,
			FileboxProperties properties) {
		this.jobs = jobs;
		this.items = items;
		this.store = store;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.properties = properties.zip();
	}

	/** @param itemIds 중복 없는 파일함 항목 ID. ZIP 안 순서 */
	JobView request(Actor actor, List<Long> itemIds, String idempotencyKey) {
		Idempotency.requireKey(idempotencyKey);
		if (itemIds.size() > this.properties.maxItems()) {
			throw ApiException.invalid("ZIP에는 파일을 " + this.properties.maxItems() + "개까지 넣을 수 있습니다.");
		}
		long owner = actor.userId();
		this.rateLimiter.check("filebox-zip", owner, this.properties.rateLimit(), this.properties.rateWindow());
		// 권한을 먼저 본다. 권한을 잃은 사람은 저장한 receipt도 받지 못한다.
		if (this.items.items(owner, itemIds).size() != itemIds.size()) {
			throw ApiException.notFound();
		}
		return inTransaction(() -> this.idempotency.execute(owner, "filebox.zip_job.create", idempotencyKey, itemIds,
				JobView.class, () -> {
					this.items.lockQuota(owner);
					List<Item> selected = this.items.items(owner, itemIds);
					if (selected.size() != itemIds.size()) {
						throw ApiException.notFound();
					}
					long total = 0;
					for (Item item : selected) {
						if (!item.active()) {
							throw new ApiException(HttpStatus.CONFLICT, "not_active", "휴지통에 있는 항목입니다.");
						}
						if (item.folder()) {
							throw new ApiException(HttpStatus.CONFLICT, "folder_not_allowed", "폴더는 ZIP에 넣을 수 없습니다.");
						}
						if (!"clean".equals(item.scanState())) {
							throw new ApiException(HttpStatus.CONFLICT, "not_ready", "검사를 마치지 않았거나 거부된 파일입니다.");
						}
						total += item.sizeBytes();
					}
					if (total > this.properties.maxTotalSize().toBytes()) {
						throw new ApiException(HttpStatus.CONTENT_TOO_LARGE, "zip_too_large", "ZIP에 넣을 파일의 크기 합이 너무 큽니다.");
					}
					String hash = itemSetHash(itemIds);
					Optional<Job> existing = this.jobs.reusable(owner, hash);
					if (existing.isPresent()) {
						return JobView.of(existing.get());
					}
					if (this.jobs.pending(owner) >= this.properties.maxConcurrent()) {
						throw new ApiException(HttpStatus.CONFLICT, "too_many_jobs", "진행 중인 ZIP 작업이 끝난 뒤 다시 요청해 주세요.");
					}
					long jobId = this.jobs.insert(owner, hash, itemIds, total);
					this.audit.success(owner, actor.sessionId(), "filebox.zip_job.create", "zip_job", jobId, null);
					return JobView.of(this.jobs.find(owner, jobId).orElseThrow());
				}));
	}

	JobView status(long owner, long jobId) {
		return JobView.of(this.jobs.find(owner, jobId).orElseThrow(ApiException::notFound));
	}

	/** 요청한 사람만 내려받는다. 만들고 있으면 409, TTL이 지났으면 410이다. */
	ResponseEntity<InputStreamResource> download(Actor actor, long jobId) {
		Job job = this.jobs.find(actor.userId(), jobId).orElseThrow(ApiException::notFound);
		if ("expired".equals(job.state())) {
			throw new ApiException(HttpStatus.GONE, "expired", "내려받을 수 있는 시간이 지났습니다.");
		}
		if (!"ready".equals(job.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "not_ready", "ZIP을 아직 만들지 않았습니다.");
		}
		InputStream in = this.store.open(Bucket.CONTENT, job.storageKey()).orElseThrow(ApiException::notFound);
		this.audit.success(actor.userId(), actor.sessionId(), "filebox.zip_job.download", "zip_job", jobId, null);
		return Downloads.attachment("filebox-" + jobId + ".zip", "application/zip", job.sizeBytes(), in);
	}

	// 순서와 관계없는 항목 집합의 SHA-256.
	private static String itemSetHash(List<Long> itemIds) {
		String canonical = itemIds.stream().sorted().map(String::valueOf).collect(Collectors.joining(","));
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	// 교착, 잠금 대기 초과처럼 다시 하면 될 수 있는 실패만 다시 한다. transaction은 실패할 때마다 통째로 되돌아간다.
	private <T> T inTransaction(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute((tx) -> work.get());
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	/**
	 * @param state requested, building, ready, failed, expired
	 * @param sizeBytes 만든 ZIP의 크기. ready일 때만
	 * @param failure 실패 사유 코드(item_unavailable, build_failed)
	 */
	record JobView(long jobId, String state, int itemCount, long totalBytes, Long sizeBytes, Instant expiresAt,
			String failure) {

		static JobView of(Job job) {
			return new JobView(job.id(), job.state(), job.itemCount(), job.totalBytes(),
					"ready".equals(job.state()) ? job.sizeBytes() : null, job.expiresAt(), job.failure());
		}

	}

}
