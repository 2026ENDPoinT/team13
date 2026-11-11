package com.stackcorp.astra.files;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 파일의 마지막 참조가 사라졌을 때의 삭제 표시. 파일은 삭제 상태가 되는 순간 모든 경로(FileAccess, 내려받기, 요약, rendition
 * 전달)에서 읽히지 않는다. 저장소 객체는 worker의 {@link StorageGc}가 나중에 지운다(SC-05 파일 삭제). files 모듈 안에서만 쓴다.
 */
@Repository
public class FileReferences {

	private final JdbcClient jdbc;

	FileReferences(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 첨부에 남은 참조가 없으면 파일을 지운다. 파일 행을 잠근 뒤 센다. 부르는 쪽 transaction 안에서 부른다.
	 *
	 * @return 이번 호출로 지웠으면 true
	 */
	public boolean releaseIfUnattached(long fileId) {
		this.jdbc.sql("SELECT file_id FROM files WHERE file_id = :file FOR UPDATE").param("file", fileId).query(Long.class).list();
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
		int deleted = this.jdbc.sql("""
				UPDATE files SET state = 'deleted', deleted_at = CURRENT_TIMESTAMP(3)
				 WHERE file_id = :file AND state = 'active'
				""").param("file", fileId).update();
		if (deleted == 0) {
			return false;
		}
		this.jdbc.sql("""
				UPDATE file_blobs b JOIN files f ON f.file_blob_id = b.file_blob_id
				   SET b.reference_count = b.reference_count - 1
				 WHERE f.file_id = :file AND b.reference_count > 0
				""").param("file", fileId).update();
		return true;
	}

}
