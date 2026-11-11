package com.stackcorp.astra.files;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

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

import com.stackcorp.astra.files.StorageGcRecords.ExpiredUpload;
import com.stackcorp.astra.files.StorageGcRecords.UnreferencedBlob;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;

/**
 * worker의 저장소 GC(ADR-0010, ADR-0011). 한 번에 정해진 수만큼 처리하고, 같은 일을 다시 해도 결과가 같다.
 * worker가 둘이어도 DB 상태 전이가 한 번만 반영되고, 겹쳐 지우는 S3 객체는 없는 객체 삭제라 해가 없다.
 * S3 호출은 DB transaction 밖에서 한다.
 * <ol>
 * <li>만료된 미첨부 업로드를 expired로 바꾸고 파일을 지운다. blob 참조 수를 줄인다.</li>
 * <li>지운 파일의 파생물(derivatives/{fileId}/)과 rendition(content renditions/{key}/, delivery doc/{key}/)을 지운다.</li>
 * <li>참조가 0인 blob을 먼저 지운 것으로 표시한 뒤 객체를 지운다. 표시 뒤에 같은 내용이 오면 새 key로 저장된다.</li>
 * </ol>
 * transaction은 READ COMMITTED다. 다른 행의 index 틈을 잠그지 않아, 이웃한 행을 다루는 다른 worker나 요청과 서로 기다리지 않는다.
 * 잠금 순서는 file_blobs → files → upload_sessions다. 교착이나 잠금 대기 초과로 실패한 transaction은 정해진 횟수만큼 다시 하고,
 * 끝내 실패한 행은 상태가 바뀌지 않은 채 남아 다음 실행에서 다시 고른다. 실패는 그 단계의 실패로 올린다. 로그에는 개수와 ID만 남긴다.
 */
@Component
@Profile("worker")
class StorageGc {

	private static final Logger log = LoggerFactory.getLogger(StorageGc.class);

	private static final int ATTEMPTS = 3;

	private final StorageGcRecords records;

	private final FileReferences references;

	private final ObjectStore store;

	private final TransactionTemplate transaction;

	private final StorageGcProperties properties;

	StorageGc(StorageGcRecords records, FileReferences references, ObjectStore store,
			PlatformTransactionManager transactionManager, StorageGcProperties properties) {
		this.records = records;
		this.references = references;
		this.store = store;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${astra.files.gc.interval:1m}", initialDelayString = "${astra.files.gc.interval:1m}")
	void run() {
		step("expired uploads", this::expireUploads);
		step("deleted files", this::purgeDeletedFiles);
		step("unreferenced blobs", this::purgeBlobs);
	}

	/**
	 * 업로드마다 짧은 transaction에서 file_blobs → files → upload_sessions 순서로 잠근 뒤 바꾼다. 두 worker가 같은 업로드를 골라도
	 * 상태 조건 때문에 한쪽만 바꾼다.
	 *
	 * @return expired로 바꾼 업로드 수
	 * @throws IllegalStateException 다시 해도 바꾸지 못한 업로드가 있을 때. 나머지는 처리한 뒤 올린다
	 */
	int expireUploads() {
		int count = 0;
		List<Long> failed = new ArrayList<>();
		for (ExpiredUpload upload : this.records.expiredUploads(this.properties.batchSize())) {
			try {
				boolean expired = inTransaction((tx) -> {
					this.references.lock(upload.fileId());
					if (!this.records.expire(upload.uploadId())) {
						return false;
					}
					this.references.release(upload.fileId());
					return true;
				});
				if (expired) {
					count++;
				}
			}
			catch (RuntimeException ex) {
				log.warn("storage gc failed to expire upload uploadId={}", upload.uploadId(), ex);
				failed.add(upload.uploadId());
			}
		}
		failIfAny("expire uploads", failed, count);
		return count;
	}

	/**
	 * @return 파생물을 정리한 파일 수
	 * @throws IllegalStateException 정리하지 못한 파일이 있을 때. 표시하지 않았으므로 다음 실행에서 다시 지운다
	 */
	int purgeDeletedFiles() {
		int purged = 0;
		List<Long> failed = new ArrayList<>();
		for (long fileId : this.records.deletedFiles(this.properties.derivativeGrace(), this.properties.batchSize())) {
			try {
				List<String> keys = inTransaction((tx) -> this.records.closeRenditions(fileId));
				this.store.deletePrefix(Bucket.CONTENT, FileDerivativeStore.prefix(fileId));
				for (String key : keys) {
					this.store.deletePrefix(Bucket.DELIVERY, DocumentRenditionService.deliveryPrefix(key));
					this.store.deletePrefix(Bucket.CONTENT, DocumentRenditionService.contentPrefix(key));
				}
				inTransaction((tx) -> {
					this.records.derivativesPurged(fileId);
					return null;
				});
				purged++;
			}
			catch (RuntimeException ex) {
				log.warn("storage gc failed to purge derivatives fileId={}", fileId, ex);
				failed.add(fileId);
			}
		}
		failIfAny("purge derivatives", failed, purged);
		return purged;
	}

	/**
	 * @return 객체를 지운 blob 수
	 * @throws IllegalStateException 지운 것으로 표시한 뒤 객체를 지우지 못한 blob이 있을 때. 그 객체는 닿을 수 없는 채 남는다
	 */
	int purgeBlobs() {
		List<UnreferencedBlob> marked = inTransaction((tx) -> {
			List<UnreferencedBlob> blobs = new ArrayList<>();
			for (UnreferencedBlob blob : this.records.lockUnreferencedBlobs(this.properties.blobGrace(),
					this.properties.batchSize())) {
				if (this.records.markBlobPurged(blob.id())) {
					blobs.add(blob);
				}
			}
			return blobs;
		});
		int deleted = 0;
		List<Long> failed = new ArrayList<>();
		for (UnreferencedBlob blob : marked) {
			try {
				this.store.delete(Bucket.CONTENT, blob.storageKey());
				deleted++;
			}
			catch (RuntimeException ex) {
				log.error("storage gc marked blob purged but failed to delete object blobId={}", blob.id(), ex);
				failed.add(blob.id());
			}
		}
		failIfAny("delete purged blob objects", failed, deleted);
		return deleted;
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
				log.info("storage gc transaction retried attempt={} error={}", attempt, ex.getClass().getSimpleName());
			}
		}
	}

	private static void failIfAny(String what, List<Long> failed, int done) {
		if (!failed.isEmpty()) {
			throw new IllegalStateException(
					"storage gc could not " + what + " ids=" + failed + " (completed " + done + ")");
		}
	}

	private static void step(String name, IntSupplier work) {
		try {
			int count = work.getAsInt();
			if (count > 0) {
				log.info("storage gc step={} count={}", name, count);
			}
		}
		catch (RuntimeException ex) {
			log.error("storage gc step failed step={}", name, ex);
		}
	}

}
