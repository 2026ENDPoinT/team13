package com.stackcorp.astra.files;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 파일의 마지막 참조가 사라졌을 때의 삭제 표시. 파일은 삭제 상태가 되는 순간 모든 경로(FileAccess, 내려받기, 요약, rendition
 * 전달)에서 읽히지 않는다. 저장소 객체는 worker의 {@link StorageGc}가 나중에 지운다(SC-05 파일 삭제). files 모듈 안에서만 쓴다.
 * 잠금 순서는 업로드 기록, 검사 판정과 같은 file_blobs → files → upload_sessions다.
 */
@Repository
public class FileReferences {

	private final JdbcClient jdbc;

	FileReferences(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 파일의 blob 행, 그다음 파일 행을 잠근다. 그 파일의 업로드 행을 바꾸기 전에 부른다. file_blob_id는 바뀌지 않으므로 잠그지 않고 읽는다.
	 *
	 * @return blob ID. 파일이 없으면 비어 있는 값
	 */
	public Optional<Long> lock(long fileId) {
		Optional<Long> blobId = this.jdbc.sql("SELECT file_blob_id FROM files WHERE file_id = :file")
			.param("file", fileId)
			.query(Long.class)
			.optional();
		blobId.ifPresent((blob) -> {
			this.jdbc.sql("SELECT file_blob_id FROM file_blobs WHERE file_blob_id = :blob FOR UPDATE")
				.param("blob", blob)
				.query(Long.class)
				.list();
			this.jdbc.sql("SELECT file_id FROM files WHERE file_id = :file FOR UPDATE").param("file", fileId).query(Long.class).list();
		});
		return blobId;
	}

	/**
	 * 첨부에 남은 참조가 없으면 파일을 지운다. 잠근 뒤 센다. 부르는 쪽 transaction 안에서 부른다.
	 *
	 * @return 이번 호출로 지웠으면 true
	 */
	public boolean releaseIfUnattached(long fileId) {
		lock(fileId);
		long remaining = this.jdbc.sql("SELECT COUNT(*) FROM attachments WHERE file_id = :file")
			.param("file", fileId)
			.query(Long.class)
			.single();
		return remaining == 0 && release(fileId);
	}

	/**
	 * 파일을 지우고 원본 blob의 참조 수를 하나 줄인다. 상태 조건을 건 UPDATE라 여러 번 불러도 한 번만 줄어든다.
	 *
	 * @return 이번 호출로 지웠으면 true
	 */
	public boolean release(long fileId) {
		Optional<Long> blobId = lock(fileId);
		if (blobId.isEmpty()) {
			return false;
		}
		int deleted = this.jdbc.sql("""
				UPDATE files SET state = 'deleted', deleted_at = CURRENT_TIMESTAMP(3)
				 WHERE file_id = :file AND state = 'active'
				""").param("file", fileId).update();
		if (deleted == 0) {
			return false;
		}
		this.jdbc.sql("UPDATE file_blobs SET reference_count = reference_count - 1 WHERE file_blob_id = :blob AND reference_count > 0")
			.param("blob", blobId.get())
			.update();
		return true;
	}

}
