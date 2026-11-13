package com.stackcorp.astra.identity.breakglass;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

/**
 * break-glass session의 TOTP 재인증(ADR-0029). 로그인과 같은 잠금과 코드 재사용 금지를 쓴다. break-glass 계정은 하나라 테스트 앞뒤로
 * 그 계정의 로그인 기록과 재인증 기록을 지운다.
 */
class BreakGlassStepUpTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestData data;

	long adminId;

	ApiClient admin;

	@BeforeEach
	void account() {
		this.adminId = this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", BREAK_GLASS_REF)
			.query(Long.class)
			.optional()
			.orElseGet(() -> this.data.userWithRef(BREAK_GLASS_REF, "STAFF"));
		clean();
		this.admin = new ApiClient(this.mvc, this.data.session(this.adminId));
	}

	@AfterEach
	void clean() {
		this.jdbc.sql("DELETE FROM login_events WHERE user_id = :id").param("id", this.adminId).update();
		this.jdbc.sql("""
				DELETE g FROM session_step_ups g JOIN user_sessions s ON s.session_id = g.session_id WHERE s.user_id = :id
				""").param("id", this.adminId).update();
	}

	@Test
	void reentersTotpOnceAndSatisfiesStepUp() throws Exception {
		this.admin.post("/api/me/sessions/revoke-others").andExpect(status().isForbidden());

		stepUp(this.admin, "000000").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("verification_failed"));
		stepUp(this.admin, "abcdef").andExpect(status().isBadRequest());
		stepUp(this.admin, currentCode()).andExpect(status().isNoContent());
		this.admin.post("/api/me/sessions/revoke-others").andExpect(status().isOk());

		// 같은 코드를 다른 session에서 다시 쓰지 못한다. 로그인에도 다시 쓰지 못한다.
		ApiClient another = new ApiClient(this.mvc, this.data.session(this.adminId));
		stepUp(another, currentCode()).andExpect(status().isBadRequest());
		another.post("/api/me/sessions/revoke-others").andExpect(status().isForbidden());
		assertThat(this.jdbc.sql("""
				SELECT failure_reason FROM login_events WHERE user_id = :id AND login_result = 'FAILURE'
				 ORDER BY login_event_id
				""").param("id", this.adminId).query(String.class).list()).containsExactly("STEP_UP_BAD_TOTP",
				"STEP_UP_BAD_TOTP", "STEP_UP_TOTP_REUSE_WINDOW");
	}

	@Test
	void sharesLockoutWithLoginAndRefusesNonBreakGlassSessions() throws Exception {
		for (int i = 0; i < BreakGlassLogin.MAX_FAILURES; i++) {
			stepUp(this.admin, "000000").andExpect(status().isBadRequest());
		}
		stepUp(this.admin, currentCode()).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("locked"));
		this.admin.post("/api/me/sessions/revoke-others").andExpect(status().isForbidden());

		ApiClient student = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		stepUp(student, currentCode()).andExpect(status().isNotFound());
	}

	private ResultActions stepUp(ApiClient client, String code) throws Exception {
		return client.postJson("/api/me/step-up/totp", "{\"code\":\"" + code + "\"}");
	}

	private static String currentCode() {
		return Totp.code(Totp.base32(TOTP_SECRET), Instant.now().getEpochSecond() / Totp.STEP_SECONDS);
	}

}
