package com.stackcorp.astra.files;

import java.util.Optional;

import com.stackcorp.astra.files.scan.ScanMode;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * files, file_blobs 조회.
 */
@Repository
class FileRecords {

	private final JdbcClient jdbc;

	FileRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<FileRow> find(long fileId) {
		return this.jdbc.sql("""
				SELECT f.file_id, f.file_version_no, f.original_file_name, f.course_id, f.file_extension, f.mime_type,
				       f.scan_state, b.blob_state, b.detected_mime_type, b.content_sha256, b.storage_key, b.size_bytes,
				       f.source_menu_code
				  FROM files f JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE f.file_id = :fileId AND f.state = 'active'
				""")
			.param("fileId", fileId)
			.query((rs, n) -> new FileRow(rs.getLong("file_id"), rs.getInt("file_version_no"),
					rs.getString("original_file_name"), rs.getObject("course_id", Long.class),
					rs.getString("file_extension"), rs.getString("mime_type"), rs.getString("scan_state"),
					rs.getString("blob_state"), rs.getString("detected_mime_type"), rs.getString("content_sha256"),
					rs.getString("storage_key"), rs.getLong("size_bytes"), rs.getString("source_menu_code")))
			.optional();
	}

	/**
	 * @param purpose 업로드 용도(files.source_menu_code)
	 */
	record FileRow(long fileId, int versionNo, String name, Long courseId, String extension, String mimeType,
			String scanState, String blobState, String detectedMimeType, String sha256, String storageKey,
			long sizeBytes, String purpose) {

		/** 검사를 통과해 열람, 내려받기, rendition에 쓸 수 있는지. 검사를 면제한 영상 원본은 해당하지 않는다. */
		boolean ready() {
			return "clean".equals(this.scanState) && "clean".equals(this.blobState);
		}

		/**
		 * 영상 변환에 쓸 수 있는지. 검사한 내용이거나, 검사를 면제한 lecture_video 원본(blob_state unscanned)이다(ADR-0016 12번).
		 * 면제한 원본은 격리한 transcoder만 읽고 누구에게도 그대로 내보내지 않는다.
		 */
		boolean transcodable() {
			return "clean".equals(this.scanState) && ("clean".equals(this.blobState)
					|| (ScanMode.UNSCANNED.equals(this.blobState) && ScanMode.EXEMPT_PURPOSE.equals(this.purpose)));
		}

		// converter가 내용을 다시 확인한다.
		boolean pdf() {
			return FileTypes.pdf(this.detectedMimeType, this.mimeType, this.extension);
		}

	}

}
