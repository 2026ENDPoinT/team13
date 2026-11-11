package com.stackcorp.astra.files.scan;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 검사 대기열과 상태 전이. 검사는 내용 단위(file_blobs)로 하고 결과를 같은 내용을 쓰는 files, upload_sessions에 함께 옮긴다(ADR-0018).
 * 전이는 blob_state와 lease 소유자를 조건으로 한 compare-and-set이다(SC-05). 잠금 순서는 업로드 기록과 같은 file_blobs → files →
 * upload_sessions다. GC가 검사 중에 지운 것으로 표시한 blob(purged_at, ADR-0011)의 늦은 판정은 반영하지 않는다. 같은 내용을 다시
 * 받으면 그 업로드가 새 객체로 되살리고 처음부터 다시 검사한다.
 */
@Repository
class ScanRecords {

	private final JdbcClient jdbc;

	ScanRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 검사할 때가 된 내용 하나를 잡고 lease를 건다. 다른 worker가 잡고 있는 행은 건너뛴다. transaction 안에서 부른다. */
	Optional<Pending> claim(String owner, Duration lease) {
		Optional<Pending> next = this.jdbc.sql("""
				SELECT file_blob_id, storage_key, size_bytes, scan_attempt_count + 1 FROM file_blobs
				 WHERE blob_state = 'quarantined' AND purged_at IS NULL
				   AND (scan_next_at IS NULL OR scan_next_at <= CURRENT_TIMESTAMP(3))
				   AND (scan_lease_expires_at IS NULL OR scan_lease_expires_at <= CURRENT_TIMESTAMP(3))
				 ORDER BY scan_next_at, file_blob_id LIMIT 1 FOR UPDATE SKIP LOCKED
				""")
			.query((rs, n) -> new Pending(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4)))
			.optional();
		next.ifPresent((p) -> this.jdbc.sql("""
				UPDATE file_blobs
				   SET scan_lease_owner = :owner, scan_attempt_count = scan_attempt_count + 1,
				       scan_lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND)
				 WHERE file_blob_id = :id
				""").param("owner", owner).param("lease", lease.toSeconds()).param("id", p.blobId()).update());
		return next;
	}

	/** 같은 내용의 파일과 검사를 기다리던 업로드를 clean으로 옮긴다. transaction 안에서 부른다. @return lease를 아직 갖고 있었는지 */
	boolean markClean(long blobId, String owner) {
		int updated = this.jdbc.sql("""
				UPDATE file_blobs
				   SET blob_state = 'clean', scanned_at = CURRENT_TIMESTAMP(3), scan_next_at = NULL,
				       scan_lease_owner = NULL, scan_lease_expires_at = NULL
				 WHERE file_blob_id = :id AND blob_state = 'quarantined' AND scan_lease_owner = :owner AND purged_at IS NULL
				""").param("id", blobId).param("owner", owner).update();
		if (updated == 0) {
			return false;
		}
		this.jdbc.sql("UPDATE files SET scan_state = 'clean' WHERE file_blob_id = :id AND scan_state = 'pending'")
			.param("id", blobId)
			.update();
		this.jdbc.sql("""
				UPDATE upload_sessions u JOIN files f ON f.file_id = u.file_id
				   SET u.upload_state = 'clean'
				 WHERE f.file_blob_id = :id AND u.upload_state = 'quarantined'
				""").param("id", blobId).update();
		return true;
	}

	/**
	 * 내용을 거부한다. 같은 내용의 파일은 모두 infected가 되어 어떤 경로로도 읽히지 않고, 첨부 전 업로드는 rejected가 된다.
	 * transaction 안에서 부른다.
	 *
	 * @return lease를 아직 갖고 있었는지
	 */
	boolean markInfected(long blobId, String owner, String signature) {
		int updated = this.jdbc.sql("""
				UPDATE file_blobs
				   SET blob_state = 'rejected', scan_signature = :signature, scanned_at = CURRENT_TIMESTAMP(3),
				       scan_next_at = NULL, scan_lease_owner = NULL, scan_lease_expires_at = NULL
				 WHERE file_blob_id = :id AND blob_state = 'quarantined' AND scan_lease_owner = :owner AND purged_at IS NULL
				""").param("signature", signature).param("id", blobId).param("owner", owner).update();
		if (updated == 0) {
			return false;
		}
		this.jdbc.sql("UPDATE files SET scan_state = 'infected' WHERE file_blob_id = :id")
			.param("id", blobId)
			.update();
		this.jdbc.sql("""
				UPDATE upload_sessions u JOIN files f ON f.file_id = u.file_id
				   SET u.upload_state = 'rejected', u.rejection_code = 'infected'
				 WHERE f.file_blob_id = :id AND u.upload_state IN ('quarantined', 'clean')
				""").param("id", blobId).update();
		return true;
	}

	/** 검사기에 닿지 못했다. quarantined로 두고 delay 뒤에 다시 잡게 한다. */
	void retryLater(long blobId, String owner, Duration delay) {
		this.jdbc.sql("""
				UPDATE file_blobs
				   SET scan_next_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :delay SECOND),
				       scan_lease_owner = NULL, scan_lease_expires_at = NULL
				 WHERE file_blob_id = :id AND blob_state = 'quarantined' AND scan_lease_owner = :owner AND purged_at IS NULL
				""").param("delay", delay.toSeconds()).param("id", blobId).param("owner", owner).update();
	}

	/** 거부했지만 객체를 아직 지우지 않은 내용. */
	List<Rejected> unpurged(int limit) {
		return this.jdbc.sql("""
				SELECT file_blob_id, storage_key FROM file_blobs
				 WHERE blob_state = 'rejected' AND purged_at IS NULL ORDER BY file_blob_id LIMIT :limit
				""").param("limit", limit).query((rs, n) -> new Rejected(rs.getLong(1), rs.getString(2))).list();
	}

	void markPurged(long blobId) {
		this.jdbc.sql("""
				UPDATE file_blobs SET purged_at = CURRENT_TIMESTAMP(3)
				 WHERE file_blob_id = :id AND blob_state = 'rejected' AND purged_at IS NULL
				""").param("id", blobId).update();
	}

	/** @param attempt 이번 시도가 몇 번째인지. 1부터 센다 */
	record Pending(long blobId, String storageKey, long sizeBytes, int attempt) {
	}

	record Rejected(long blobId, String storageKey) {
	}

}
