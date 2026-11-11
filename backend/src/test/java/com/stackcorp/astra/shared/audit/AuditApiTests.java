package com.stackcorp.astra.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.files.api.VideoKeys;
import com.stackcorp.astra.shared.audit.AuditRows.Row;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.LlmConsents;
import com.stackcorp.astra.summary.job.SummaryRunnerDriver;

import tools.jackson.databind.JsonNode;

/**
 * 상태를 바꾸거나 민감한 연산은 감사 기록을 하나씩 남긴다(SC-09). 기록은 응답의 requestId로 찾을 수 있고, 요청 본문과 파일 이름은
 * 들어가지 않는다.
 */
class AuditApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	VideoKeys keys;

	@Autowired
	FakeLlm llm;

	@Autowired
	ApplicationContext context;

	long course;

	long professorId;

	long studentId;

	ApiClient professor;

	ApiClient student;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.professorId = this.data.user("PROFESSOR");
		this.studentId = this.data.user("STUDENT");
		this.data.instructor(this.course, this.professorId);
		LlmConsents.grant(this.context, this.course, this.professorId);
		this.data.enroll(this.course, this.studentId);
		this.professor = new ApiClient(this.mvc, this.data.session(this.professorId));
		this.student = new ApiClient(this.mvc, this.data.session(this.studentId));
	}

	@Test
	void recordsUploadsMaterialsAndDownloadsWithoutBodyOrFileNames() throws Exception {
		long professorSession = sessionOf(this.professorId);
		ResultActions upload = this.professor
			.upload(this.course, "lecture_material", "기밀-시험문제.pdf", TestPdfs.pdf(1), null)
			.andExpect(status().isCreated());
		long uploadId = ApiClient.json(upload).get("uploadId").asLong();
		assertThat(audit(upload)).isEqualTo(new Row(this.professorId, professorSession, "upload.create", "upload",
				uploadId, this.course, "ok"));

		ResultActions material = this.professor
			.postJson("/api/courses/" + this.course + "/materials",
					"{\"weekNo\":3,\"title\":\"비밀 제목\",\"description\":\"자유 텍스트\",\"uploadIds\":[" + uploadId + "]}")
			.andExpect(status().isCreated());
		JsonNode created = ApiClient.json(material);
		assertThat(audit(material)).isEqualTo(new Row(this.professorId, professorSession, "material.create",
				"learning_activity", created.get("activityId").asLong(), this.course, "ok"));

		long fileId = created.get("files").get(0).get("fileId").asLong();
		ResultActions download = this.student.get("/api/courses/" + this.course + "/files/" + fileId + "/content")
			.andExpect(status().isOk());
		assertThat(audit(download)).isEqualTo(
				new Row(this.studentId, sessionOf(this.studentId), "file.download", "file", fileId, this.course, "ok"));

		long unused = this.professor.uploaded(this.course, "unused.pdf", TestPdfs.pdf(1));
		ResultActions delete = this.professor.delete("/api/uploads/" + unused).andExpect(status().isNoContent());
		assertThat(audit(delete))
			.isEqualTo(new Row(this.professorId, professorSession, "upload.delete", "upload", unused, null, "ok"));

		// 거부한 명령은 감사 기록을 남기지 않고, 어떤 기록에도 본문이나 파일 이름이 없다.
		ResultActions refused = this.student.get("/api/courses/" + this.data.course() + "/files/" + fileId + "/content")
			.andExpect(status().isNotFound());
		assertThat(AuditRows.of(this.jdbc, refused)).isEmpty();
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM audit_events
				 WHERE CONCAT_WS('|', operation, object_type, result_code) REGEXP '[^a-z0-9_.|]'
				""").query(Long.class).single()).isZero();
	}

	@Test
	void recordsSummaryJobCommands() throws Exception {
		this.llm.reset(FakeLlm::honest);
		while (SummaryRunnerDriver.runOnce(this.context)) {
			// 앞 테스트가 남긴 작업을 비운다.
		}
		long fileId = this.data.pdfFile(TestPdfs.pdf(1), "clean");
		this.data.activity(this.course, "lecture_material", true, null, this.data.attachmentGroup(fileId));
		long session = sessionOf(this.professorId);
		String files = "/api/courses/" + this.course + "/files/" + fileId;
		String jobs = "/api/courses/" + this.course + "/summary-jobs/";

		ResultActions request = this.professor.post(files + "/summary-jobs").andExpect(status().isAccepted());
		long first = ApiClient.json(request).get("id").asLong();
		assertThat(audit(request)).isEqualTo(
				new Row(this.professorId, session, "summary_job.request", "summary_job", first, this.course, "ok"));
		ResultActions cancel = this.professor.post(jobs + first + "/cancel").andExpect(status().isOk());
		assertThat(audit(cancel)).isEqualTo(
				new Row(this.professorId, session, "summary_job.cancel", "summary_job", first, this.course, "ok"));
		ResultActions retry = this.professor.post(jobs + first + "/retry").andExpect(status().isAccepted());
		long second = ApiClient.json(retry).get("id").asLong();
		assertThat(audit(retry)).isEqualTo(
				new Row(this.professorId, session, "summary_job.retry", "summary_job", second, this.course, "ok"));
		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();
		ResultActions publish = this.professor.post(jobs + second + "/publish").andExpect(status().isOk());
		assertThat(audit(publish)).isEqualTo(
				new Row(this.professorId, session, "summary_job.publish", "summary_job", second, this.course, "ok"));
	}

	@Test
	void recordsPlaybackGrantsAndKeyFetches() throws Exception {
		long activity = this.data.activity(this.course, "lecture_weeks", true, null, null);
		String asset = "lecture-" + UUID.randomUUID().toString().substring(0, 8);
		this.keys.ensure(asset);
		long item = this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path)
				VALUES (:a, 1, '9주차 강의', 'video', :path)
				""", Map.of("a", activity, "path", "vod/" + asset + "/main.m3u8"));
		String base = "/api/courses/" + this.course + "/lecture-items/" + item;
		long session = sessionOf(this.studentId);

		ResultActions playback = this.student.post(base + "/playback").andExpect(status().isOk());
		assertThat(audit(playback)).isEqualTo(new Row(this.studentId, session, "lecture.playback_grant",
				"online_lecture_item", item, this.course, "ok"));
		ResultActions key = this.student.get(base + "/hls-key").andExpect(status().isOk());
		assertThat(audit(key)).isEqualTo(
				new Row(this.studentId, session, "lecture.hls_key", "online_lecture_item", item, this.course, "ok"));
	}

	@Test
	void aFailedRecordOutsideATransactionDoesNotFailTheRequest() throws Exception {
		long fileId = this.data.pdfFile(TestPdfs.pdf(1), "clean");
		this.data.activity(this.course, "lecture_material", true, null, this.data.attachmentGroup(fileId));

		this.jdbc.sql("RENAME TABLE audit_events TO audit_events_off").update();
		try {
			this.student.get("/api/courses/" + this.course + "/files/" + fileId + "/content").andExpect(status().isOk());
		}
		finally {
			this.jdbc.sql("RENAME TABLE audit_events_off TO audit_events").update();
		}
	}

	@Test
	void aFailedRecordInsideTheCommandTransactionRollsTheCommandBack() throws Exception {
		this.jdbc.sql("RENAME TABLE audit_events TO audit_events_off").update();
		try {
			assertThatThrownBy(() -> this.professor.postJson("/api/courses/" + this.course + "/materials",
					"{\"weekNo\":1,\"title\":\"자료\"}"))
				.hasRootCauseInstanceOf(java.sql.SQLSyntaxErrorException.class);
		}
		finally {
			this.jdbc.sql("RENAME TABLE audit_events_off TO audit_events").update();
		}
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_activities WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single()).isZero();
	}

	private long sessionOf(long userId) {
		return this.jdbc.sql("SELECT MAX(session_id) FROM user_sessions WHERE user_id = :u")
			.param("u", userId)
			.query(Long.class)
			.single();
	}

	private Row audit(ResultActions result) {
		return AuditRows.single(this.jdbc, result);
	}

}
