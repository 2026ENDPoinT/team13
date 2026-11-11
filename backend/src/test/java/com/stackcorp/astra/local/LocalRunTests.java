package com.stackcorp.astra.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestcontainersConfiguration;
import com.stackcorp.astra.identity.session.DevLoginConfiguration;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.job.SummaryRunnerDriver;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 로컬 실행 구성(TestStackBackendApplication과 같은 설정): 개발용 로그인, 표본 데이터, CDN 없이 앱이 응답하는 전달 경로(ADR-0009).
 */
@SpringBootTest(properties = { "astra.summary.poll-interval=1h", "astra.summary.lease-renewal=1h",
		"astra.summary.lease=2h", "astra.studychat.poll-interval=1h", "astra.studychat.sweep-interval=1h" })
@AutoConfigureMockMvc
@ActiveProfiles({ "local", "worker" })
@Import({ TestcontainersConfiguration.class, FakeLlm.Configuration.class, DevLoginConfiguration.class, LocalSeed.class })
class LocalRunTests {

	private static final String DEV_LOGIN = "/api/auth/dev-login";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Autowired
	FakeLlm llm;

	@Test
	void logsInASeededPersonaOnlyFromThisMachine() throws Exception {
		MockHttpServletResponse response = this.mvc.perform(devLogin(LocalSeed.STUDENT_REF))
			.andExpect(status().isNoContent())
			.andReturn()
			.getResponse();
		Cookie session = response.getCookie("ASTRA_SESSION");
		assertThat(session.isHttpOnly()).isTrue();
		this.mvc.perform(get("/api/me").cookie(session))
			.andExpect(jsonPath("$.name").value("박민준"))
			.andExpect(jsonPath("$.role").value("STUDENT"));
		this.mvc.perform(get("/api/me/courses").cookie(session))
			.andExpect(jsonPath("$.length()").value(7))
			.andExpect(jsonPath("$[?(@.key == 'bi')].role").value("STUDENT"))
			.andExpect(jsonPath("$[?(@.key == 'bi')].instructors[0]").value("박은주"));
		this.mvc.perform(get("/api/me/home").cookie(session))
			.andExpect(jsonPath("$.courses.length()").value(7))
			.andExpect(jsonPath("$.notices.length()").value(5))
			.andExpect(jsonPath("$.notices[0].pinned").value(true));

		// proxy 뒤에서 온 요청, 다른 컴퓨터의 요청은 로그인 경로가 없는 것처럼 답한다.
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("X-Forwarded-For", "203.0.113.9, 127.0.0.1"))
			.andExpect(status().isNotFound());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("CF-Connecting-IP", "203.0.113.9"))
			.andExpect(status().isNotFound());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).with((request) -> {
			request.setRemoteAddr("10.0.0.5");
			return request;
		})).andExpect(status().isNotFound());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("X-Forwarded-For", "127.0.0.1"))
			.andExpect(status().isNoContent());

		this.mvc.perform(devLogin("no-such-user")).andExpect(status().isUnauthorized());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("Origin", "https://evil.example"))
			.andExpect(status().isForbidden());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("Origin", "http://127.0.0.1:4190"))
			.andExpect(status().isNoContent());
		this.mvc
			.perform(post(DEV_LOGIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"externalRef\":\"" + LocalSeed.STUDENT_REF + "\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void servesPageImagesOnlyWithTheCookieIssuedForThatDocument() throws Exception {
		this.llm.reset(FakeLlm::honest);
		ApiClient professor = new ApiClient(this.mvc, login(LocalSeed.PROFESSOR_REF).getValue());
		Cookie studentSession = login(LocalSeed.STUDENT_REF);
		long course = this.jdbc.sql("SELECT course_id FROM courses WHERE legacy_kjkey = 'bi'").query(Long.class).single();
		String base = publishedSummary(professor, studentSession, course, 2);
		Cookie delivery = this.lastDeliveryCookie;
		assertThat(delivery.getName()).isEqualTo("ASTRA-Delivery");
		assertThat(delivery.getPath()).isEqualTo(base);
		assertThat(delivery.isHttpOnly()).isTrue();

		this.mvc.perform(get(base + "001.jpg").cookie(studentSession, delivery))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Cache-Control", "max-age=300, private"));
		this.mvc.perform(get(base + "003.jpg").cookie(studentSession, delivery)).andExpect(status().isNotFound());
		this.mvc.perform(get(base + "document.json").cookie(studentSession, delivery)).andExpect(status().isNotFound());

		// cookie가 없거나, 값이 바뀌었거나, 다른 문서의 cookie면 거부한다.
		this.mvc.perform(get(base + "001.jpg").cookie(studentSession)).andExpect(status().isForbidden());
		this.mvc.perform(get(base + "001.jpg").cookie(studentSession, new Cookie("ASTRA-Delivery", "9999999999.AAAA")))
			.andExpect(status().isForbidden());
		String expired = "1." + delivery.getValue().substring(delivery.getValue().indexOf('.') + 1);
		this.mvc.perform(get(base + "001.jpg").cookie(studentSession, new Cookie("ASTRA-Delivery", expired)))
			.andExpect(status().isForbidden());
		String otherBase = publishedSummary(professor, studentSession, course, 1);
		assertThat(otherBase).isNotEqualTo(base);
		this.mvc.perform(get(otherBase + "001.jpg").cookie(studentSession, delivery)).andExpect(status().isForbidden());
		// 서명한 cookie가 있어도 로그인하지 않은 요청은 받지 않는다.
		this.mvc.perform(get(base + "001.jpg").cookie(delivery)).andExpect(status().isUnauthorized());
	}

	private Cookie lastDeliveryCookie;

	// 교수자가 화면에서 보는 처리 조건을 읽고 그대로 동의한다(ADR-0032).
	private void consentToPageSummary(ApiClient professor, long course) throws Exception {
		JsonNode policy = ApiClient.json(professor.get("/api/llm/policy").andExpect(status().isOk()));
		professor
			.send(MockMvcRequestBuilders.put("/api/courses/" + course + "/llm-consents/page_summary")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"noticeVersion\":\"" + policy.get("noticeVersion").asString() + "\",\"profileDigest\":\""
						+ policy.get("profileDigest").asString() + "\",\"acknowledged\":true}"))
			.andExpect(status().isOk());
	}

	/** 자료를 올리고 요약을 게시한 뒤, 학생이 받은 pageImageBase를 돌려준다. */
	private String publishedSummary(ApiClient professor, Cookie studentSession, long course, int pages) throws Exception {
		while (SummaryRunnerDriver.runOnce(this.context)) {
			// 남은 작업을 비운다.
		}
		long uploadId = professor.uploaded(course, "week" + pages + ".pdf", TestPdfs.pdf(pages));
		JsonNode material = ApiClient.json(professor
			.postJson("/api/courses/" + course + "/materials",
					"{\"weekNo\":9,\"title\":\"9주차\",\"uploadIds\":[" + uploadId + "]}")
			.andExpect(status().isCreated()));
		long fileId = material.get("files").get(0).get("fileId").asLong();
		consentToPageSummary(professor, course);
		long jobId = ApiClient
			.json(professor.post("/api/courses/" + course + "/files/" + fileId + "/summary-jobs")
				.andExpect(status().isAccepted()))
			.get("id")
			.asLong();
		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();
		professor.post("/api/courses/" + course + "/summary-jobs/" + jobId + "/publish").andExpect(status().isOk());
		MockHttpServletResponse response = this.mvc
			.perform(get("/api/courses/" + course + "/files/" + fileId + "/summary").cookie(studentSession))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
		this.lastDeliveryCookie = response.getCookie("ASTRA-Delivery");
		return ApiClient.json(response).get("pageImageBase").asString();
	}

	private Cookie login(String externalRef) throws Exception {
		return this.mvc.perform(devLogin(externalRef)).andReturn().getResponse().getCookie("ASTRA_SESSION");
	}

	private MockHttpServletRequestBuilder devLogin(String externalRef) throws Exception {
		Cookie csrf = this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return post(DEV_LOGIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"externalRef\":\"" + externalRef + "\"}")
			.cookie(csrf)
			.header("X-XSRF-TOKEN", csrf.getValue());
	}

}
