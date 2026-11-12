package com.stackcorp.astra.identity.breakglass;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.stackcorp.astra.shared.audit.AuditRows;
import com.stackcorp.astra.shared.audit.AuditRows.Row;

/**
 * break-glass 로그인의 성공과 실패, 로그아웃은 감사 기록을 남긴다(SC-09). 실패 기록은 계정을 특정하지 않고, 입력한 값을 담지 않는다.
 */
class BreakGlassAuditTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestData data;

	long adminId;

	@BeforeEach
	@AfterEach
	void clearLoginEvents() {
		this.adminId = this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", BREAK_GLASS_REF)
			.query(Long.class)
			.optional()
			.orElseGet(() -> this.data.userWithRef(BREAK_GLASS_REF, "STAFF"));
		// 잠금과 TOTP 재사용 판정이 다른 테스트의 로그인에 걸리지 않게 한다.
		this.jdbc.sql("DELETE FROM login_events WHERE user_id = :id").param("id", this.adminId).update();
	}

	@Test
	void recordsRejectedAndSuccessfulLoginsAndLogout() throws Exception {
		ApiClient anonymous = new ApiClient(this.mvc, null);

		ResultActions unknown = anonymous.postJson("/api/auth/break-glass", login("nobody", BREAK_GLASS_PASSWORD))
			.andExpect(status().isUnauthorized());
		assertThat(AuditRows.single(this.jdbc, unknown))
			.isEqualTo(new Row(null, null, "auth.break_glass_login", null, null, null, "rejected"));
		ResultActions wrong = anonymous.postJson("/api/auth/break-glass", login(BREAK_GLASS_REF, "wrong-password"))
			.andExpect(status().isUnauthorized());
		assertThat(AuditRows.single(this.jdbc, wrong))
			.isEqualTo(new Row(null, null, "auth.break_glass_login", null, null, null, "rejected"));

		ResultActions success = anonymous.postJson("/api/auth/break-glass", login(BREAK_GLASS_REF, BREAK_GLASS_PASSWORD))
			.andExpect(status().isNoContent());
		long session = this.jdbc.sql("SELECT MAX(session_id) FROM user_sessions WHERE user_id = :u")
			.param("u", this.adminId)
			.query(Long.class)
			.single();
		assertThat(AuditRows.single(this.jdbc, success))
			.isEqualTo(new Row(this.adminId, session, "auth.break_glass_login", "user_session", session, null, "ok"));

		String token = success.andReturn().getResponse().getCookie("ASTRA_SESSION").getValue();
		ResultActions logout = new ApiClient(this.mvc, token).post("/api/auth/logout").andExpect(status().isNoContent());
		assertThat(AuditRows.single(this.jdbc, logout))
			.isEqualTo(new Row(this.adminId, session, "auth.logout", "user_session", session, null, "ok"));
	}

	private static String login(String username, String password) {
		String code = Totp.code(Totp.base32(TOTP_SECRET), Instant.now().getEpochSecond() / Totp.STEP_SECONDS);
		return "{\"username\":\"" + username + "\",\"password\":\"" + password + "\",\"code\":\"" + code + "\"}";
	}

}
