package com.stackcorp.astra.local;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.files.storage.ObjectStore;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 파일함. 학생 박민준과 교수 박은주에게 폴더 트리와 작은 파일 몇 개, 휴지통 항목 하나를 넣는다. 파일은 올리기
 * (FileboxUploads)와 같이 내용의 SHA-256으로 저장소에 실제 객체를 두고 blob, 파일, 업로드(attached), 파일함 항목, 사용량 행을 쓴다.
 * 로컬 실행에는 악성코드 검사기가 없으므로 검사 상태는 clean이다(ScanMode). 그 사용자의 파일함 항목이 이미 있으면 넣지 않는다.
 */
final class LocalFileboxSeed {

	private final JdbcClient jdbc;

	private final ObjectStore store;

	private final long owner;

	private long bytes;

	private int items;

	private LocalFileboxSeed(JdbcClient jdbc, ObjectStore store, long owner) {
		this.jdbc = jdbc;
		this.store = store;
		this.owner = owner;
	}

	static void seed(JdbcClient jdbc, ObjectStore store, long student, long professor) {
		if (!empty(jdbc, student)) {
			return;
		}
		LocalFileboxSeed mine = new LocalFileboxSeed(jdbc, store, student);
		long term = mine.folder(null, "2026-2학기", "INTERVAL 20 DAY");
		long db = mine.folder(term, "데이터베이스", "INTERVAL 19 DAY");
		mine.folder(term, "기계학습", "INTERVAL 18 DAY");
		mine.file(db, "관계대수 정리.pdf", "application/pdf", TestPdfs.pdfWithText("Relational algebra notes", "Join"),
				"INTERVAL 5 DAY");
		mine.file(db, "ER 다이어그램 메모.txt", "text/plain", text("학생-수강-과목 관계를 다대다로 그린다.\n"), "INTERVAL 4 DAY");
		mine.file(null, "자기소개서 초안.txt", "text/plain", text("지원 동기와 프로젝트 경험을 정리한다.\n"), "INTERVAL 2 DAY");
		long old = mine.file(null, "오래된 메모.txt", "text/plain", text("지난 학기 메모.\n"), "INTERVAL 40 DAY");
		mine.trash(old, "INTERVAL 1 DAY");
		mine.usage();

		if (!empty(jdbc, professor)) {
			return;
		}
		LocalFileboxSeed prof = new LocalFileboxSeed(jdbc, store, professor);
		long lectures = prof.folder(null, "강의자료", "INTERVAL 30 DAY");
		long dbLecture = prof.folder(lectures, "데이터베이스", "INTERVAL 29 DAY");
		prof.folder(null, "성적 처리", "INTERVAL 28 DAY");
		prof.file(dbLecture, "1주차 강의 개요.pdf", "application/pdf", TestPdfs.pdfWithText("Week 1 overview"),
				"INTERVAL 27 DAY");
		prof.file(dbLecture, "실습 안내.txt", "text/plain", text("실습은 MySQL 8.4 컨테이너로 한다.\n"), "INTERVAL 10 DAY");
		long draft = prof.folder(null, "지난 학기", "INTERVAL 200 DAY");
		prof.file(draft, "2026-1 기말고사 초안.txt", "text/plain", text("지난 학기 기말고사 초안.\n"), "INTERVAL 150 DAY");
		prof.trash(draft, "INTERVAL 3 DAY");
		prof.usage();
	}

	private static boolean empty(JdbcClient jdbc, long owner) {
		return !LocalSql.exists(jdbc, "SELECT 1 FROM filebox_items WHERE owner_user_id = :o", LocalSql.params("o", owner));
	}

	private long folder(Long parent, String name, String age) {
		this.items++;
		return LocalSql.insert(this.jdbc, """
				INSERT INTO filebox_items (owner_user_id, parent_item_id, item_kind, item_name, file_id, created_at)
				VALUES (:o, :parent, 'folder', :name, NULL, CURRENT_TIMESTAMP(3) - %s)
				""".formatted(age), LocalSql.params("o", this.owner, "parent", parent, "name", name));
	}

	/** 올리기와 같은 순서: 객체, blob(같은 내용이면 참조 수만 올린다), 파일, 업로드, 파일함 항목. */
	private long file(Long parent, String name, String mime, byte[] content, String age) {
		String sha = sha256(content);
		String key = "blobs/" + sha.substring(0, 2) + "/" + sha;
		Long blob = this.jdbc.sql("SELECT file_blob_id FROM file_blobs WHERE content_sha256 = :sha AND purged_at IS NULL")
			.param("sha", sha)
			.query(Long.class)
			.optional()
			.orElse(null);
		if (blob == null) {
			this.store.put(ObjectStore.Bucket.CONTENT, key, content, mime);
			blob = LocalSql.insert(this.jdbc, """
					INSERT INTO file_blobs (content_sha256, size_bytes, detected_mime_type, storage_key, blob_state, reference_count)
					VALUES (:sha, :size, :mime, :key, 'clean', 1)
					""", LocalSql.params("sha", sha, "size", content.length, "mime", mime, "key", key));
		}
		else {
			this.jdbc.sql("UPDATE file_blobs SET reference_count = reference_count + 1 WHERE file_blob_id = :b")
				.param("b", blob)
				.update();
		}
		long file = LocalSql.insert(this.jdbc, """
				INSERT INTO files (file_blob_id, original_file_name, file_extension, mime_type, file_size_bytes,
				                   uploader_user_id, course_id, source_menu_code, scan_state, preview_convertible, created_at)
				VALUES (:blob, :name, :ext, :mime, :size, :o, NULL, 'filebox', 'clean', :pdf, CURRENT_TIMESTAMP(3) - %s)
				""".formatted(age), LocalSql.params("blob", blob, "name", name, "ext",
				name.substring(name.lastIndexOf('.') + 1), "mime", mime, "size", content.length, "o", this.owner, "pdf",
				"application/pdf".equals(mime)));
		this.jdbc.sql("""
				INSERT INTO upload_sessions (upload_policy_id, owner_user_id, course_id, file_id, upload_state, expires_at,
				                             attached_at, created_at)
				SELECT upload_policy_id, :o, NULL, :f, 'attached', CURRENT_TIMESTAMP(3) - %1$s + INTERVAL 1 DAY,
				       CURRENT_TIMESTAMP(3) - %1$s, CURRENT_TIMESTAMP(3) - %1$s
				  FROM upload_policies WHERE policy_code = 'filebox' AND is_active = 1
				""".formatted(age)).param("o", this.owner).param("f", file).update();
		this.bytes += content.length;
		this.items++;
		return LocalSql.insert(this.jdbc, """
				INSERT INTO filebox_items (owner_user_id, parent_item_id, item_kind, item_name, file_id, created_at)
				VALUES (:o, :parent, 'file', :name, :file, CURRENT_TIMESTAMP(3) - %s)
				""".formatted(age), LocalSql.params("o", this.owner, "parent", parent, "name", name, "file", file));
	}

	/** 휴지통에 넣기와 같다: 항목과 그 아래 전체가 같은 trash_root_id를 가진다. 사용량은 그대로다. */
	private void trash(long root, String age) {
		this.jdbc.sql("""
				UPDATE filebox_items SET item_state = 'trashed', trashed_at = CURRENT_TIMESTAMP(3) - %s, trash_root_id = :root
				 WHERE owner_user_id = :o AND item_state = 'active' AND (filebox_item_id = :root OR parent_item_id = :root)
				""".formatted(age)).param("o", this.owner).param("root", root).update();
	}

	/** 사용량은 활성과 휴지통 항목의 합이다(FileboxRecords.addUsage). */
	private void usage() {
		this.jdbc.sql("""
				INSERT INTO filebox_quotas (owner_user_id, used_bytes, item_count) VALUES (:o, :bytes, :items)
				ON DUPLICATE KEY UPDATE used_bytes = used_bytes + :bytes, item_count = item_count + :items
				""").param("o", this.owner).param("bytes", this.bytes).param("items", this.items).update();
	}

	private static byte[] text(String value) {
		return value.getBytes(StandardCharsets.UTF_8);
	}

	private static String sha256(byte[] content) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
