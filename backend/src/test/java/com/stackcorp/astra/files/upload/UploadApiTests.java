package com.stackcorp.astra.files.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;

/**
 * 업로드는 그 과목의 담당자만, 정책이 허용한 형식과 크기로만 받는다. 올린 사람만 자기 업로드를 본다(SC-08, SEC-15).
 */
class UploadApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	ApiClient professor;

	ApiClient student;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, stu);
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.student = new ApiClient(this.mvc, this.data.session(stu));
	}

	@Test
	void storesAnInspectedFileUnderASanitizedNameAndServerDetectedType() throws Exception {
		byte[] pdf = TestPdfs.pdf(1);
		JsonNode upload = ApiClient.json(this.professor
			.upload(this.course, "lecture_material", "..\\..\\9주차 <b>정규화<b>.pdf", pdf, null)
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.state").value("clean"))
			.andExpect(jsonPath("$.name").value("9주차 _b_정규화_b_.pdf"))
			.andExpect(jsonPath("$.sizeBytes").value(pdf.length)));

		var row = this.jdbc.sql("""
				SELECT f.mime_type, f.course_id, f.source_menu_code, f.scan_state, b.detected_mime_type, b.storage_key, b.blob_state
				  FROM upload_sessions u JOIN files f ON f.file_id = u.file_id JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE u.upload_id = :id
				""").param("id", upload.get("uploadId").asLong()).query().singleRow();
		// client가 보낸 application/octet-stream이 아니라 내용으로 판정한 형식을 저장한다.
		assertThat(row).containsEntry("mime_type", "application/pdf")
			.containsEntry("detected_mime_type", "application/pdf")
			.containsEntry("source_menu_code", "lecture_material")
			.containsEntry("scan_state", "clean")
			.containsEntry("blob_state", "clean");
		assertThat(((Number) row.get("course_id")).longValue()).isEqualTo(this.course);
		assertThat((String) row.get("storage_key")).matches("blobs/[0-9a-f]{2}/[0-9a-f]{64}");
	}

	@Test
	void rejectsContentThatDoesNotMatchItsExtensionAndExtensionsOutsideThePolicy() throws Exception {
		byte[] html = "<html><script>alert(document.cookie)</script></html>".getBytes(StandardCharsets.UTF_8);
		this.professor.upload(this.course, "lecture_material", "lecture.pdf", html, null)
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("content_mismatch"));
		this.professor.upload(this.course, "lecture_material", "page.html", html, null)
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("extension_not_allowed"));
		this.professor.upload(this.course, "lecture_material", "run.pdf.exe", TestPdfs.pdf(1), null)
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("extension_not_allowed"));
		this.professor.upload(this.course, "lecture_material", "empty.pdf", new byte[0], null)
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("empty_file"));
		this.professor.upload(this.course, "report", "week9.pdf", TestPdfs.pdf(1), null)
			.andExpect(status().isBadRequest());
	}

	@Test
	void rejectsFilesLargerThanThePolicy() throws Exception {
		this.jdbc.sql("UPDATE upload_policies SET max_file_bytes = 1000 WHERE policy_code = 'lecture_material'").update();
		try {
			this.professor.upload(this.course, "lecture_material", "big.pdf", TestPdfs.pdf(3), null)
				.andExpect(status().is(413))
				.andExpect(jsonPath("$.code").value("too_large"));
		}
		finally {
			this.jdbc.sql("UPDATE upload_policies SET max_file_bytes = 52428800 WHERE policy_code = 'lecture_material'")
				.update();
		}
	}

	@Test
	void lettingOnlyInstructorsOfThatCourseUpload() throws Exception {
		byte[] pdf = TestPdfs.pdf(1);
		this.student.upload(this.course, "lecture_material", "week9.pdf", pdf, null).andExpect(status().isNotFound());
		this.professor.upload(this.data.course(), "lecture_material", "week9.pdf", pdf, null)
			.andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).upload(this.course, "lecture_material", "week9.pdf", pdf, null)
			.andExpect(status().isUnauthorized());
		// CSRF token이 없으면 session이 있어도 받지 않는다.
		this.mvc
			.perform(multipart("/api/uploads").file(new MockMultipartFile("file", "week9.pdf", "application/pdf", pdf))
				.param("courseId", Long.toString(this.course))
				.param("purpose", "lecture_material")
				.cookie(new Cookie("ASTRA_SESSION", this.data.session(this.data.user("PROFESSOR")))))
			.andExpect(status().isForbidden());
	}

	@Test
	void showsAnUploadOnlyToItsOwnerAndDeletesItOnce() throws Exception {
		long uploadId = this.professor.uploaded(this.course, "week9.pdf", TestPdfs.pdf(1));
		long otherProf = this.data.user("PROFESSOR");
		this.data.instructor(this.course, otherProf);
		ApiClient colleague = new ApiClient(this.mvc, this.data.session(otherProf));

		this.professor.get("/api/uploads/" + uploadId).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("clean"));
		colleague.get("/api/uploads/" + uploadId).andExpect(status().isNotFound());
		colleague.delete("/api/uploads/" + uploadId).andExpect(status().isNotFound());

		this.professor.delete("/api/uploads/" + uploadId).andExpect(status().isNoContent());
		this.professor.delete("/api/uploads/" + uploadId).andExpect(status().isNoContent());
		this.professor.get("/api/uploads/" + uploadId).andExpect(jsonPath("$.state").value("deleted"));
		assertThat(this.jdbc.sql("""
				SELECT f.state FROM upload_sessions u JOIN files f ON f.file_id = u.file_id WHERE u.upload_id = :id
				""").param("id", uploadId).query(String.class).single()).isEqualTo("deleted");
	}

	@Test
	void replaysAnIdempotentUploadOnlyForTheSameRequest() throws Exception {
		byte[] pdf = TestPdfs.pdf(1);
		long first = ApiClient.json(this.professor.upload(this.course, "lecture_material", "week9.pdf", pdf, "key-1"))
			.get("uploadId")
			.asLong();
		long owner = this.jdbc.sql("SELECT owner_user_id FROM upload_sessions WHERE upload_id = :id")
			.param("id", first)
			.query(Long.class)
			.single();
		this.professor.upload(this.course, "lecture_material", "week9.pdf", pdf, "key-1")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.uploadId").value(first))
			.andExpect(jsonPath("$.name").value("week9.pdf"));
		// 같은 key에 다른 파일이나 다른 이름이면 처음 업로드를 돌려주지 않는다. 아무것도 저장하지 않는다(보안 검토 L1).
		this.professor.upload(this.course, "lecture_material", "week9.pdf", TestPdfs.pdf(2), "key-1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		this.professor.upload(this.course, "lecture_material", "other-name.pdf", pdf, "key-1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		long otherCourse = this.data.course();
		this.data.instructor(otherCourse, owner);
		this.professor.upload(otherCourse, "lecture_material", "week9.pdf", pdf, "key-1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM upload_sessions WHERE owner_user_id = :u")
			.param("u", owner)
			.query(Long.class)
			.single()).isEqualTo(1);
		this.professor.upload(this.course, "lecture_material", "week9.pdf", pdf, "bad key!")
			.andExpect(status().isBadRequest());
	}

	@Test
	void anUploadRecordedBeforeFingerprintsIsNeverReplayedForAnUnknownRequest() throws Exception {
		byte[] pdf = TestPdfs.pdf(1);
		long first = ApiClient.json(this.professor.upload(this.course, "lecture_material", "week9.pdf", pdf, "legacy-1"))
			.get("uploadId")
			.asLong();
		// V34 이전 업로드에는 지문이 없다. 같은 요청인지 알 수 없으므로 같은 파일이어도 409다.
		this.jdbc.sql("UPDATE upload_sessions SET request_fingerprint = NULL WHERE upload_id = :id").param("id", first).update();
		this.professor.upload(this.course, "lecture_material", "week9.pdf", pdf, "legacy-1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
	}

	@Test
	void limitsUploadsPerUser() throws Exception {
		byte[] pdf = TestPdfs.pdf(1);
		for (int i = 0; i < 6; i++) {
			this.professor.upload(this.course, "lecture_material", "week" + i + ".pdf", pdf, null)
				.andExpect(status().isCreated());
		}
		this.professor.upload(this.course, "lecture_material", "week7.pdf", pdf, null)
			.andExpect(status().isTooManyRequests())
			.andExpect(header().exists("Retry-After"))
			.andExpect(jsonPath("$.code").value("rate_limited"));
	}

}
