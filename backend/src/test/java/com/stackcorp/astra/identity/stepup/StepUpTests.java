package com.stackcorp.astra.identity.stepup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.OidcIntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.identity.session.TestSessions;

import jakarta.servlet.http.Cookie;

/**
 * 학교 IdP 재인증(step-up)과 그것을 요구하는 명령(ADR-0029). 같은 process의 가짜 OpenID Provider로 재인증한다.
 */
class StepUpTests extends OidcIntegrationTest {

	private static final String CONTACT = "{\"phone\":\"010-2222-3333\",\"email\":\"new@school.example\"}";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestData data;

	String ref;

	long userId;

	String token;

	ApiClient client;

	@BeforeEach
	void user() {
		IDP.reset();
		this.ref = "2026" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
		this.userId = this.data.userWithRef(this.ref, "STUDENT");
		this.token = this.data.session(this.userId);
		this.client = new ApiClient(this.mvc, this.token);
	}

	@Test
	void reauthenticatesAtTheIdpAndReturnsToTheOriginalPage() throws Exception {
		MvcResult start = this.mvc.perform(get("/api/auth/reauth").param("returnTo", "/me/info?tab=contact")
			.cookie(session(this.token))).andExpect(status().isFound()).andReturn();
		URI location = URI.create(start.getResponse().getHeader("Location"));
		assertThat(location.toString()).startsWith(IDP.issuer()).contains("prompt=login", "max_age=0");

		MockHttpServletResponse done = callback(IDP.authorize(location, this.ref),
				start.getResponse().getCookie("ASTRA_LOGIN"), session(this.token));

		assertThat(done.getStatus()).isEqualTo(302);
		assertThat(done.getHeader("Location")).isEqualTo("/me/info?tab=contact");
		// 새 session을 만들지 않는다.
		assertThat(done.getCookie("ASTRA_SESSION")).isNull();
		assertThat(done.getCookie("ASTRA_LOGIN").getMaxAge()).isZero();
		assertThat(stepUps("RECENT_AUTH", "SSO")).isOne();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM user_sessions WHERE user_id = :id")
			.param("id", this.userId)
			.query(Integer.class)
			.single()).isOne();
	}

	@Test
	void contactChangeRequiresAFreshReauthentication() throws Exception {
		contact(this.client, CONTACT).andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("step_up_required"));
		this.client.get("/api/me/profile").andExpect(jsonPath("$.contact.phone").doesNotExist());

		reauthenticate(this.token, this.ref);
		contact(this.client, CONTACT).andExpect(status().isOk())
			.andExpect(jsonPath("$.contact.phone").value("010-2222-3333"))
			.andExpect(jsonPath("$.contact.source").value("local"))
			.andExpect(jsonPath("$.contact.editable").value(true));
		contact(this.client, "{\"phone\":\"12\",\"email\":\"new@school.example\"}").andExpect(status().isBadRequest());
		contact(this.client, "{\"phone\":\"010-2222-3333\",\"email\":\"<a>@x\"}").andExpect(status().isBadRequest());

		// 재인증이 오래되면 다시 요구한다.
		this.jdbc.sql("""
				UPDATE session_step_ups SET granted_at = DATE_SUB(granted_at, INTERVAL 11 MINUTE)
				 WHERE session_id = :session AND step_up_purpose = 'RECENT_AUTH'
				""").param("session", sessionId(this.token)).update();
		contact(this.client, CONTACT).andExpect(status().isForbidden());

		// 다른 session의 재인증은 이 session에 쓰이지 않는다.
		String otherToken = this.data.session(this.userId);
		reauthenticate(otherToken, this.ref);
		contact(this.client, CONTACT).andExpect(status().isForbidden());
		contact(new ApiClient(this.mvc, otherToken), CONTACT).andExpect(status().isOk());
	}

	@Test
	void registrarOwnedContactsStayReadOnlyEvenAfterReauthentication() throws Exception {
		this.jdbc.sql("""
				INSERT INTO user_contacts (user_id, phone_number, email_address, contact_source)
				VALUES (:id, '010-1111-1111', 'school@school.example', 'ACADEMIC')
				""").param("id", this.userId).update();
		reauthenticate(this.token, this.ref);

		contact(this.client, CONTACT).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("contact_managed_by_registrar"));
		this.client.get("/api/me/profile").andExpect(jsonPath("$.contact.phone").value("010-1111-1111"));
	}

	@Test
	void rejectsReauthenticationThatDoesNotProveThisUserAgain() throws Exception {
		String other = "2026" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
		this.data.userWithRef(other, "STUDENT");

		// IdP에서 다른 계정으로 로그인했다.
		assertReauthFailed(attempt(this.token, other, true));
		// 로그인 session이 없는 브라우저가 재인증 callback을 가져왔다.
		assertReauthFailed(attempt(this.token, this.ref, false));
		// IdP가 다시 인증하지 않고 예전 auth_time을 돌려줬다.
		long stale = Instant.now().getEpochSecond() - 3600;
		IDP.claims((c) -> c.put("auth_time", stale));
		MockHttpServletResponse staleResult = attempt(this.token, this.ref, true);
		assertThat(staleResult.getHeader("Location")).isEqualTo("/?login=failed");
		IDP.reset();

		assertThat(stepUps("RECENT_AUTH", "SSO")).isZero();
		contact(this.client, CONTACT).andExpect(status().isForbidden());
	}

	@Test
	void failedReauthenticationReturnsToTheValidatedStartingPageWithAFailureMarker() throws Exception {
		String other = "2026" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
		this.data.userWithRef(other, "STUDENT");

		assertThat(attempt(this.token, other, "/me/info?tab=contact#phone").getHeader("Location"))
			.isEqualTo("/me/info?tab=contact&reauth=failed#phone");
		assertThat(attempt(this.token, other, "/courses/7").getHeader("Location")).isEqualTo("/courses/7?reauth=failed");
		// 같은 origin의 상대 경로가 아니면 로그인과 같이 /로 보낸다.
		for (String unsafe : List.of("//evil.example/x", "https://evil.example/", "/\\evil.example")) {
			assertThat(attempt(this.token, other, unsafe).getHeader("Location")).isEqualTo("/?reauth=failed");
		}
		assertThat(stepUps("RECENT_AUTH", "SSO")).isZero();
	}

	// 재인증 시작은 top-level 이동이다. 시작할 수 없으면 JSON 대신 시작한 화면으로 reauth=failed를 붙여 돌려보낸다.
	@Test
	void returnsToTheMarkedStartingPageWithoutAUsableSession() throws Exception {
		assertNotStarted(this.mvc.perform(get("/api/auth/reauth").param("returnTo", "/me/info?step=contact"))
			.andReturn()
			.getResponse(), "/me/info?step=contact&reauth=failed");

		this.jdbc.sql("""
				UPDATE user_sessions SET last_seen_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 3 HOUR)
				 WHERE session_id = :id
				""").param("id", sessionId(this.token)).update();
		assertNotStarted(start(this.token, "/me/info?step=contact"), "/me/info?step=contact&reauth=failed");
		// 만료된 session은 전처럼 폐기되고 이 경로가 되살리거나 늘리지 않는다.
		assertThat(this.jdbc.sql("SELECT revoke_reason FROM user_sessions WHERE session_id = :id")
			.param("id", sessionId(this.token))
			.query(String.class)
			.single()).isEqualTo("EXPIRED");
		assertNotStarted(start(this.token, "/me/info?step=contact"), "/me/info?step=contact&reauth=failed");
		this.client.get("/api/me").andExpect(status().isUnauthorized());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM user_sessions WHERE user_id = :id")
			.param("id", this.userId)
			.query(Integer.class)
			.single()).isOne();
	}

	@Test
	void returnsBreakGlassSessionsToTheMarkedStartingPage() throws Exception {
		String admin = this.data.session(breakGlassUser());
		// 학습 화면의 새 탭은 auth=done을 붙인 경로로 시작한다. 실패 표시는 그 뒤에 붙는다.
		assertNotStarted(start(admin, "/courses/7/lectures/3?item=5&auth=done"),
				"/courses/7/lectures/3?item=5&auth=done&reauth=failed");
		new ApiClient(this.mvc, admin).get("/api/me").andExpect(status().isOk());
	}

	@Test
	void countsEveryStartAndReturnsToTheMarkedStartingPageWhenRateLimited() throws Exception {
		for (int i = 0; i < 10; i++) {
			MockHttpServletResponse started = start(this.token, "/me/info");
			assertThat(started.getStatus()).isEqualTo(302);
			assertThat(started.getHeader("Location")).startsWith(IDP.issuer());
		}
		assertNotStarted(start(this.token, "/me/info"), "/me/info?reauth=failed");
		assertNotStarted(start(this.token, "/me/info#top"), "/me/info?reauth=failed#top");
		this.client.get("/api/me").andExpect(status().isOk());
	}

	@Test
	void neverFollowsAForeignOrMalformedReturnPathWhenItCannotStart() throws Exception {
		String admin = this.data.session(breakGlassUser());
		List<String> unsafe = List.of("//evil.example/x", "https://evil.example/", "/\\evil.example",
				"javascript:alert(1)", "evil.example", "/a b", "/" + "a".repeat(600), "");
		for (String returnTo : unsafe) {
			assertNotStarted(this.mvc.perform(get("/api/auth/reauth").param("returnTo", returnTo))
				.andReturn()
				.getResponse(), "/?reauth=failed");
			assertNotStarted(start(admin, returnTo), "/?reauth=failed");
		}
		assertNotStarted(this.mvc.perform(get("/api/auth/reauth").param("returnTo", "/a", "/b")).andReturn().getResponse(),
				"/?reauth=failed");
		assertNotStarted(this.mvc.perform(get("/api/auth/reauth")).andReturn().getResponse(), "/?reauth=failed");
	}

	@Test
	void revokesAllOtherSessionsOnlyAfterReauthenticationAndKeepsTheCurrentOne() throws Exception {
		String second = this.data.session(this.userId);
		String third = this.data.session(this.userId);
		this.client.post("/api/me/sessions/revoke-others").andExpect(status().isForbidden());
		new ApiClient(this.mvc, second).get("/api/me").andExpect(status().isOk());

		reauthenticate(this.token, this.ref);
		this.client.post("/api/me/sessions/revoke-others")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revokedSessions").value(2));

		new ApiClient(this.mvc, second).get("/api/me").andExpect(status().isUnauthorized());
		new ApiClient(this.mvc, third).get("/api/me").andExpect(status().isUnauthorized());
		this.client.get("/api/me").andExpect(status().isOk());
	}

	private ResultActions contact(ApiClient client, String body) throws Exception {
		return client.send(put("/api/me/contact").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	/** IdP에서 다시 로그인하고 callback까지 마친다. */
	private void reauthenticate(String sessionToken, String studentNumber) throws Exception {
		MockHttpServletResponse done = attempt(sessionToken, studentNumber, true);
		assertThat(done.getHeader("Location")).isEqualTo("/");
	}

	private MockHttpServletResponse attempt(String sessionToken, String studentNumber, boolean withSession)
			throws Exception {
		return attempt(sessionToken, studentNumber, withSession, null);
	}

	private MockHttpServletResponse attempt(String sessionToken, String studentNumber, String returnTo)
			throws Exception {
		return attempt(sessionToken, studentNumber, true, returnTo);
	}

	private MockHttpServletResponse attempt(String sessionToken, String studentNumber, boolean withSession,
			String returnTo) throws Exception {
		var request = get("/api/auth/reauth").cookie(session(sessionToken));
		if (returnTo != null) {
			request.param("returnTo", returnTo);
		}
		MvcResult start = this.mvc.perform(request).andExpect(status().isFound()).andReturn();
		URI callback = IDP.authorize(URI.create(start.getResponse().getHeader("Location")), studentNumber);
		Cookie login = start.getResponse().getCookie("ASTRA_LOGIN");
		return withSession ? callback(callback, login, session(sessionToken)) : callback(callback, login);
	}

	// IdP에서 돌아오는 요청은 다른 사이트에서 시작한 top-level 이동이다. SameSite=Lax session 쿠키는 함께 온다.
	private MockHttpServletResponse callback(URI callback, Cookie... cookies) throws Exception {
		return this.mvc
			.perform(get(URI.create(callback.getRawPath() + "?" + callback.getRawQuery()))
				.header("Sec-Fetch-Site", "cross-site")
				.cookie(cookies))
			.andReturn()
			.getResponse();
	}

	private static void assertReauthFailed(MockHttpServletResponse response) {
		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getHeader("Location")).isEqualTo("/?reauth=failed");
		assertThat(response.getCookie("ASTRA_SESSION")).isNull();
	}

	private int stepUps(String purpose, String method) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM session_step_ups g JOIN user_sessions s ON s.session_id = g.session_id
				 WHERE s.user_id = :user AND g.step_up_purpose = :purpose AND g.granted_method = :method
				""")
			.param("user", this.userId)
			.param("purpose", purpose)
			.param("method", method)
			.query(Integer.class)
			.single();
	}

	private long sessionId(String sessionToken) {
		return TestSessions.id(this.jdbc, sessionToken);
	}

	private static Cookie session(String token) {
		return new Cookie("ASTRA_SESSION", token);
	}

	private MockHttpServletResponse start(String sessionToken, String returnTo) throws Exception {
		return this.mvc.perform(get("/api/auth/reauth").param("returnTo", returnTo).cookie(session(sessionToken)))
			.andReturn()
			.getResponse();
	}

	/** 시작하지 않고 돌려보냈다. session도 로그인 전용 쿠키도 만들거나 지우지 않는다. */
	private static void assertNotStarted(MockHttpServletResponse response, String location) {
		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getHeader("Location")).isEqualTo(location);
		assertThat(response.getCookie("ASTRA_SESSION")).isNull();
		assertThat(response.getCookie("ASTRA_LOGIN")).isNull();
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(response.getHeader("Referrer-Policy")).isEqualTo("no-referrer");
	}

	private long breakGlassUser() {
		return this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", BREAK_GLASS_REF)
			.query(Long.class)
			.optional()
			.orElseGet(() -> this.data.userWithRef(BREAK_GLASS_REF, "STAFF"));
	}

}
