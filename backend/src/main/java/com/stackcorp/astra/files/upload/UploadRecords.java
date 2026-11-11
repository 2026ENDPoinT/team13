package com.stackcorp.astra.files.upload;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * upload_policies, upload_sessions와 업로드가 만드는 file_blobs, files 행.
 */
@Repository
class UploadRecords {

	private final JdbcClient jdbc;

	UploadRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Policy> policy(String code) {
		return this.jdbc.sql("""
				SELECT upload_policy_id, max_file_bytes, max_file_count, allowed_extensions
				  FROM upload_policies WHERE policy_code = :code AND is_active = 1
				""")
			.param("code", code)
			.query((rs, n) -> new Policy(rs.getLong(1), rs.getObject(2, Long.class), rs.getObject(3, Integer.class),
					rs.getString(4) == null ? List.of() : Arrays.stream(rs.getString(4).split(",")).map(String::strip).toList()))
			.optional();
	}

	Optional<Blob> blob(String sha256) {
		return this.jdbc
			.sql("SELECT file_blob_id, blob_state, storage_key, purged_at IS NOT NULL FROM file_blobs WHERE content_sha256 = :sha")
			.param("sha", sha256)
			.query((rs, n) -> new Blob(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
			.optional();
	}

	/**
	 * 같은 내용의 blob이 있으면 참조 수만 올린다. 지웠던 blob이면 다시 쓴다.
	 *
	 * @param initialState 새 blob의 blob_state. 검사기가 있으면 quarantined다(files.scan.ScanMode)
	 */
	Blob referenceBlob(String sha256, long size, String mime, String storageKey, String initialState) {
		try {
			GeneratedKeyHolder key = new GeneratedKeyHolder();
			this.jdbc.sql("""
					INSERT INTO file_blobs (content_sha256, size_bytes, detected_mime_type, storage_key, blob_state, reference_count)
					VALUES (:sha, :size, :mime, :key, :state, 1)
					""")
				.param("sha", sha256)
				.param("size", size)
				.param("mime", mime)
				.param("key", storageKey)
				.param("state", initialState)
				.update(key, "file_blob_id");
			return new Blob(key.getKey().longValue(), initialState, storageKey, false);
		}
		catch (DuplicateKeyException ex) {
			// 검사 worker가 같은 행을 바꾸는 중일 수 있다. 잠가 읽어 확정된 검사 상태를 본다(ADR-0018).
			Blob existing = lockedBlob(sha256).orElseThrow(() -> ex);
			if (existing.purged()) {
				this.jdbc.sql("""
						UPDATE file_blobs SET reference_count = 1, purged_at = NULL, storage_key = :key
						 WHERE file_blob_id = :id AND purged_at IS NOT NULL
						""").param("key", storageKey).param("id", existing.id()).update();
			}
			else {
				this.jdbc.sql("UPDATE file_blobs SET reference_count = reference_count + 1 WHERE file_blob_id = :id")
					.param("id", existing.id())
					.update();
			}
			return existing;
		}
	}

	private Optional<Blob> lockedBlob(String sha256) {
		return this.jdbc.sql("""
				SELECT file_blob_id, blob_state, storage_key, purged_at IS NOT NULL FROM file_blobs
				 WHERE content_sha256 = :sha FOR UPDATE
				""")
			.param("sha", sha256)
			.query((rs, n) -> new Blob(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getBoolean(4)))
			.optional();
	}

	long insertFile(long blobId, String name, String extension, String mime, long size, long uploader, long courseId,
			String purpose, String scanState) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO files (file_blob_id, original_file_name, file_extension, mime_type, file_size_bytes,
				                   uploader_user_id, course_id, source_menu_code, scan_state, preview_convertible)
				VALUES (:blob, :name, :ext, :mime, :size, :uploader, :course, :purpose, :scan, :convertible)
				""")
			.param("blob", blobId)
			.param("name", name)
			.param("ext", extension)
			.param("mime", mime)
			.param("size", size)
			.param("uploader", uploader)
			.param("course", courseId)
			.param("purpose", purpose)
			.param("scan", scanState)
			.param("convertible", "application/pdf".equals(mime))
			.update(key, "file_id");
		return key.getKey().longValue();
	}

	/** @throws DuplicateKeyException 같은 사용자가 같은 멱등 key로 이미 올렸을 때 */
	long insertUpload(long policyId, long owner, long courseId, long fileId, String clientKey, String state,
			Duration ttl) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO upload_sessions (upload_policy_id, owner_user_id, course_id, file_id, client_upload_key,
				                             upload_state, expires_at)
				VALUES (:policy, :owner, :course, :file, :key, :state, DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :ttl SECOND))
				""")
			.param("policy", policyId)
			.param("owner", owner)
			.param("course", courseId)
			.param("file", fileId)
			.param("key", clientKey)
			.param("state", state)
			.param("ttl", ttl.toSeconds())
			.update(key, "upload_id");
		return key.getKey().longValue();
	}

	Optional<Upload> find(long uploadId, long owner) {
		return this.jdbc.sql(SELECT + " WHERE u.upload_id = :id AND u.owner_user_id = :owner")
			.param("id", uploadId)
			.param("owner", owner)
			.query(UploadRecords::upload)
			.optional();
	}

	Optional<Upload> findByKey(long owner, String clientKey) {
		return this.jdbc.sql(SELECT + " WHERE u.owner_user_id = :owner AND u.client_upload_key = :key")
			.param("owner", owner)
			.param("key", clientKey)
			.query(UploadRecords::upload)
			.optional();
	}

	/** 첨부하지 않은 업로드만 지운다. @return 이번 호출로 지웠는지 */
	boolean delete(long uploadId, long owner) {
		int updated = this.jdbc.sql("""
				UPDATE upload_sessions SET upload_state = 'deleted'
				 WHERE upload_id = :id AND owner_user_id = :owner AND upload_state IN ('quarantined', 'clean')
				""").param("id", uploadId).param("owner", owner).update();
		if (updated == 0) {
			return false;
		}
		this.jdbc.sql("""
				UPDATE files f JOIN upload_sessions u ON u.file_id = f.file_id
				   SET f.state = 'deleted', f.deleted_at = CURRENT_TIMESTAMP(3)
				 WHERE u.upload_id = :id AND f.state = 'active'
				""").param("id", uploadId).update();
		this.jdbc.sql("""
				UPDATE file_blobs b JOIN files f ON f.file_blob_id = b.file_blob_id JOIN upload_sessions u ON u.file_id = f.file_id
				   SET b.reference_count = b.reference_count - 1
				 WHERE u.upload_id = :id AND b.reference_count > 0
				""").param("id", uploadId).update();
		return true;
	}

	/**
	 * 쓸 수 있는 업로드를 잠그고 첨부 상태로 바꾼다. 소유자, 과목, 용도, 검사 상태, 만료를 한 조건으로 확인한다.
	 *
	 * @return 파일 ID. 조건에 맞지 않으면 비어 있는 값
	 */
	Optional<Long> claim(long uploadId, long owner, long courseId, long policyId) {
		Optional<Long> fileId = this.jdbc.sql("""
				SELECT u.file_id FROM upload_sessions u JOIN files f ON f.file_id = u.file_id
				 WHERE u.upload_id = :id AND u.owner_user_id = :owner AND u.course_id = :course
				   AND u.upload_policy_id = :policy AND u.upload_state = 'clean'
				   AND u.expires_at > CURRENT_TIMESTAMP(3) AND f.state = 'active' AND f.scan_state = 'clean'
				   FOR UPDATE
				""")
			.param("id", uploadId)
			.param("owner", owner)
			.param("course", courseId)
			.param("policy", policyId)
			.query(Long.class)
			.optional();
		fileId.ifPresent((f) -> this.jdbc
			.sql("UPDATE upload_sessions SET upload_state = 'attached', attached_at = CURRENT_TIMESTAMP(3) WHERE upload_id = :id")
			.param("id", uploadId)
			.update());
		return fileId;
	}

	private static final String SELECT = """
			SELECT u.upload_id, u.course_id, u.upload_state, u.expires_at, u.expires_at <= CURRENT_TIMESTAMP(3) AS expired,
			       f.original_file_name, f.file_size_bytes
			  FROM upload_sessions u JOIN files f ON f.file_id = u.file_id
			""";

	private static Upload upload(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		String state = rs.getString("upload_state");
		boolean pending = "quarantined".equals(state) || "clean".equals(state);
		Timestamp expires = rs.getTimestamp("expires_at");
		return new Upload(rs.getLong("upload_id"), rs.getLong("course_id"),
				pending && rs.getBoolean("expired") ? "expired" : state, rs.getString("original_file_name"),
				rs.getLong("file_size_bytes"), expires.toInstant());
	}

	record Policy(long id, Long maxFileBytes, Integer maxFileCount, List<String> extensions) {
	}

	record Blob(long id, String state, String storageKey, boolean purged) {
	}

	record Upload(long uploadId, long courseId, String state, String name, long sizeBytes, Instant expiresAt) {
	}

}
