package com.stackcorp.astra.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.stackcorp.astra.OidcIntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

/**
 * CSRF token(XSRF-TOKEN 쿠키)의 수명(ADR-0004 4번). 브라우저 session 동안 바뀌지 않고 로그인과 로그아웃 응답만 새 값을 준다. 응답마다
 * 바뀌면 프론트엔드가 403마다 같은 명령을 한 번 더 보내고, 동시에 보낸 요청이 낡은 token을 실어 거부된다.
 */
class CsrfTokenLifetimeTests extends OidcIntegrationTest {

	private static final String CONTACT = "{\"phone\":\"010-2222-3333\",\"email\":\"new@school.example\"}";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	String ref;

	@BeforeEach
	void user() {
		this.ref = "2026" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
		this.data.userWithRef(this.ref, "STUDENT");
	}

	@Test
	void consecutiveRequestsKeepTheToken() throws Exception {
		MockHttpServletResponse login = devLogin(csrf());
		Cookie session = login.getCookie("ASTRA_SESSION");
		Cookie csrf = renewed(login);

		for (String path : List.of("/api/me", "/api/auth/methods", "/api/me/profile", "/api/me", "/api/auth/csrf")) {
			MockHttpServletResponse response = this.mvc.perform(get(path).cookie(session, csrf)).andReturn().getResponse();
			assertThat(response.getStatus()).as(path).isLessThan(300);
			assertThat(csrfCookies(response)).as(path).isEmpty();
		}
		// 로그인하지 않은 요청도 같다. 쿠키가 없을 때만 새로 만든다.
		assertThat(csrfCookies(this.mvc.perform(get("/api/auth/methods").cookie(csrf)).andReturn().getResponse()))
			.isEmpty();
		assertThat(csrfCookies(this.mvc.perform(get("/api/me").cookie(session)).andReturn().getResponse()))
			.hasSize(1);
	}

	@Test
	void stepUpRefusalLeavesTheTokenAlone() throws Exception {
		MockHttpServletResponse login = devLogin(csrf());
		Cookie session = login.getCookie("ASTRA_SESSION");
		Cookie csrf = renewed(login);

		MockHttpServletResponse refused = this.mvc
			.perform(mutating(put("/api/me/contact"), session, csrf).contentType(MediaType.APPLICATION_JSON)
				.content(CONTACT))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("step_up_required"))
			.andReturn()
			.getResponse();

		assertThat(csrfCookies(refused)).isEmpty();
		// 프론트엔드가 403 뒤에 다시 받는 token도 같은 값이라 같은 명령을 다시 보내지 않는다.
		assertThat(csrfCookies(this.mvc.perform(get("/api/auth/csrf").cookie(session, csrf)).andReturn().getResponse()))
			.isEmpty();
	}

	@Test
	void loginAndLogoutIssueANewToken() throws Exception {
		Cookie before = csrf();
		MockHttpServletResponse login = devLogin(before);
		Cookie session = login.getCookie("ASTRA_SESSION");
		Cookie afterLogin = renewed(login);
		assertThat(afterLogin.getValue()).isNotBlank().isNotEqualTo(before.getValue());
		assertThat(csrfCookies(login)).singleElement()
			.satisfies((c) -> assertThat(c).startsWith("XSRF-TOKEN=")
				.contains("Path=/")
				.doesNotContain("HttpOnly", "Max-Age", "Expires"));

		MockHttpServletResponse logout = this.mvc.perform(mutating(post("/api/auth/logout"), session, afterLogin))
			.andExpect(status().isNoContent())
			.andReturn()
			.getResponse();
		assertThat(logout.getCookie("ASTRA_SESSION").getMaxAge()).isZero();
		Cookie afterLogout = renewed(logout);
		assertThat(afterLogout.getValue()).isNotBlank().isNotEqualTo(afterLogin.getValue());
		this.mvc.perform(get("/api/me").cookie(session, afterLogout)).andExpect(status().isUnauthorized());
	}

	@Test
	void refusesAMissingOrWrongToken() throws Exception {
		MockHttpServletResponse login = devLogin(csrf());
		Cookie session = login.getCookie("ASTRA_SESSION");
		Cookie csrf = renewed(login);

		this.mvc.perform(post("/api/auth/logout").cookie(session, csrf))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("forbidden"));
		this.mvc.perform(post("/api/auth/logout").cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue() + "x"))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("forbidden"));
		// 쿠키가 없으면 header만으로는 지나가지 못한다.
		this.mvc.perform(post("/api/auth/logout").cookie(session).header("X-XSRF-TOKEN", csrf.getValue()))
			.andExpect(status().isForbidden());
		// 개발용 로그인 chain도 같다.
		this.mvc.perform(post(DevLoginConfiguration.PATH).cookie(csrf)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"externalRef\":\"" + this.ref + "\"}")).andExpect(status().isForbidden());
		this.mvc.perform(get("/api/me").cookie(session, csrf)).andExpect(status().isOk());
	}

	private MockHttpServletResponse devLogin(Cookie csrf) throws Exception {
		return this.mvc
			.perform(mutating(post(DevLoginConfiguration.PATH), csrf).contentType(MediaType.APPLICATION_JSON)
				.content("{\"externalRef\":\"" + this.ref + "\"}"))
			.andExpect(status().isNoContent())
			.andReturn()
			.getResponse();
	}

	private Cookie csrf() throws Exception {
		return this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie(CsrfCookie.NAME);
	}

	/** 마지막 쿠키가 CSRF 쿠키이고 그 값을 header로도 보낸다. */
	private static MockHttpServletRequestBuilder mutating(MockHttpServletRequestBuilder request, Cookie... cookies) {
		return request.cookie(cookies).header("X-XSRF-TOKEN", cookies[cookies.length - 1].getValue());
	}

	/** 응답이 준 새 XSRF-TOKEN. 지우는 줄 없이 브라우저 session 동안 남는 값 하나만 있다. */
	private static Cookie renewed(MockHttpServletResponse response) {
		assertThat(csrfCookies(response)).hasSize(1);
		Cookie cookie = response.getCookie(CsrfCookie.NAME);
		assertThat(cookie.getMaxAge()).isEqualTo(-1);
		return cookie;
	}

	private static List<String> csrfCookies(MockHttpServletResponse response) {
		return response.getHeaders(HttpHeaders.SET_COOKIE)
			.stream()
			.filter((c) -> c.startsWith(CsrfCookie.NAME + "="))
			.toList();
	}

}
