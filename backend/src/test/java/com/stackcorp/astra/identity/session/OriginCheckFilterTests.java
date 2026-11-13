package com.stackcorp.astra.identity.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 프론트엔드 서버(Frontend-final server.mjs)와 CloudFront는 Host를 앱 주소로 바꾸거나 https를 http로 넘긴다. 그래서 앱은 요청에서 브라우저의
 * origin을 알 수 없고, 배포는 허용 Origin을 설정으로 넣는다. 앞단이 붙이는 X-Forwarded-Host는 누구나 보낼 수 있어 믿지 않는다.
 */
class OriginCheckFilterTests {

	private static final String FRONTEND = "https://eclass.example.ac.kr";

	@Test
	void acceptsAMutatingRequestFromTheConfiguredFrontendOriginThroughAProxyThatRewritesHost() throws Exception {
		MockHttpServletResponse response = run(List.of(FRONTEND), proxied(FRONTEND, "eclass.example.ac.kr"));

		assertThat(response.getStatus()).isEqualTo(200);
	}

	@Test
	void refusesAnotherOriginEvenWhenItsForwardedHostClaimsToMatch() throws Exception {
		MockHttpServletResponse response = run(List.of(FRONTEND), proxied("https://evil.example", "evil.example"));

		assertThat(response.getStatus()).isEqualTo(403);
		assertThat(response.getContentAsString()).contains("\"code\":\"forbidden\"").contains("허용하지 않은 출처");
	}

	@Test
	void refusesAProxiedRequestWhenNoOriginIsConfiguredBecauseForwardedHeadersAreNotTrusted() throws Exception {
		MockHttpServletResponse response = run(List.of(), proxied(FRONTEND, "eclass.example.ac.kr"));

		assertThat(response.getStatus()).isEqualTo(403);
	}

	@Test
	void stillAcceptsItsOwnOriginWhenNoOriginIsConfigured() throws Exception {
		MockHttpServletRequest request = post("http://127.0.0.1:8080");

		assertThat(run(List.of(), request).getStatus()).isEqualTo(200);
	}

	@Test
	void refusesAnAllowedOriginThatABrowserCannotSendAndNamesTheVariable() {
		for (String unusable : List.of("https://eclass.example.ac.kr/", "https://Eclass.example.ac.kr",
				"https://eclass.example.ac.kr:443", "https://eclass.example.ac.kr/app", "eclass.example.ac.kr",
				"ftp://eclass.example.ac.kr", "")) {
			assertThatIllegalArgumentException().isThrownBy(() -> properties(List.of(unusable)))
				.withMessageContaining("ASTRA_SESSION_ALLOWED_ORIGINS");
		}
		assertThat(properties(List.of(FRONTEND, "http://127.0.0.1:4190", "http://[::1]:4190")).allowedOrigins())
			.hasSize(3);
	}

	// server.mjs가 백엔드로 보내는 모양: Host는 upstream, 브라우저의 Origin과 Sec-Fetch-Site를 그대로 넘기고 X-Forwarded-*를 붙인다.
	private static MockHttpServletRequest proxied(String origin, String forwardedHost) {
		MockHttpServletRequest request = post(origin);
		request.addHeader("Sec-Fetch-Site", "same-origin");
		request.addHeader("X-Forwarded-Host", forwardedHost);
		request.addHeader("X-Forwarded-Proto", "https");
		request.addHeader("X-Forwarded-For", "203.0.113.7, 127.0.0.1");
		return request;
	}

	private static MockHttpServletRequest post(String origin) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/logout");
		request.setScheme("http");
		request.setServerName("127.0.0.1");
		request.setServerPort(8080);
		request.addHeader("Host", "127.0.0.1:8080");
		request.addHeader("Origin", origin);
		return request;
	}

	private static MockHttpServletResponse run(List<String> allowed, MockHttpServletRequest request) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		new OriginCheckFilter(properties(allowed)).doFilter(request, response, new MockFilterChain());
		return response;
	}

	private static SessionProperties properties(List<String> allowed) {
		return new SessionProperties("ASTRA_SESSION", Duration.ofHours(2), Duration.ofHours(12), true, allowed);
	}

}
