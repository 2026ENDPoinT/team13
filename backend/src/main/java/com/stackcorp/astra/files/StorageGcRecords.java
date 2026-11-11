package com.stackcorp.astra.files;

import java.time.Duration;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 저장소 GC가 읽고 바꾸는 행. 상태 전이는 모두 현재 상태를 조건으로 건 UPDATE라 여러 번, 여러 worker가 불러도 한 번만 반영된다.
 */
@Repository
class StorageGcRecords {

	private final JdbcClient jdbc;

	StorageGcRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 만료된 미첨부 업로드를 잠근다. 다른 worker나 첨부 확정이 잡고 있는 행은 건너뛴다. */
	List<ExpiredUpload> lockExpiredUploads(int limit) {
		return this.jdbc.sql("""
				SELECT upload_id, file_id FROM upload_sessions
				 WHERE upload_state IN ('quarantined', 'clean') AND expires_at <= CURRENT_TIMESTAMP(3)
				 ORDER BY expires_at, upload_id LIMIT :limit
				   FOR UPDATE SKIP LOCKED
				""").param("limit", limit).query((rs, n) -> new ExpiredUpload(rs.getLong(1), rs.getLong(2))).list();
	}

	/** @return 이번 호출로 expired가 됐으면 true */
	boolean expire(long uploadId) {
		return this.jdbc.sql("""
				UPDATE upload_sessions SET upload_state = 'expired'
				 WHERE upload_id = :id AND upload_state IN ('quarantined', 'clean') AND expires_at <= CURRENT_TIMESTAMP(3)
				""").param("id", uploadId).update() == 1;
	}

	/** 지운 지 grace가 지났고 파생물을 아직 정리하지 않은 파일. */
	List<Long> deletedFiles(Duration grace, int limit) {
		return this.jdbc.sql("""
				SELECT file_id FROM files
				 WHERE state = 'deleted' AND derivatives_purged_at IS NULL
				   AND COALESCE(deleted_at, updated_at, created_at) <= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :grace SECOND)
				 ORDER BY file_id LIMIT :limit
				""").param("grace", grace.toSeconds()).param("limit", limit).query(Long.class).list();
	}

	/**
	 * 파일의 rendition을 모두 expired로 닫고 key를 돌려준다. 객체를 지우기 전에 닫으므로, 아직 변환 중인 rendition은 완료로 바뀌지
	 * 못하고 자기가 쓴 객체를 스스로 지운다(DocumentRenditionService).
	 */
	List<String> closeRenditions(long fileId) {
		this.jdbc.sql("""
				UPDATE conversion_jobs SET conversion_state = 'expired', expires_at = CURRENT_TIMESTAMP(3)
				 WHERE file_id = :file AND conversion_state <> 'expired'
				""").param("file", fileId).update();
		return this.jdbc.sql("""
				SELECT converted_doc_key FROM conversion_jobs WHERE file_id = :file AND converted_doc_key IS NOT NULL
				""").param("file", fileId).query(String.class).list();
	}

	void derivativesPurged(long fileId) {
		this.jdbc.sql("""
				UPDATE files SET derivatives_purged_at = CURRENT_TIMESTAMP(3)
				 WHERE file_id = :file AND state = 'deleted' AND derivatives_purged_at IS NULL
				""").param("file", fileId).update();
	}

	/**
	 * 참조가 0이 된 지 grace가 지난 blob을 잠근다. 같은 내용의 업로드는 UploadRecords.referenceBlob에서 같은 행을 잠그므로
	 * 둘 중 하나만 먼저 판정한다. 살아 있는 파일이 가리키는 blob은 참조 수와 관계없이 고르지 않는다.
	 */
	List<UnreferencedBlob> lockUnreferencedBlobs(Duration grace, int limit) {
		return this.jdbc.sql("""
				SELECT b.file_blob_id, b.storage_key FROM file_blobs b
				 WHERE b.reference_count = 0 AND b.purged_at IS NULL
				   AND COALESCE(b.updated_at, b.created_at) <= DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :grace SECOND)
				   AND NOT EXISTS (SELECT 1 FROM files f WHERE f.file_blob_id = b.file_blob_id AND f.state = 'active')
				 ORDER BY b.file_blob_id LIMIT :limit
				   FOR UPDATE SKIP LOCKED
				""")
			.param("grace", grace.toSeconds())
			.param("limit", limit)
			.query((rs, n) -> new UnreferencedBlob(rs.getLong(1), rs.getString(2)))
			.list();
	}

	/** 객체를 지우기 전에 지운 것으로 표시한다. 그 뒤 같은 내용을 받으면 새 key에 저장한다. @return 표시했으면 true */
	boolean markBlobPurged(long blobId) {
		return this.jdbc.sql("""
				UPDATE file_blobs SET purged_at = CURRENT_TIMESTAMP(3)
				 WHERE file_blob_id = :id AND reference_count = 0 AND purged_at IS NULL
				""").param("id", blobId).update() == 1;
	}

	record ExpiredUpload(long uploadId, long fileId) {
	}

	record UnreferencedBlob(long id, String storageKey) {
	}

}
