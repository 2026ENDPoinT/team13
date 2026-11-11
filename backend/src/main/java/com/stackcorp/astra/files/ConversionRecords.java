package com.stackcorp.astra.files;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * conversion_jobs. rendition 하나가 행 하나다. converted_doc_key가 rendition key다.
 * 삭제한 파일의 rendition은 없는 것으로 본다. 변환 중에 파일이 지워지면 GC가 행을 expired로 바꾸고, 변환은 완료로 바꾸지 못한다.
 */
@Repository
class ConversionRecords {

	private final JdbcClient jdbc;

	ConversionRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<String> findReadyKey(long fileId, int versionNo) {
		return this.jdbc.sql("""
				SELECT c.converted_doc_key FROM conversion_jobs c JOIN files f ON f.file_id = c.file_id
				 WHERE c.file_id = :fileId AND c.file_version_no = :version AND c.conv_type = 'img'
				   AND c.conversion_state = 'ready' AND f.state = 'active'
				 ORDER BY c.conversion_job_id DESC LIMIT 1
				""")
			.param("fileId", fileId)
			.param("version", versionNo)
			.query(String.class)
			.optional();
	}

	/** 지우지 않은 파일의 완료된 rendition key인지. */
	boolean live(String key) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM conversion_jobs c JOIN files f ON f.file_id = c.file_id
				 WHERE c.converted_doc_key = :key AND c.conversion_state = 'ready' AND f.state = 'active'
				""").param("key", key).query(Long.class).single() > 0;
	}

	long start(long fileId, int versionNo, String key) {
		GeneratedKeyHolder id = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO conversion_jobs (file_id, file_version_no, conv_type, converted_doc_key, conversion_state, started_at)
				VALUES (:fileId, :version, 'img', :key, 'converting', CURRENT_TIMESTAMP(3))
				""")
			.param("fileId", fileId)
			.param("version", versionNo)
			.param("key", key)
			.update(id);
		return id.getKey().longValue();
	}

	/** @return 완료로 바꿨으면 true. 변환 중에 파일이 지워져 GC가 먼저 닫았으면 false */
	boolean ready(long id, int pageCount) {
		return this.jdbc.sql("""
				UPDATE conversion_jobs SET conversion_state = 'ready', page_count = :pages, completed_at = CURRENT_TIMESTAMP(3)
				 WHERE conversion_job_id = :id AND conversion_state = 'converting'
				""").param("pages", pageCount).param("id", id).update() == 1;
	}

	void failed(long id, String errorCode) {
		this.jdbc.sql("""
				UPDATE conversion_jobs SET conversion_state = 'failed', error_code = :code, completed_at = CURRENT_TIMESTAMP(3)
				 WHERE conversion_job_id = :id AND conversion_state = 'converting'
				""").param("code", errorCode).param("id", id).update();
	}

}
