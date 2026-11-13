package com.stackcorp.astra.identity.breakglass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

/**
 * 로그인 기록과 session에 남기는 브라우저 주소. {@code X-Forwarded-For}는 연결한 쪽이 설정한 proxy 범위
 * ({@link IntegrationTest#TRUSTED_PROXIES})일 때만 오른쪽부터 읽고, 다른 곳에서 온 같은 header는 무시한다.
 */
class ClientAddressLoginTests extends IntegrationTest {

	private static final String CLOUDFRONT = "10.40.2.15";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestData data;

	long adminId;

	@BeforeEach
	@AfterEach
	void account() {
		this.adminId = this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", BREAK_GLASS_REF)
			.query(Long.class)
			.optional()
			.orElseGet(() -> this.data.userWithRef(BREAK_GLASS_REF, "STAFF"));
		// 잠금과 TOTP 재사용 판정이 다른 테스트의 로그인에 걸리지 않게 한다.
		this.jdbc.sql("DELETE FROM login_events WHERE user_id = :id").param("id", this.adminId).update();
		this.jdbc.sql("""
				DELETE g FROM session_step_ups g JOIN user_sessions s ON s.session_id = g.session_id WHERE s.user_id = :id
				""").param("id", this.adminId).update();
	}

	@Test
	void recordsTheForwardedAddressOnlyWhenTheConnectionComesFromTheTrustedProxy() throws Exception {
		Cookie csrf = this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");

		// CloudFront는 브라우저가 보낸 값 뒤에 자기가 본 주소를 덧붙인다. 왼쪽 값은 읽지 않는다.
		Cookie session = this.mvc
			.perform(from(CLOUDFRONT, login(csrf, BREAK_GLASS_PASSWORD)).header("X-Forwarded-For",
					"198.51.100.66, 203.0.113.9"))
			.andExpect(status().isNoContent())
			.andReturn()
			.getResponse()
			.getCookie("ASTRA_SESSION");
		assertThat(this.jdbc.sql("""
				SELECT ip_address FROM user_sessions WHERE session_token_hash = SHA2(:token, 256)
				""").param("token", session.getValue()).query(String.class).single()).isEqualTo("203.0.113.9");

		// 같은 header라도 proxy가 아닌 곳에서 오면 연결한 쪽 주소다.
		this.mvc.perform(login(csrf, "wrong").header("X-Forwarded-For", "203.0.113.10"))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(from("10.40.3.4", login(csrf, "wrong")).header("X-Forwarded-For", "203.0.113.11"))
			.andExpect(status().isUnauthorized());
		// proxy에서 왔어도 형식이 다르거나 비어 있으면 그 proxy 주소다.
		this.mvc.perform(from(CLOUDFRONT, login(csrf, "wrong")).header("X-Forwarded-For", "evil.example"))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(from(CLOUDFRONT, login(csrf, "wrong")).header("X-Forwarded-For", ""))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(from(CLOUDFRONT, login(csrf, "wrong")).header("X-Forwarded-For", "2001:db8::7"))
			.andExpect(status().isUnauthorized());

		assertThat(this.jdbc.sql("SELECT ip_address FROM login_events WHERE user_id = :id ORDER BY login_event_id")
			.param("id", this.adminId)
			.query(String.class)
			.list()).containsExactly("203.0.113.9", "127.0.0.1", "10.40.3.4", CLOUDFRONT, CLOUDFRONT,
					"2001:db8:0:0:0:0:0:7");
	}

	private static MockHttpServletRequestBuilder login(Cookie csrf, String password) {
		String code = Totp.code(Totp.base32(TOTP_SECRET), Instant.now().getEpochSecond() / Totp.STEP_SECONDS);
		return post("/api/auth/break-glass").cookie(csrf)
			.header("X-XSRF-TOKEN", csrf.getValue())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"username\":\"" + BREAK_GLASS_REF + "\",\"password\":\"" + password + "\",\"code\":\"" + code
					+ "\"}");
	}

	private static MockHttpServletRequestBuilder from(String address, MockHttpServletRequestBuilder request) {
		return request.with((r) -> {
			r.setRemoteAddr(address);
			return r;
		});
	}

}
