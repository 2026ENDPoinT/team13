package com.stackcorp.astra.files;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
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
 * 로그에는 개수와 ID만 남긴다.
 */
@Component
@Profile("worker")
class StorageGc {

	private static final Logger log = LoggerFactory.getLogger(StorageGc.class);

	private final StorageGcRecords records;

	private final FileReferences references;

	private final ObjectStore store;

	private final TransactionTemplate transaction;

	private final StorageGcProperties properties;

	StorageGc(StorageGcRecords records, FileReferences references, ObjectStore store, TransactionTemplate transaction,
			StorageGcProperties properties) {
		this.records = records;
		this.references = references;
		this.store = store;
		this.transaction = transaction;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${astra.files.gc.interval:1m}", initialDelayString = "${astra.files.gc.interval:1m}")
	void run() {
		step("expired uploads", this::expireUploads);
		step("deleted files", this::purgeDeletedFiles);
		step("unreferenced blobs", this::purgeBlobs);
	}

	/** @return expired로 바꾼 업로드 수 */
	int expireUploads() {
		Integer expired = this.transaction.execute((tx) -> {
			int count = 0;
			for (ExpiredUpload upload : this.records.lockExpiredUploads(this.properties.batchSize())) {
				if (this.records.expire(upload.uploadId())) {
					this.references.release(upload.fileId());
					count++;
				}
			}
			return count;
		});
		return expired == null ? 0 : expired;
	}

	/** @return 파생물을 정리한 파일 수 */
	int purgeDeletedFiles() {
		int purged = 0;
		for (long fileId : this.records.deletedFiles(this.properties.derivativeGrace(), this.properties.batchSize())) {
			try {
				List<String> keys = this.transaction.execute((tx) -> this.records.closeRenditions(fileId));
				this.store.deletePrefix(Bucket.CONTENT, FileDerivativeStore.prefix(fileId));
				for (String key : keys == null ? List.<String>of() : keys) {
					this.store.deletePrefix(Bucket.DELIVERY, DocumentRenditionService.deliveryPrefix(key));
					this.store.deletePrefix(Bucket.CONTENT, DocumentRenditionService.contentPrefix(key));
				}
				this.transaction.executeWithoutResult((tx) -> this.records.derivativesPurged(fileId));
				purged++;
			}
			catch (RuntimeException ex) {
				// 표시하지 않았으므로 다음 실행에서 다시 지운다.
				log.warn("storage gc failed to purge derivatives fileId={}", fileId, ex);
			}
		}
		return purged;
	}

	/** @return 객체를 지운 blob 수 */
	int purgeBlobs() {
		List<UnreferencedBlob> marked = this.transaction.execute((tx) -> {
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
		for (UnreferencedBlob blob : marked == null ? List.<UnreferencedBlob>of() : marked) {
			try {
				this.store.delete(Bucket.CONTENT, blob.storageKey());
				deleted++;
			}
			catch (RuntimeException ex) {
				log.error("storage gc marked blob purged but failed to delete object blobId={}", blob.id(), ex);
			}
		}
		return deleted;
	}

	private static void step(String name, IntSupplier work) {
		try {
			int count = work.getAsInt();
			if (count > 0) {
				log.info("storage gc step={} count={}", name, count);
			}
		}
		catch (RuntimeException ex) {
			log.warn("storage gc step failed step={}", name, ex);
		}
	}

}
