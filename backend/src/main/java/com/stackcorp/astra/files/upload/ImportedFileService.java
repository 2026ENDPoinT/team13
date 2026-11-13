package com.stackcorp.astra.files.upload;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.stackcorp.astra.files.api.ImportedFiles;
import com.stackcorp.astra.files.upload.UploadRecords.Policy;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 학교 e-Class에서 받은 파일을 {@code school_file} 용도의 과목 파일로 저장한다. 저장은 업로드와 같은 검사·중복 제거를 따르고
 * ({@link UploadService#store}), 같은 transaction에서 새 첨부 그룹에 붙여 업로드를 attached로 둔다.
 */
@Service
class ImportedFileService implements ImportedFiles {

	static final String PURPOSE = UploadPurpose.SCHOOL_FILE.code();

	private final UploadService uploads;

	private final UploadRecords records;

	private final JdbcClient jdbc;

	ImportedFileService(UploadService uploads, UploadRecords records, JdbcClient jdbc) {
		this.uploads = uploads;
		this.records = records;
		this.jdbc = jdbc;
	}

	@Override
	public long maxFileBytes() {
		Long max = policy().maxFileBytes();
		return max == null ? Long.MAX_VALUE : max;
	}

	@Override
	public Imported store(long ownerUserId, long courseId, String name, Path content) {
		long[] placed = new long[2];
		UploadRecords.Upload upload;
		try {
			upload = this.uploads.store(ownerUserId, courseId, PURPOSE, policy(), new PathFile(name, content), null, null,
					new UploadService.Placement() {

						@Override
						public void after(long fileId, long uploadId, String storedName) {
							GeneratedKeyHolder key = new GeneratedKeyHolder();
							ImportedFileService.this.jdbc
								.sql("INSERT INTO attachment_groups (created_by_user_id) VALUES (:user)")
								.param("user", ownerUserId)
								.update(key, "attachment_group_id");
							long group = key.getKey().longValue();
							ImportedFileService.this.jdbc.sql("""
									INSERT INTO attachments (attachment_group_id, file_id, upload_id, sort_order)
									VALUES (:group, :file, :upload, 1)
									""").param("group", group).param("file", fileId).param("upload", uploadId).update();
							ImportedFileService.this.jdbc.sql("""
									UPDATE upload_sessions SET upload_state = 'attached', attached_at = CURRENT_TIMESTAMP(3)
									 WHERE upload_id = :id
									""").param("id", uploadId).update();
							placed[0] = group;
							placed[1] = fileId;
						}

					});
		}
		catch (ApiException ex) {
			throw new ImportRejectedException(ex.code());
		}
		boolean ready = this.jdbc.sql("""
				SELECT f.scan_state = 'clean' AND b.blob_state = 'clean'
				  FROM files f JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE f.file_id = :file
				""").param("file", placed[1]).query(Boolean.class).single();
		if (upload == null || placed[0] == 0) {
			throw new IllegalStateException("받은 파일을 기록하지 못했습니다.");
		}
		return new Imported(placed[0], placed[1], ready);
	}

	private Policy policy() {
		return this.records.policy(PURPOSE).orElseThrow(() -> new IllegalStateException("upload policy가 없습니다: " + PURPOSE));
	}

	/** 받은 파일을 업로드 검사에 넘기는 {@link MultipartFile}. */
	private record PathFile(String name, Path path) implements MultipartFile {

		@Override
		public String getName() {
			return "file";
		}

		@Override
		public String getOriginalFilename() {
			return this.name;
		}

		@Override
		public String getContentType() {
			return null;
		}

		@Override
		public boolean isEmpty() {
			return getSize() == 0;
		}

		@Override
		public long getSize() {
			try {
				return Files.size(this.path);
			}
			catch (IOException ex) {
				throw new IllegalStateException("받은 파일을 읽지 못했습니다.", ex);
			}
		}

		@Override
		public byte[] getBytes() throws IOException {
			return Files.readAllBytes(this.path);
		}

		@Override
		public InputStream getInputStream() throws IOException {
			return Files.newInputStream(this.path);
		}

		@Override
		public void transferTo(java.io.File dest) throws IOException {
			Files.copy(this.path, dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}

	}

}
