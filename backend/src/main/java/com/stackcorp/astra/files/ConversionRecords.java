package com.stackcorp.astra.files;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * conversion_jobs. rendition 하나가 행 하나다. converted_doc_key가 rendition key다.
 */
@Repository
class ConversionRecords {

	private final JdbcClient jdbc;

	ConversionRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<String> findReadyKey(long fileId, int versionNo) {
		return this.jdbc.sql("""
				SELECT converted_doc_key FROM conversion_jobs
				 WHERE file_id = :fileId AND file_version_no = :version AND conv_type = 'img'
				   AND conversion_state = 'ready'
				 ORDER BY conversion_job_id DESC LIMIT 1
				""")
			.param("fileId", fileId)
			.param("version", versionNo)
			.query(String.class)
			.optional();
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

	void ready(long id, int pageCount) {
		this.jdbc.sql("""
				UPDATE conversion_jobs SET conversion_state = 'ready', page_count = :pages, completed_at = CURRENT_TIMESTAMP(3)
				 WHERE conversion_job_id = :id
				""").param("pages", pageCount).param("id", id).update();
	}

	void failed(long id, String errorCode) {
		this.jdbc.sql("""
				UPDATE conversion_jobs SET conversion_state = 'failed', error_code = :code, completed_at = CURRENT_TIMESTAMP(3)
				 WHERE conversion_job_id = :id
				""").param("code", errorCode).param("id", id).update();
	}

}
