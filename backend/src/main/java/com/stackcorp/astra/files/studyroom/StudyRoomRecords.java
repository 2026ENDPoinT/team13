package com.stackcorp.astra.files.studyroom;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 학습실 행(ADR-0040). 한 사용자의 학습실을 바꾸는 명령은 먼저 {@link #lockQuota}로 그 사용자 행을 잠근다. 그래서 폴더, 녹음, 배치 행은
 * 잠그지 않고 읽은 뒤 version을 조건으로 바꾼다. 모든 조회는 소유자를 조건에 넣는다.
 */
@Repository
class StudyRoomRecords {

	private static final String FOLDER = """
			SELECT study_room_folder_id, course_id, folder_name, folder_position, folder_version, created_at
			  FROM study_room_folders
			""";

	private static final String RECORDING = """
			SELECT r.study_room_recording_id, r.course_id, r.file_id, r.recording_title, r.recording_source,
			       r.audio_mime_type, r.duration_ms, r.recorded_at, r.recording_version, f.original_file_name,
			       f.file_size_bytes, f.scan_state, b.blob_state, b.storage_key
			  FROM study_room_recordings r
			  JOIN files f ON f.file_id = r.file_id
			  JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
			""";

	private static final String DOCUMENT = """
			SELECT doc.study_room_document_id, doc.course_id, doc.file_id, doc.document_title, doc.document_version,
			       f.original_file_name, f.file_size_bytes, f.scan_state, b.blob_state, b.storage_key,
			       p.study_room_folder_id, doc.created_at, COALESCE(doc.updated_at, doc.created_at)
			  FROM study_room_documents doc
			  JOIN files f ON f.file_id = doc.file_id
			  JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
			  LEFT JOIN study_room_placements p
			    ON p.owner_user_id = doc.owner_user_id AND p.study_room_document_id = doc.study_room_document_id
			""";

	private static final String PLACEMENT = """
			SELECT p.study_room_placement_id, p.study_room_folder_id, p.file_id, p.study_room_recording_id,
			       p.study_room_document_id, p.placement_version, d.course_id
			  FROM study_room_placements p
			  JOIN study_room_folders d ON d.study_room_folder_id = p.study_room_folder_id
			""";

	private static final String QUOTA = "SELECT used_bytes, recording_count, document_count FROM study_room_quotas"
			+ " WHERE owner_user_id = :owner";

	private final JdbcClient jdbc;

	StudyRoomRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 사용자의 사용량 행을 만들고(없으면) 잠근다. 한 사용자의 학습실 변경은 여기서 차례를 기다린다. */
	Quota lockQuota(long owner) {
		this.jdbc.sql("""
				INSERT INTO study_room_quotas (owner_user_id) VALUES (:owner)
				ON DUPLICATE KEY UPDATE owner_user_id = owner_user_id
				""").param("owner", owner).update();
		return this.jdbc.sql(QUOTA + " FOR UPDATE")
			.param("owner", owner)
			.query((rs, n) -> new Quota(rs.getLong(1), rs.getInt(2), rs.getInt(3)))
			.single();
	}

	/** 잠그지 않고 읽는다. 행이 없으면 쓴 것이 없다. */
	Quota quota(long owner) {
		return this.jdbc.sql(QUOTA)
			.param("owner", owner)
			.query((rs, n) -> new Quota(rs.getLong(1), rs.getInt(2), rs.getInt(3)))
			.optional()
			.orElse(new Quota(0, 0, 0));
	}

	/**
	 * {@link #lockQuota}로 잠근 뒤 부른다. 바이트는 녹음과 문서를 함께 센다.
	 *
	 * @param recordings 더할 녹음 수
	 * @param documents 더할 문서 수
	 */
	void addUsage(long owner, long bytes, int recordings, int documents) {
		this.jdbc.sql("""
				UPDATE study_room_quotas
				   SET used_bytes = used_bytes + :bytes, recording_count = recording_count + :recordings,
				       document_count = document_count + :documents
				 WHERE owner_user_id = :owner
				""")
			.param("bytes", bytes)
			.param("recordings", recordings)
			.param("documents", documents)
			.param("owner", owner)
			.update();
	}

	/** {@link #lockQuota}로 잠근 뒤 부른다. */
	void removeUsage(long owner, long bytes, int recordings, int documents) {
		this.jdbc.sql("""
				UPDATE study_room_quotas
				   SET used_bytes = IF(used_bytes >= :bytes, used_bytes - :bytes, 0),
				       recording_count = IF(recording_count >= :recordings, recording_count - :recordings, 0),
				       document_count = IF(document_count >= :documents, document_count - :documents, 0)
				 WHERE owner_user_id = :owner
				""")
			.param("bytes", bytes)
			.param("recordings", recordings)
			.param("documents", documents)
			.param("owner", owner)
			.update();
	}

	// ---------- folders ----------

	Optional<Folder> folder(long owner, long id) {
		return this.jdbc.sql(FOLDER + " WHERE owner_user_id = :owner AND study_room_folder_id = :id")
			.param("owner", owner)
			.param("id", id)
			.query(StudyRoomRecords::folder)
			.optional();
	}

	/** 과목 하나의 내 폴더. 표시 순서, 그다음 ID 순이다. */
	List<Folder> folders(long owner, long courseId) {
		return this.jdbc
			.sql(FOLDER + " WHERE owner_user_id = :owner AND course_id = :course ORDER BY folder_position, study_room_folder_id")
			.param("owner", owner)
			.param("course", courseId)
			.query(StudyRoomRecords::folder)
			.list();
	}

	/** @param position 과목 안 표시 순서. 부르는 쪽이 사용자 행을 잠근 뒤 정한다 */
	long insertFolder(long owner, long courseId, String name, int position) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO study_room_folders (owner_user_id, course_id, folder_name, folder_position)
				VALUES (:owner, :course, :name, :position)
				""")
			.param("owner", owner)
			.param("course", courseId)
			.param("name", name)
			.param("position", position)
			.update(key, "study_room_folder_id");
		return key.getKey().longValue();
	}

	/** @return 바꾼 행 수. version이 다르면 0 */
	int renameFolder(long owner, long id, int version, String name) {
		return this.jdbc.sql("""
				UPDATE study_room_folders SET folder_name = :name, folder_version = folder_version + 1
				 WHERE owner_user_id = :owner AND study_room_folder_id = :id AND folder_version = :version
				""").param("name", name).param("owner", owner).param("id", id).param("version", version).update();
	}

	/** @return 바꾼 행 수. version이 다르면 0 */
	int positionFolder(long owner, long id, int version, int position) {
		return this.jdbc.sql("""
				UPDATE study_room_folders SET folder_position = :position, folder_version = folder_version + 1
				 WHERE owner_user_id = :owner AND study_room_folder_id = :id AND folder_version = :version
				""").param("position", position).param("owner", owner).param("id", id).param("version", version).update();
	}

	/** 폴더와 그 안의 배치를 지운다. 배치를 잃은 항목은 과목 학습실 맨 위로 간다. @return 지운 폴더 수. version이 다르면 0 */
	int deleteFolder(long owner, long id, int version) {
		this.jdbc.sql("DELETE FROM study_room_placements WHERE owner_user_id = :owner AND study_room_folder_id = :id")
			.param("owner", owner)
			.param("id", id)
			.update();
		return this.jdbc.sql("""
				DELETE FROM study_room_folders
				 WHERE owner_user_id = :owner AND study_room_folder_id = :id AND folder_version = :version
				""").param("owner", owner).param("id", id).param("version", version).update();
	}

	// ---------- recordings ----------

	Optional<Recording> recording(long owner, long id) {
		return this.jdbc.sql(RECORDING + " WHERE r.owner_user_id = :owner AND r.study_room_recording_id = :id")
			.param("owner", owner)
			.param("id", id)
			.query(StudyRoomRecords::recording)
			.optional();
	}

	/** @param courseId null이면 모든 과목. 녹음 시작 일시 역순, 그다음 ID 역순이다 */
	List<Recording> recordings(long owner, Long courseId) {
		return this.jdbc.sql(RECORDING + " WHERE r.owner_user_id = :owner"
				+ (courseId == null ? "" : " AND r.course_id = :course")
				+ " ORDER BY r.recorded_at DESC, r.study_room_recording_id DESC")
			.param("owner", owner)
			.param("course", courseId)
			.query(StudyRoomRecords::recording)
			.list();
	}

	long insertRecording(long owner, long courseId, long fileId, String title, String source, String mimeType,
			long durationMs, Instant recordedAt) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO study_room_recordings (owner_user_id, course_id, file_id, recording_title, recording_source,
				                                   audio_mime_type, duration_ms, recorded_at)
				VALUES (:owner, :course, :file, :title, :source, :mime, :duration, :recorded)
				""")
			.param("owner", owner)
			.param("course", courseId)
			.param("file", fileId)
			.param("title", title)
			.param("source", source)
			.param("mime", mimeType)
			.param("duration", durationMs)
			.param("recorded", Timestamp.from(recordedAt))
			.update(key, "study_room_recording_id");
		return key.getKey().longValue();
	}

	/** @return 바꾼 행 수. version이 다르면 0 */
	int renameRecording(long owner, long id, int version, String title) {
		return this.jdbc.sql("""
				UPDATE study_room_recordings SET recording_title = :title, recording_version = recording_version + 1
				 WHERE owner_user_id = :owner AND study_room_recording_id = :id AND recording_version = :version
				""").param("title", title).param("owner", owner).param("id", id).param("version", version).update();
	}

	/** 녹음과 그 배치를 지운다. @return 지운 녹음 수. version이 다르면 0 */
	int deleteRecording(long owner, long id, int version) {
		this.jdbc.sql("DELETE FROM study_room_placements WHERE owner_user_id = :owner AND study_room_recording_id = :id")
			.param("owner", owner)
			.param("id", id)
			.update();
		return this.jdbc.sql("""
				DELETE FROM study_room_recordings
				 WHERE owner_user_id = :owner AND study_room_recording_id = :id AND recording_version = :version
				""").param("owner", owner).param("id", id).param("version", version).update();
	}

	/** 업로드로 만든 녹음. 그 업로드가 녹음 업로드가 아니거나 녹음을 지웠으면 비어 있다. */
	Optional<Long> recordingOfUpload(long owner, long uploadId) {
		return this.jdbc.sql("""
				SELECT r.study_room_recording_id FROM upload_sessions u JOIN study_room_recordings r ON r.file_id = u.file_id
				 WHERE u.upload_id = :upload AND u.owner_user_id = :owner AND r.owner_user_id = :owner
				""").param("upload", uploadId).param("owner", owner).query(Long.class).optional();
	}

	// ---------- documents ----------

	Optional<Document> document(long owner, long id) {
		return this.jdbc.sql(DOCUMENT + " WHERE doc.owner_user_id = :owner AND doc.study_room_document_id = :id")
			.param("owner", owner)
			.param("id", id)
			.query(StudyRoomRecords::document)
			.optional();
	}

	/** 과목 하나의 내 문서. 올린 일시 역순, 그다음 ID 역순이다. */
	List<Document> documents(long owner, long courseId) {
		return this.jdbc.sql(DOCUMENT + """
				 WHERE doc.owner_user_id = :owner AND doc.course_id = :course
				 ORDER BY doc.created_at DESC, doc.study_room_document_id DESC
				""").param("owner", owner).param("course", courseId).query(StudyRoomRecords::document).list();
	}

	long insertDocument(long owner, long courseId, long fileId, String title) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO study_room_documents (owner_user_id, course_id, file_id, document_title)
				VALUES (:owner, :course, :file, :title)
				""")
			.param("owner", owner)
			.param("course", courseId)
			.param("file", fileId)
			.param("title", title)
			.update(key, "study_room_document_id");
		return key.getKey().longValue();
	}

	/** @return 바꾼 행 수. version이 다르면 0 */
	int renameDocument(long owner, long id, int version, String title) {
		return this.jdbc.sql("""
				UPDATE study_room_documents SET document_title = :title, document_version = document_version + 1
				 WHERE owner_user_id = :owner AND study_room_document_id = :id AND document_version = :version
				""").param("title", title).param("owner", owner).param("id", id).param("version", version).update();
	}

	/** 문서와 그 배치를 지운다. @return 지운 문서 수. version이 다르면 0 */
	int deleteDocument(long owner, long id, int version) {
		this.jdbc.sql("DELETE FROM study_room_placements WHERE owner_user_id = :owner AND study_room_document_id = :id")
			.param("owner", owner)
			.param("id", id)
			.update();
		return this.jdbc.sql("""
				DELETE FROM study_room_documents
				 WHERE owner_user_id = :owner AND study_room_document_id = :id AND document_version = :version
				""").param("owner", owner).param("id", id).param("version", version).update();
	}

	/** 업로드로 만든 문서. 그 업로드가 문서 업로드가 아니거나 문서를 지웠으면 비어 있다. */
	Optional<Long> documentOfUpload(long owner, long uploadId) {
		return this.jdbc.sql("""
				SELECT d.study_room_document_id FROM upload_sessions u JOIN study_room_documents d ON d.file_id = u.file_id
				 WHERE u.upload_id = :upload AND u.owner_user_id = :owner AND d.owner_user_id = :owner
				""").param("upload", uploadId).param("owner", owner).query(Long.class).optional();
	}

	/**
	 * 이 사용자가 이 멱등 key로 올린 업로드가 있는지. 업로드의 용도와 상태는 보지 않는다. 같은 요청인지는 업로드가 지문으로 판정한다.
	 */
	boolean uploadKeyUsed(long owner, String clientKey) {
		return this.jdbc.sql("SELECT COUNT(*) FROM upload_sessions WHERE owner_user_id = :owner AND client_upload_key = :key")
			.param("owner", owner)
			.param("key", clientKey)
			.query(Long.class)
			.single() > 0;
	}

	// ---------- placements ----------

	Optional<Placement> placementOfFile(long owner, long fileId) {
		return this.jdbc.sql(PLACEMENT + " WHERE p.owner_user_id = :owner AND p.file_id = :item")
			.param("owner", owner)
			.param("item", fileId)
			.query(StudyRoomRecords::placement)
			.optional();
	}

	Optional<Placement> placementOfRecording(long owner, long recordingId) {
		return this.jdbc.sql(PLACEMENT + " WHERE p.owner_user_id = :owner AND p.study_room_recording_id = :item")
			.param("owner", owner)
			.param("item", recordingId)
			.query(StudyRoomRecords::placement)
			.optional();
	}

	Optional<Placement> placementOfDocument(long owner, long documentId) {
		return this.jdbc.sql(PLACEMENT + " WHERE p.owner_user_id = :owner AND p.study_room_document_id = :item")
			.param("owner", owner)
			.param("item", documentId)
			.query(StudyRoomRecords::placement)
			.optional();
	}

	/** 과목 하나의 내 폴더에 둔 배치. 볼 수 있는지는 부르는 쪽이 거른다. */
	List<Placement> placements(long owner, long courseId) {
		return this.jdbc.sql(PLACEMENT + """
				 WHERE p.owner_user_id = :owner AND d.course_id = :course
				 ORDER BY p.study_room_placement_id
				""").param("owner", owner).param("course", courseId).query(StudyRoomRecords::placement).list();
	}

	/** 강의자료 파일 ID, 녹음 ID, 문서 ID 가운데 하나만 null이 아니다. */
	long insertPlacement(long owner, long folderId, Long fileId, Long recordingId, Long documentId) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO study_room_placements (owner_user_id, study_room_folder_id, file_id, study_room_recording_id,
				                                   study_room_document_id)
				VALUES (:owner, :folder, :file, :recording, :document)
				""")
			.param("owner", owner)
			.param("folder", folderId)
			.param("file", fileId)
			.param("recording", recordingId)
			.param("document", documentId)
			.update(key, "study_room_placement_id");
		return key.getKey().longValue();
	}

	/** @return 바꾼 행 수. version이 다르면 0 */
	int movePlacement(long owner, long id, int version, long folderId) {
		return this.jdbc.sql("""
				UPDATE study_room_placements SET study_room_folder_id = :folder, placement_version = placement_version + 1
				 WHERE owner_user_id = :owner AND study_room_placement_id = :id AND placement_version = :version
				""").param("folder", folderId).param("owner", owner).param("id", id).param("version", version).update();
	}

	/** @return 지운 행 수. version이 다르면 0 */
	int deletePlacement(long owner, long id, int version) {
		return this.jdbc.sql("""
				DELETE FROM study_room_placements
				 WHERE owner_user_id = :owner AND study_room_placement_id = :id AND placement_version = :version
				""").param("owner", owner).param("id", id).param("version", version).update();
	}

	private static Folder folder(ResultSet rs, int n) throws SQLException {
		return new Folder(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
				rs.getTimestamp(6).toInstant());
	}

	private static Recording recording(ResultSet rs, int n) throws SQLException {
		return new Recording(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
				rs.getString(6), rs.getLong(7), rs.getTimestamp(8).toInstant(), rs.getInt(9), rs.getString(10),
				rs.getLong(11), state(rs.getString(12), rs.getString(13)), rs.getString(14));
	}

	private static Document document(ResultSet rs, int n) throws SQLException {
		return new Document(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getInt(5),
				rs.getString(6), rs.getLong(7), state(rs.getString(8), rs.getString(9)), rs.getString(10),
				rs.getObject(11, Long.class), rs.getTimestamp(12).toInstant(), rs.getTimestamp(13).toInstant());
	}

	private static Placement placement(ResultSet rs, int n) throws SQLException {
		return new Placement(rs.getLong(1), rs.getLong(2), rs.getObject(3, Long.class), rs.getObject(4, Long.class),
				rs.getObject(5, Long.class), rs.getInt(6), rs.getLong(7));
	}

	// 검사 판정(ADR-0018)을 녹음·문서의 상태로 옮긴다. clean으로 가는 길은 검사기의 clean 판정 하나다.
	private static String state(String scanState, String blobState) {
		if ("clean".equals(scanState) && "clean".equals(blobState)) {
			return Recording.READY;
		}
		if ("infected".equals(scanState) || "rejected".equals(blobState)) {
			return Recording.REJECTED;
		}
		return Recording.SCANNING;
	}

	/** @param usedBytes 녹음과 문서 파일의 바이트 합 */
	record Quota(long usedBytes, int recordingCount, int documentCount) {
	}

	record Folder(long id, long courseId, String name, int position, int version, Instant createdAt) {
	}

	/**
	 * @param fileName 업로드할 때의 파일 이름(정리한 값)
	 * @param state {@link #READY}, {@link #SCANNING}, {@link #REJECTED}
	 */
	record Recording(long id, long courseId, long fileId, String title, String source, String mimeType, long durationMs,
			Instant recordedAt, int version, String fileName, long sizeBytes, String state, String storageKey) {

		static final String READY = "ready";

		static final String SCANNING = "scanning";

		static final String REJECTED = "rejected";

	}

	/**
	 * @param fileName 업로드할 때의 파일 이름(정리한 값)
	 * @param state {@link Recording#READY}, {@link Recording#SCANNING}, {@link Recording#REJECTED}
	 * @param folderId 둔 폴더. 과목 학습실 맨 위면 null
	 * @param updatedAt 마지막으로 바꾼 일시. 바꾼 적이 없으면 만든 일시
	 */
	record Document(long id, long courseId, long fileId, String title, int version, String fileName, long sizeBytes,
			String state, String storageKey, Long folderId, Instant createdAt, Instant updatedAt) {
	}

	/**
	 * @param fileId 강의자료 파일 배치면 그 파일 ID
	 * @param recordingId 녹음 배치면 그 녹음 ID
	 * @param documentId 문서 배치면 그 문서 ID
	 * @param courseId 둔 폴더의 과목
	 */
	record Placement(long id, long folderId, Long fileId, Long recordingId, Long documentId, int version,
			long courseId) {
	}

}
