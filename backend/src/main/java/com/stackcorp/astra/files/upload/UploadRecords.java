package com.stackcorp.astra.files.upload;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.files.FileReferences;

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

	private final FileReferences references;

	UploadRecords(JdbcClient jdbc, FileReferences references) {
		this.jdbc = jdbc;
		this.references = references;
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
	 * 같은 내용의 blob을 참조한다. 없으면 만들고, 있으면 참조 수만 올린다. blob 행을 잠근 뒤 판정하므로 검사 worker의 판정(ADR-0018)과
	 * GC의 삭제 표시(StorageGc, ADR-0011)와 엇갈리지 않는다. 잠금 순서는 file_blobs → files → upload_sessions다.
	 * <ul>
	 * <li>거부한 내용(rejected)은 지웠든 아니든 바꾸지 않고 그대로 돌려준다. 부르는 쪽이 거부한다. 되살리지 않는다.</li>
	 * <li>GC가 지운 blob은 이번 요청이 새 key에 저장한 내용으로만 되살린다. GC가 아직 지우고 있을 수 있는 옛 key는 다시 쓰지 않는다.
	 * 되살린 blob은 새로 받은 내용이므로 initialState에서 다시 시작한다. 검사기가 있으면 지우기 전에 clean이었어도 다시 검사한다.</li>
	 * </ul>
	 *
	 * @param storageKey 이번 요청이 내용을 저장한 key. 살아 있는 blob을 함께 쓰려고 저장하지 않았으면 null
	 * @param initialState 새로 만들거나 되살린 blob의 blob_state. 검사기가 있으면 quarantined다(files.scan.ScanMode)
	 * @return 참조한 blob. 지운 blob인데 쓸 수 있는 새 key가 없으면 비어 있는 값. 이때는 아무것도 바꾸지 않았다
	 */
	Optional<Blob> referenceBlob(String sha256, long size, String mime, String storageKey, String initialState) {
		if (storageKey != null) {
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
				return Optional.of(new Blob(key.getKey().longValue(), initialState, storageKey, false));
			}
			catch (DuplicateKeyException ex) {
				// 같은 내용의 blob이 이미 있다. 잠가 읽어 확정된 검사 상태와 삭제 표시를 본다.
			}
		}
		Optional<Blob> locked = lockedBlob(sha256);
		if (locked.isEmpty()) {
			return Optional.empty();
		}
		Blob existing = locked.get();
		if ("rejected".equals(existing.state())) {
			return locked;
		}
		if (!existing.purged()) {
			this.jdbc.sql("UPDATE file_blobs SET reference_count = reference_count + 1 WHERE file_blob_id = :id")
				.param("id", existing.id())
				.update();
			return locked;
		}
		if (storageKey == null || storageKey.equals(existing.storageKey())) {
			return Optional.empty();
		}
		this.jdbc.sql("""
				UPDATE file_blobs
				   SET reference_count = 1, purged_at = NULL, storage_key = :key, blob_state = :state,
				       scan_attempt_count = 0, scan_next_at = NULL, scan_lease_owner = NULL, scan_lease_expires_at = NULL,
				       scan_signature = NULL, scanned_at = NULL
				 WHERE file_blob_id = :id AND purged_at IS NOT NULL AND blob_state <> 'rejected'
				""").param("key", storageKey).param("state", initialState).param("id", existing.id()).update();
		return Optional.of(new Blob(existing.id(), initialState, storageKey, false));
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

	long insertFile(long blobId, String name, String extension, String mime, long size, long uploader, Long courseId,
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
	long insertUpload(long policyId, long owner, Long courseId, long fileId, String clientKey, String state,
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

	/**
	 * 첨부하지 않은 업로드만 지운다. 검사 판정, GC와 같은 순서(file_blobs → files → upload_sessions)로 잠근다.
	 *
	 * @return 이번 호출로 지웠는지
	 */
	boolean delete(long uploadId, long owner) {
		Optional<Long> fileId = this.jdbc.sql("SELECT file_id FROM upload_sessions WHERE upload_id = :id AND owner_user_id = :owner")
			.param("id", uploadId)
			.param("owner", owner)
			.query(Long.class)
			.optional();
		if (fileId.isEmpty()) {
			return false;
		}
		this.references.lock(fileId.get());
		int updated = this.jdbc.sql("""
				UPDATE upload_sessions SET upload_state = 'deleted'
				 WHERE upload_id = :id AND owner_user_id = :owner AND upload_state IN ('quarantined', 'clean')
				""").param("id", uploadId).param("owner", owner).update();
		if (updated == 0) {
			return false;
		}
		this.references.release(fileId.get());
		return true;
	}

	/**
	 * 쓸 수 있는 업로드를 잠그고 첨부 상태로 바꾼다. 소유자, 과목, 용도, 검사 상태, 만료를 한 조건으로 확인한다. 업로드 행만 잠근다.
	 * 파일 행까지 잠그면 검사 판정(file_blobs → files → upload_sessions)과 반대 순서가 된다. clean 판정은 끝 상태이므로 잠그지 않고
	 * 읽어도 된다.
	 *
	 * @return 파일 ID. 조건에 맞지 않으면 비어 있는 값
	 */
	Optional<Long> claim(long uploadId, long owner, long courseId, long policyId) {
		Optional<Long> fileId = this.jdbc.sql("""
				SELECT u.file_id FROM upload_sessions u JOIN files f ON f.file_id = u.file_id
				 WHERE u.upload_id = :id AND u.owner_user_id = :owner AND u.course_id = :course
				   AND u.upload_policy_id = :policy AND u.upload_state = 'clean'
				   AND u.expires_at > CURRENT_TIMESTAMP(3) AND f.state = 'active' AND f.scan_state = 'clean'
				   FOR UPDATE OF u
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
