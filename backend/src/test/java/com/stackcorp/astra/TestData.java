package com.stackcorp.astra;

import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.identity.session.SessionStore;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 테스트 데이터. 행마다 고유한 값을 써서 같은 DB를 쓰는 테스트끼리 섞이지 않게 한다.
 */
public final class TestData {

	private final JdbcClient jdbc;

	private final S3Client s3;

	private final TestBuckets buckets;

	private final SessionStore sessions;

	public TestData(JdbcClient jdbc, S3Client s3, TestBuckets buckets, SessionStore sessions) {
		this.jdbc = jdbc;
		this.s3 = s3;
		this.buckets = buckets;
		this.sessions = sessions;
	}

	public long user(String role) {
		return insert("INSERT INTO users (external_ref, user_name, user_role) VALUES (:ref, :name, :role)",
				Map.of("ref", "t-" + UUID.randomUUID(), "name", "테스트 " + role, "role", role));
	}

	public long userWithRef(String externalRef, String role) {
		return insert("INSERT INTO users (external_ref, user_name, user_role) VALUES (:ref, '관리자', :role)",
				Map.of("ref", externalRef, "role", role));
	}

	/** @return session 쿠키 값 */
	public String session(long userId) {
		return this.sessions.create(userId, "127.0.0.1", "test").token();
	}

	public long course() {
		return insert("INSERT INTO courses (course_type, course_name) VALUES ('정규', 'Database')", Map.of());
	}

	public void instructor(long courseId, long userId) {
		this.jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role) VALUES (:c, :u, 'PROFESSOR')")
			.param("c", courseId)
			.param("u", userId)
			.update();
	}

	public void enroll(long courseId, long userId) {
		this.jdbc.sql("""
				INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status)
				VALUES (:c, :u, 'REGISTRAR', 'STUDENT', 'ENROLLED')
				""").param("c", courseId).param("u", userId).update();
	}

	/**
	 * @param openAt null이면 바로 열린 활동
	 */
	public long activity(long courseId, String type, boolean published, String openAt, Long attachmentGroupId) {
		long week = insert("INSERT INTO course_weeks (course_id, week_no) VALUES (:c, :n)",
				Map.of("c", courseId, "n", (int) (Math.random() * 1_000_000) + 1));
		return insert("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, published_yn, open_at, attachment_group_id)
				VALUES (:c, :w, :type, '9주차', :pub, :open, :group)
				""", params("c", courseId, "w", week, "type", type, "pub", published, "open", openAt, "group",
				attachmentGroupId));
	}

	public long attachmentGroup(long fileId) {
		long group = insert("INSERT INTO attachment_groups (group_title) VALUES ('자료')", Map.of());
		this.jdbc.sql("INSERT INTO attachments (attachment_group_id, file_id) VALUES (:g, :f)")
			.param("g", group)
			.param("f", fileId)
			.update();
		return group;
	}

	public long pdfFile(byte[] pdf, String scanState) {
		return file("week9.pdf", "application/pdf", pdf, scanState);
	}

	public long file(String name, String mime, byte[] content, String scanState) {
		String storageKey = "blobs/" + UUID.randomUUID();
		this.s3.putObject((b) -> b.bucket(this.buckets.content()).key(storageKey), RequestBody.fromBytes(content));
		String sha = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
		long blobId = insert("""
				INSERT INTO file_blobs (content_sha256, size_bytes, detected_mime_type, storage_key, blob_state, reference_count)
				VALUES (:sha, :size, :mime, :key, :state, 1)
				""", Map.of("sha", sha, "size", content.length, "mime", mime, "key", storageKey, "state", scanState));
		return insert("""
				INSERT INTO files (file_blob_id, original_file_name, file_extension, mime_type, file_size_bytes, scan_state)
				VALUES (:blob, :name, :ext, :mime, :size, :state)
				""", Map.of("blob", blobId, "name", name, "ext", name.substring(name.lastIndexOf('.') + 1), "mime", mime,
				"size", content.length, "state", scanState));
	}

	public long insert(String sql, Map<String, ?> params) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql(sql).params(params).update(key);
		return key.getKey().longValue();
	}

	private static Map<String, Object> params(Object... pairs) {
		Map<String, Object> map = new java.util.HashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((String) pairs[i], pairs[i + 1]);
		}
		return map;
	}

}
