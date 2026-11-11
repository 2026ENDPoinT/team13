package com.stackcorp.astra.files;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.stackcorp.astra.files.api.PageConverter.SourceFormat;

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
				       f.scan_state, b.blob_state, b.detected_mime_type, b.content_sha256, b.storage_key, b.size_bytes
				  FROM files f JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE f.file_id = :fileId AND f.state = 'active'
				""")
			.param("fileId", fileId)
			.query((rs, n) -> new FileRow(rs.getLong("file_id"), rs.getInt("file_version_no"),
					rs.getString("original_file_name"), rs.getObject("course_id", Long.class),
					rs.getString("file_extension"), rs.getString("mime_type"), rs.getString("scan_state"),
					rs.getString("blob_state"), rs.getString("detected_mime_type"), rs.getString("content_sha256"),
					rs.getString("storage_key"), rs.getLong("size_bytes")))
			.optional();
	}

	record FileRow(long fileId, int versionNo, String name, Long courseId, String extension, String mimeType,
			String scanState, String blobState, String detectedMimeType, String sha256, String storageKey,
			long sizeBytes) {

		boolean ready() {
			return "clean".equals(this.scanState) && "clean".equals(this.blobState);
		}

		// converter가 내용을 다시 확인한다.
		boolean pdf() {
			return FileTypes.pdf(this.detectedMimeType, this.mimeType, this.extension);
		}

		Optional<SourceFormat> previewFormat() {
			return FileTypes.previewFormat(this.detectedMimeType, this.mimeType, this.extension);
		}

		Optional<String> inlineImageType() {
			return FileTypes.inlineImage(this.detectedMimeType);
		}

	}

}
