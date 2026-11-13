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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.summary.LlmConsents;
import com.stackcorp.astra.summary.api.LlmClient;
import com.stackcorp.astra.summary.job.SummaryRunnerDriver;

/**
 * 배포 스위치는 켜져 있지만 제공자(API key)가 설정되지 않은 배포(ADR-0032). 새 요청은 스위치를 끈 배포와 같이 503
 * {@code llm_disabled}이고 처리 조건은 {@code enabled=false}다. 이미 원장에 있는 작업은 worker가 {@code not_configured}로 닫는다.
 * 모델 bean이 없어야 하므로 Spring context를 따로 쓴다.
 */
@TestPropertySource(properties = "astra.test.fake-llm=false")
class LlmNotConfiguredTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Test
	void aDeploymentWithoutAProviderRefusesNewRequestsLikeADisabledOne() throws Exception {
		assertThat(this.context.getBeanProvider(LlmClient.class).getIfAvailable()).isNull();
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		long ta = this.data.user("TA");
		this.data.instructor(course, prof);
		this.jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role) VALUES (:c, :u, 'TA')")
			.param("c", course)
			.param("u", ta)
			.update();
		this.data.enroll(course, stu);
		LlmConsents.grant(this.context, course, prof);
		long file = this.data.pdfFile(TestPdfs.pdf(1), "clean");
		this.data.activity(course, "lecture_material", true, null, this.data.attachmentGroup(file));
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));
		ApiClient assistant = new ApiClient(this.mvc, this.data.session(ta));
		String files = "/api/courses/" + course + "/files/" + file;

		student.get("/api/llm/policy").andExpect(jsonPath("$.enabled").value(false));
		// 이미 원장에 들어온 작업이다. worker는 모델 bean이 없으니 not_configured로 실패시킨다.
		long queued = this.data.insert("""
				INSERT INTO summary_jobs (file_id, file_version_no, course_id, requested_by_user_id)
				VALUES (:f, 1, :c, :u)
				""", Map.of("f", file, "c", course, "u", prof));
		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();
		assertThat(this.jdbc.sql("SELECT CONCAT(job_state, ':', error_code) FROM summary_jobs WHERE summary_job_id = :id")
			.param("id", queued)
			.query(String.class)
			.single()).isEqualTo("failed:not_configured");

		professor.post(files + "/summary-jobs")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("llm_disabled"));
		professor.post("/api/courses/" + course + "/summary-jobs/" + queued + "/retry")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("llm_disabled"));
		// 역할 거절은 배포 상태보다 먼저다.
		assistant.post(files + "/summary-jobs")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("llm_decider_required"));
		student.post(files + "/study-chats")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("llm_disabled"));
		long chat = this.data.insert("INSERT INTO study_chats (owner_user_id, course_id, file_id) VALUES (:u, :c, :f)",
				Map.of("u", stu, "c", course, "f", file));
		student
			.send(MockMvcRequestBuilders.post("/api/courses/" + course + "/study-chats/" + chat + "/exchanges")
				.header("Idempotency-Key", "not-configured-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"question\":\"질문\"}"))
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("llm_disabled"));
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM summary_jobs WHERE file_id = :f")
			.param("f", file)
			.query(Long.class)
			.single()).isEqualTo(1);

		// 동의 목록과 사용량은 그대로 읽힌다. 화면은 policy의 enabled=false로 동의 버튼을 내지 않는다.
		professor.get("/api/courses/" + course + "/llm-consents")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$[0].granted").value(true));
		professor.get("/api/courses/" + course + "/llm-usage").andExpect(status().isOk());
	}

	// 화면 AI 도우미(ADR-0044): 제공자가 없으면 모델이 필요한 문장은 503 llm_disabled다. 규칙 답은 그대로다.
	@Test
	void theAssistantNeedsAProviderOnlyForModelAnswers() throws Exception {
		ApiClient student = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		student.postJson("/api/assistant", "{\"text\":\"할 일 목록 보여줘\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.source").value("rule"));
		student.postJson("/api/assistant", "{\"text\":\"아무거나 찾아줘\"}")
			.andExpect(status().isServiceUnavailable())
			.andExpect(jsonPath("$.code").value("llm_disabled"));
	}

}
