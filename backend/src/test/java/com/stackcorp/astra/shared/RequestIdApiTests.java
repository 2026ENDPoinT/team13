package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 모든 응답은 서버가 만든 X-Request-Id를 갖고, problem JSON의 requestId는 그 header와 같다. 컨트롤러에 닿지 못하고 filter에서
 * 거부한 요청도 같다.
 */
class RequestIdApiTests extends IntegrationTest {

	private static final String UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Test
	void successfulResponsesCarryAServerGeneratedIdAndIgnoreTheClientsOwn() throws Exception {
		String session = this.data.session(this.data.user("STUDENT"));
		MockHttpServletResponse first = this.mvc
			.perform(get("/api/me").cookie(new Cookie("ASTRA_SESSION", session)).header(RequestIds.HEADER, "forged-id"))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
		MockHttpServletResponse second = new ApiClient(this.mvc, session).get("/api/me").andReturn().getResponse();

		assertThat(first.getHeader(RequestIds.HEADER)).matches(UUID);
		assertThat(second.getHeader(RequestIds.HEADER)).matches(UUID).isNotEqualTo(first.getHeader(RequestIds.HEADER));
	}

	@Test
	void handlerErrorsCarryTheSameIdInHeaderAndBody() throws Exception {
		long professor = this.data.user("PROFESSOR");
		long course = this.data.course();
		this.data.instructor(course, professor);
		ApiClient client = new ApiClient(this.mvc, this.data.session(professor));

		// ApiException
		sameIdInHeaderAndBody(client.get("/api/courses/" + this.data.course() + "/weeks").andExpect(status().isNotFound()));
		// Spring MVC가 만든 오류
		sameIdInHeaderAndBody(client.postJson("/api/courses/" + course + "/materials", "{not json")
			.andExpect(status().isBadRequest()));
		// 모듈 컨트롤러가 직접 만든 ProblemDetail
		sameIdInHeaderAndBody(client.post("/api/courses/" + course + "/summary-jobs/999999999/cancel")
			.andExpect(status().isNotFound()));
		sameIdInHeaderAndBody(new ApiClient(this.mvc, null)
			.postJson("/api/auth/break-glass", "{\"username\":\"nobody\",\"password\":\"x\",\"code\":\"000000\"}")
			.andExpect(status().isUnauthorized()));
	}

	@Test
	void requestsRejectedBySecurityFiltersCarryTheIdToo() throws Exception {
		String session = this.data.session(this.data.user("STUDENT"));
		// session 없음: 401
		sameIdInHeaderAndBody(this.mvc.perform(get("/api/me")).andExpect(status().isUnauthorized()));
		// CSRF token 없음: 403
		sameIdInHeaderAndBody(this.mvc
			.perform(post("/api/auth/logout").cookie(new Cookie("ASTRA_SESSION", session)))
			.andExpect(status().isForbidden()));
		// 다른 사이트에서 온 요청: 403(OriginCheckFilter)
		sameIdInHeaderAndBody(this.mvc
			.perform(post("/api/auth/logout").cookie(new Cookie("ASTRA_SESSION", session))
				.header("Origin", "https://evil.example")
				.contentType(MediaType.APPLICATION_JSON))
			.andExpect(status().isForbidden()));
	}

	private static void sameIdInHeaderAndBody(ResultActions result) throws Exception {
		MockHttpServletResponse response = result.andReturn().getResponse();
		String header = response.getHeader(RequestIds.HEADER);
		assertThat(header).matches(UUID);
		assertThat(ApiClient.json(response).get("requestId").asString()).isEqualTo(header);
	}

}
