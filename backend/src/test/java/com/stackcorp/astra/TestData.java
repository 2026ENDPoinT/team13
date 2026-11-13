package com.stackcorp.astra;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

	private static final AtomicInteger TERM_YEARS = new AtomicInteger(3000);

	private static final AtomicInteger WEEK_NUMBERS = new AtomicInteger(100_000);

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
		return this.sessions.create(userId, null, "127.0.0.1", "test").token();
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
	 * terms의 (institution_code, term_year, term_code)는 unique다. 임의 연도는 한 database에 학기가 쌓이면 같은 수가 다시 뽑혀
	 * duplicate key로 실패하므로 JVM 안에서 겹치지 않는 연도를 차례로 준다. 실제 학년도(SchoolCourseSync, LocalSeed)와 겹치지
	 * 않게 3000부터 시작한다.
	 */
	public static int termYear() {
		return TERM_YEARS.getAndIncrement();
	}

	/**
	 * course_weeks의 (course_id, week_no)도 unique다. 테스트가 직접 넣는 작은 주차 번호와 겹치지 않게 100000부터 차례로 준다.
	 */
	public static int weekNo() {
		return WEEK_NUMBERS.getAndIncrement();
	}

	/**
	 * 처리를 마친 현재 아바타 사진을 둔다(ADR-0038). 행만 만들고 객체는 두지 않는다. 사진 유무 플래그를 보는 테스트용이다.
	 */
	public void photo(long userId) {
		long imageId = insert("""
				INSERT INTO profile_images (user_id, storage_key, mime_type, content_sha256, image_status)
				VALUES (:u, :key, 'image/jpeg', REPEAT('a', 64), 'ACTIVE')
				""", Map.of("u", userId, "key", "avatars/" + UUID.randomUUID() + ".jpg"));
		this.jdbc.sql("""
				INSERT INTO user_profiles (user_id, avatar_type, profile_image_id) VALUES (:u, 'IMAGE', :i)
				ON DUPLICATE KEY UPDATE avatar_type = 'IMAGE', profile_image_id = :i
				""").param("u", userId).param("i", imageId).update();
	}

	/**
	 * @param openAt null이면 바로 열린 활동
	 */
	public long activity(long courseId, String type, boolean published, String openAt, Long attachmentGroupId) {
		long week = insert("INSERT INTO course_weeks (course_id, week_no) VALUES (:c, :n)",
				Map.of("c", courseId, "n", weekNo()));
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
