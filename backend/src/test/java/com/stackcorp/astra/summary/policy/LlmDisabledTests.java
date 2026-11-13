package com.stackcorp.astra.summary.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.LlmConsents;
import com.stackcorp.astra.summary.chat.StudyChatRunnerDriver;
import com.stackcorp.astra.summary.job.SummaryRunnerDriver;

/**
 * 배포 스위치 {@code astra.llm.enabled=false}(ADR-0032). 동의가 있어도 요약 생성과 학습 대화는 503 {@code llm_disabled}이고,
 * 이미 원장에 있는 작업과 질문도 worker가 모델을 부르지 않고 닫는다. 배포 설정이 다르므로 Spring context를 따로 쓴다.
 */
@TestPropertySource(properties = "astra.llm.enabled=false")
class LlmDisabledTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Autowired
	FakeLlm llm;

	@Test
	void noExternalModelIsCalledWhenTheDeploymentDisablesIt() throws Exception {
		this.llm.reset(FakeLlm::honest);
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(course, prof);
		this.data.enroll(course, stu);
		LlmConsents.grant(this.context, course, prof);
		long file = this.data.pdfFile(TestPdfs.pdf(1), "clean");
		this.data.activity(course, "lecture_material", true, null, this.data.attachmentGroup(file));
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));
		String files = "/api/courses/" + course + "/files/" + file;

		student.get("/api/llm/policy").andExpect(jsonPath("$.enabled").value(false));
		professor.post(files + "/summary-jobs")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("llm_disabled"));
		student.post(files + "/study-chats")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("llm_disabled"));

		// 스위치를 끄기 전에 원장에 들어온 작업과 질문이다.
		long job = this.data.insert("""
				INSERT INTO summary_jobs (file_id, file_version_no, course_id, requested_by_user_id)
				VALUES (:f, 1, :c, :u)
				""", Map.of("f", file, "c", course, "u", prof));
		long chat = this.data.insert("INSERT INTO study_chats (owner_user_id, course_id, file_id) VALUES (:u, :c, :f)",
				Map.of("u", stu, "c", course, "f", file));
		long exchange = this.data.insert("INSERT INTO study_chat_exchanges (study_chat_id) VALUES (:chat)",
				Map.of("chat", chat));
		student
			.send(MockMvcRequestBuilders.post("/api/courses/" + course + "/study-chats/" + chat + "/exchanges")
				.header("Idempotency-Key", "disabled-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"질문\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("llm_disabled"));

		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();
		assertThat(StudyChatRunnerDriver.runOnce(this.context)).isTrue();

		assertThat(this.llm.log).isEmpty();
		assertThat(this.jdbc.sql("SELECT CONCAT(job_state, ':', error_code) FROM summary_jobs WHERE summary_job_id = :id")
			.param("id", job)
			.query(String.class)
			.single()).isEqualTo("cancelled:disabled");
		assertThat(this.jdbc.sql("""
				SELECT CONCAT(exchange_state, ':', error_code) FROM study_chat_exchanges WHERE study_chat_exchange_id = :id
				""").param("id", exchange).query(String.class).single()).isEqualTo("cancelled:disabled");
	}

	@Test
	void aStudentsStudyRoomDocumentIsNotSummarizedWhenTheDeploymentDisablesIt() throws Exception {
		this.llm.reset(FakeLlm::honest);
		long course = this.data.course();
		long stu = this.data.user("STUDENT");
		this.data.enroll(course, stu);
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));
		long document = ApiClient.json(student
			.send(MockMvcRequestBuilders.multipart("/api/me/study-room/courses/" + course + "/documents")
				.file(new MockMultipartFile("file", "notes.pdf", "application/pdf", TestPdfs.pdf(1))))
			.andExpect(status().isCreated())).get("documentId").asLong();

		student.post("/api/me/study-room/documents/" + document + "/summary")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("llm_disabled"));
		student.get("/api/me/study-room/documents/" + document + "/summary").andExpect(status().isNotFound());

		// 스위치를 끄기 전에 원장에 들어온 학습실 문서 작업도 모델을 부르지 않고 닫는다.
		long file = this.jdbc.sql("SELECT file_id FROM study_room_documents WHERE study_room_document_id = :id")
			.param("id", document)
			.query(Long.class)
			.single();
		long job = this.data.insert("""
				INSERT INTO summary_jobs (file_id, file_version_no, job_origin, requested_by_user_id)
				VALUES (:f, 1, 'study_room', :u)
				""", Map.of("f", file, "u", stu));
		while (SummaryRunnerDriver.runOnce(this.context)) {
			// 대기 작업을 모두 처리한다.
		}
		assertThat(this.llm.log).isEmpty();
		assertThat(this.jdbc.sql("SELECT CONCAT(job_state, ':', error_code) FROM summary_jobs WHERE summary_job_id = :id")
			.param("id", job)
			.query(String.class)
			.single()).isEqualTo("cancelled:disabled");
		student.get("/api/me/study-room/documents/" + document + "/summary")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("failed"));
	}

}
