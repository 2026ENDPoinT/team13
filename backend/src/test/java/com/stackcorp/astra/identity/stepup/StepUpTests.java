package com.stackcorp.astra.identity.stepup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
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
	void blankContactValuesClearThatContact() throws Exception {
		// 지우는 것도 연락처 변경이라 재인증이 먼저다.
		contact(this.client, "{\"phone\":\"\",\"email\":\"\"}").andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("step_up_required"));
		reauthenticate(this.token, this.ref);
		contact(this.client, CONTACT).andExpect(status().isOk());

		// 화면은 앞뒤 공백을 뗀 입력을 그대로 보낸다. 빈 전화번호와 이메일만.
		contact(this.client, "{\"phone\":\"\",\"email\":\"only@school.example\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.contact.phone").value(nullValue()))
			.andExpect(jsonPath("$.contact.email").value("only@school.example"))
			.andExpect(jsonPath("$.contact.source").value("local"))
			.andExpect(jsonPath("$.contact.editable").value(true));
		assertThat(storedContact()).containsExactly(null, "only@school.example");

		// 전화번호만, 공백뿐인 이메일.
		contact(this.client, "{\"phone\":\"010-2222-3333\",\"email\":\"   \"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.contact.phone").value("010-2222-3333"))
			.andExpect(jsonPath("$.contact.email").value(nullValue()));
		assertThat(storedContact()).containsExactly("010-2222-3333", null);

		// 둘 다 비우면 연락처가 없다. 연락처를 하나 이상 요구하는 규칙은 없다.
		contact(this.client, "{\"phone\":\"\",\"email\":\"\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.contact.phone").value(nullValue()))
			.andExpect(jsonPath("$.contact.email").value(nullValue()))
			.andExpect(jsonPath("$.contact.source").value("local"))
			.andExpect(jsonPath("$.contact.editable").value(true));
		assertThat(storedContact()).containsExactly(null, null);
		this.client.get("/api/me/profile")
			.andExpect(jsonPath("$.contact.phone").value(nullValue()))
			.andExpect(jsonPath("$.contact.email").value(nullValue()));

		// 값이 있으면 형식은 그대로 본다. 다른 값이 비어도 같다.
		contact(this.client, "{\"phone\":\"12\",\"email\":\"\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"))
			.andExpect(jsonPath("$.detail").value("전화번호 형식이 올바르지 않습니다."));
		contact(this.client, "{\"phone\":\"\",\"email\":\"<a>@x\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"))
			.andExpect(jsonPath("$.detail").value("이메일 형식이 올바르지 않습니다."));
		assertThat(storedContact()).containsExactly(null, null);
	}

	@Test
	void blankValuesDoNotClearRegistrarOwnedContacts() throws Exception {
		this.jdbc.sql("""
				INSERT INTO user_contacts (user_id, phone_number, email_address, contact_source)
				VALUES (:id, '010-1111-1111', 'school@school.example', 'ACADEMIC')
				""").param("id", this.userId).update();
		reauthenticate(this.token, this.ref);

		contact(this.client, "{\"phone\":\"\",\"email\":\"\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("contact_managed_by_registrar"));
		assertThat(storedContact()).containsExactly("010-1111-1111", "school@school.example");
	}

	@Test
	void firstContactSaveWithBothBlankCreatesAnEmptyLocalRow() throws Exception {
		reauthenticate(this.token, this.ref);
		assertThat(contactRows()).isZero();

		contact(this.client, "{\"phone\":\"\",\"email\":\" \"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.contact.phone").value(nullValue()))
			.andExpect(jsonPath("$.contact.email").value(nullValue()))
			.andExpect(jsonPath("$.contact.source").value("local"))
			.andExpect(jsonPath("$.contact.editable").value(true));
		assertThat(contactRows()).isOne();
		assertThat(storedContact()).containsExactly(null, null);
		assertThat(this.jdbc.sql("SELECT contact_source FROM user_contacts WHERE user_id = :id")
			.param("id", this.userId)
			.query(String.class)
			.single()).isEqualTo("LOCAL");
	}

	@Test
	void missingOrNullContactFieldsAreRefusedAndClearNothing() throws Exception {
		reauthenticate(this.token, this.ref);
		contact(this.client, CONTACT).andExpect(status().isOk());

		// 일부만 보낸 본문이 다른 연락처를 지우지 않는다. 지우려면 빈 문자열을 보낸다.
		contact(this.client, "{\"email\":\"a@b.example\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"))
			.andExpect(jsonPath("$.detail").value("전화번호 형식이 올바르지 않습니다."));
		contact(this.client, "{\"phone\":\"010-4444-5555\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"))
			.andExpect(jsonPath("$.detail").value("이메일 형식이 올바르지 않습니다."));
		contact(this.client, "{\"phone\":null,\"email\":\"a@b.example\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		contact(this.client, "{\"phone\":\"010-4444-5555\",\"email\":null}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		contact(this.client, "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		assertThat(storedContact()).containsExactly("010-2222-3333", "new@school.example");
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
		new ApiClient(this.mvc, null).get("/api/auth/reauth").andExpect(status().isUnauthorized());
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

	/** 저장된 전화번호와 이메일. */
	private List<String> storedContact() {
		return this.jdbc.sql("SELECT phone_number, email_address FROM user_contacts WHERE user_id = :id")
			.param("id", this.userId)
			.query((rs, n) -> Arrays.asList(rs.getString(1), rs.getString(2)))
			.single();
	}

	private int contactRows() {
		return this.jdbc.sql("SELECT COUNT(*) FROM user_contacts WHERE user_id = :id")
			.param("id", this.userId)
			.query(Integer.class)
			.single();
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

}
