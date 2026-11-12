package com.stackcorp.astra.identity.breakglass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

class BreakGlassAuthTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestData data;

	long adminId;

	@BeforeEach
	void account() {
		this.adminId = this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", BREAK_GLASS_REF)
			.query(Long.class)
			.optional()
			.orElseGet(() -> this.data.userWithRef(BREAK_GLASS_REF, "STAFF"));
		this.jdbc.sql("DELETE FROM login_events WHERE user_id = :id").param("id", this.adminId).update();
	}

	@Test
	void logsInWithPasswordAndTotpAndUsesSessionCookie() throws Exception {
		Cookie csrf = csrf();
		MvcResult login = this.mvc.perform(login(csrf, BREAK_GLASS_PASSWORD, currentCode()))
			.andExpect(status().isNoContent())
			.andReturn();

		String setCookie = login.getResponse().getHeader("Set-Cookie");
		assertThat(setCookie).startsWith("ASTRA_SESSION=").contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/");
		Cookie session = login.getResponse().getCookie("ASTRA_SESSION");
		this.mvc.perform(get("/api/me").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(this.adminId))
			.andExpect(jsonPath("$.breakGlass").value(true));
		this.mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
		assertThat(token(session)).isNotEqualTo(storedHash(session));
	}

	@Test
	void rejectsWrongSecondFactorAndRecordsReasonOnlyInAudit() throws Exception {
		Cookie csrf = csrf();
		this.mvc.perform(login(csrf, BREAK_GLASS_PASSWORD, "000000"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("rejected"));
		this.mvc.perform(login(csrf, "wrong", currentCode()))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("rejected"));

		assertThat(this.jdbc.sql("SELECT failure_reason FROM login_events WHERE user_id = :id ORDER BY login_event_id")
			.param("id", this.adminId)
			.query(String.class)
			.list()).containsExactly("BAD_TOTP", "BAD_PASSWORD");
	}

	@Test
	void refusesToReuseSecondFactorRightAfterSuccess() throws Exception {
		Cookie csrf = csrf();
		this.mvc.perform(login(csrf, BREAK_GLASS_PASSWORD, currentCode())).andExpect(status().isNoContent());

		this.mvc.perform(login(csrf, BREAK_GLASS_PASSWORD, currentCode())).andExpect(status().isUnauthorized());
	}

	@Test
	void locksAfterRepeatedFailures() throws Exception {
		Cookie csrf = csrf();
		for (int i = 0; i < BreakGlassLogin.MAX_FAILURES; i++) {
			this.mvc.perform(login(csrf, "wrong", currentCode())).andExpect(status().isUnauthorized());
		}

		this.mvc.perform(login(csrf, BREAK_GLASS_PASSWORD, currentCode()))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("locked"));
	}

	@Test
	void requiresCsrfTokenAndSameOriginForStateChanges() throws Exception {
		this.mvc.perform(post("/api/auth/break-glass").contentType(MediaType.APPLICATION_JSON).content(body("x", "000000")))
			.andExpect(status().isForbidden());
		Cookie csrf = csrf();
		this.mvc.perform(login(csrf, BREAK_GLASS_PASSWORD, currentCode()).header("Origin", "https://evil.example"))
			.andExpect(status().isForbidden());
		this.mvc.perform(login(csrf, BREAK_GLASS_PASSWORD, currentCode()).header("Sec-Fetch-Site", "cross-site"))
			.andExpect(status().isForbidden());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM login_events WHERE user_id = :id")
			.param("id", this.adminId)
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void logoutRevokesSession() throws Exception {
		Cookie session = new Cookie("ASTRA_SESSION", this.data.session(this.adminId));
		Cookie csrf = csrf();

		this.mvc.perform(post("/api/auth/logout").cookie(session, csrf).header("X-XSRF-TOKEN", csrf.getValue()))
			.andExpect(status().isNoContent());

		this.mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
		assertThat(this.jdbc.sql("SELECT revoke_reason FROM user_sessions WHERE session_token_hash = :h")
			.param("h", storedHash(session))
			.query(String.class)
			.single()).isEqualTo("LOGOUT");
	}

	@Test
	void expiresIdleSession() throws Exception {
		Cookie session = new Cookie("ASTRA_SESSION", this.data.session(this.adminId));
		this.jdbc.sql("""
				UPDATE user_sessions SET last_seen_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 3 HOUR)
				 WHERE session_token_hash = :h
				""").param("h", storedHash(session)).update();

		this.mvc.perform(get("/api/me").cookie(session)).andExpect(status().isUnauthorized());
		assertThat(this.jdbc.sql("SELECT revoke_reason FROM user_sessions WHERE session_token_hash = :h")
			.param("h", storedHash(session))
			.query(String.class)
			.single()).isEqualTo("EXPIRED");
	}

	private Cookie csrf() throws Exception {
		return this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
	}

	private MockHttpServletRequestBuilder login(Cookie csrf, String password, String code) {
		return post("/api/auth/break-glass").cookie(csrf)
			.header("X-XSRF-TOKEN", csrf.getValue())
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(password, code));
	}

	private static String body(String password, String code) {
		return "{\"username\":\"" + BREAK_GLASS_REF + "\",\"password\":\"" + password + "\",\"code\":\"" + code + "\"}";
	}

	private static String currentCode() {
		return Totp.code(Totp.base32(TOTP_SECRET), Instant.now().getEpochSecond() / Totp.STEP_SECONDS);
	}

	private static String token(Cookie session) {
		return session.getValue();
	}

	private static String storedHash(Cookie session) throws Exception {
		var digest = java.security.MessageDigest.getInstance("SHA-256").digest(session.getValue().getBytes());
		return java.util.HexFormat.of().formatHex(digest);
	}

}
