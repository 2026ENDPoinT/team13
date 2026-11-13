package com.stackcorp.astra.files.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

/**
 * 검사기를 붙이지 않은 배포. 업로드는 형식 검사만 거쳐 clean이 된다(ADR-0010 6번). 이미 quarantined인 내용은 검사기 없이 clean으로
 * 넘기지 않는다(ADR-0018).
 */
class UnscannedUploadTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ScanWorker worker;

	@Test
	void storesFormatCheckedUploadsAsCleanRightAway() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));

		long uploadId = ApiClient.json(professor
			.upload(course, "lecture_material", "notes.txt", ("notes " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8), null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.state").value("clean"))).get("uploadId").asLong();

		assertThat(this.jdbc.sql("""
				SELECT b.blob_state, f.scan_state FROM upload_sessions u JOIN files f ON f.file_id = u.file_id
				  JOIN file_blobs b ON b.file_blob_id = f.file_blob_id WHERE u.upload_id = :id
				""").param("id", uploadId).query().singleRow()).containsEntry("blob_state", "clean")
			.containsEntry("scan_state", "clean");
	}

	@Test
	void leavesQuarantinedContentAloneWithoutAScanner() {
		long fileId = this.data.file("old.txt", "text/plain", "left from a scanned deployment".getBytes(StandardCharsets.UTF_8),
				"quarantined");
		this.jdbc.sql("UPDATE files SET scan_state = 'pending' WHERE file_id = :id").param("id", fileId).update();

		assertThat(this.worker.runOnce()).isZero();

		assertThat(this.jdbc.sql("""
				SELECT b.blob_state FROM files f JOIN file_blobs b ON b.file_blob_id = f.file_blob_id WHERE f.file_id = :id
				""").param("id", fileId).query(String.class).single()).isEqualTo("quarantined");
	}

}
