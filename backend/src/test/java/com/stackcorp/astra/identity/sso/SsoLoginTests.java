package com.stackcorp.astra.identity.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.OidcIntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.identity.breakglass.BreakGlassCodes;
import com.stackcorp.astra.integration.oidc.MockOpenIdProvider;

import jakarta.servlet.http.Cookie;

/**
 * 학교 IdP 로그인 전체 흐름(ADR-0017). 같은 process의 가짜 OpenID Provider를 issuer로 설정한 context({@link OidcIntegrationTest})에서
 * 실행한다. 가짜 IdP는 context가 시작하기 전부터 503으로만 응답한다. 시작할 때 IdP를 불렀다면 context가 뜨지 않거나 요청 수가 남는다.
 */
@ExtendWith(OutputCaptureExtension.class)
class SsoLoginTests extends OidcIntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestData data;

	@BeforeEach
	void idpUp() {
		IDP.reset();
	}

	@Test
	void loginReplacesATokenTheBrowserHadBeforeLogin() throws Exception {
		String ref = studentNumber();
		this.data.userWithRef(ref, "STUDENT");
		Cookie before = new Cookie("XSRF-TOKEN", "token-from-before-login");

		Started started = start("/");
		MockHttpServletResponse response = callback(IDP.authorize(started.location(), ref), started.cookie(), before)
			.getResponse();

		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getCookie("ASTRA_SESSION")).isNotNull();
		assertThat(response.getHeaders("Set-Cookie").stream().filter((c) -> c.startsWith("XSRF-TOKEN=")))
			.singleElement()
			.satisfies((c) -> assertThat(c).startsWith("XSRF-TOKEN=").doesNotContain(before.getValue()));
		assertThat(response.getCookie("XSRF-TOKEN").getValue()).isNotBlank().isNotEqualTo(before.getValue());
	}

	@Test
	void logsInWithCodeFlowAndLandsOnReturnPath(CapturedOutput output) throws Exception {
		String ref = studentNumber();
		long userId = this.data.userWithRef(ref, "STUDENT");

		Started started = start("/courses/7?tab=notice");
		Map<String, String> authorization = query(started.location());
		assertThat(started.location().toString()).startsWith(IDP.issuer() + "/protocol/openid-connect/auth?");
		assertThat(authorization).containsEntry("client_id", MockOpenIdProvider.CLIENT_ID)
			.containsEntry("redirect_uri", REDIRECT_URI)
			.containsEntry("scope", "openid profile")
			.containsEntry("response_type", "code")
			.containsEntry("code_challenge_method", "S256")
			.containsKeys("state", "nonce", "code_challenge");
		assertThat(started.result().getResponse().getHeader("Set-Cookie")).startsWith("ASTRA_LOGIN=")
			.contains("Path=/api/auth/callback", "Max-Age=600", "HttpOnly", "Secure", "SameSite=Lax");
		assertThat(started.result().getRequest().getSession(false)).isNull();

		MvcResult done = callback(IDP.authorize(started.location(), ref), started.cookie());

		MockHttpServletResponse response = done.getResponse();
		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getHeader("Location")).isEqualTo("/courses/7?tab=notice");
		assertThat(response.getHeaders("Set-Cookie")).anySatisfy((c) -> assertThat(c).startsWith("ASTRA_SESSION=")
			.contains("HttpOnly", "Secure", "SameSite=Lax", "Path=/"))
			.anySatisfy((c) -> assertThat(c).startsWith("ASTRA_LOGIN=;").contains("Max-Age=0", "Path=/api/auth/callback"))
			// 로그인 전에 받은 CSRF token을 로그인 뒤에 쓰지 않는다(ADR-0004 4번).
			.anySatisfy((c) -> assertThat(c).startsWith("XSRF-TOKEN=").contains("Path=/").doesNotContain("Max-Age"));
		assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
		this.mvc.perform(get("/api/me").cookie(response.getCookie("ASTRA_SESSION")))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.userId").value(userId))
			.andExpect(jsonPath("$.breakGlass").value(false))
			.andExpect(jsonPath("$.stepUpMethod").value("idp"));
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM login_events
				 WHERE user_id = :id AND login_result = 'SUCCESS' AND session_id IS NOT NULL
				""").param("id", userId).query(Integer.class).single()).isOne();
		assertNoSecrets(output, authorization, started.result().getResponse(), response);
	}

	@Test
	void fallsBackToRootForUnsafeReturnPaths() throws Exception {
		String ref = studentNumber();
		this.data.userWithRef(ref, "STUDENT");
		List<String> unsafe = List.of("https://evil.example/x", "//evil.example/x", "/\\evil.example", "\\\\evil.example",
				"javascript:alert(1)", "evil.example/x", "/ok\r\nSet-Cookie: x=1", "/tab\t/x", "/space x", "/한글",
				"/" + "a".repeat(ReturnPath.MAX_LENGTH));

		for (String returnTo : unsafe) {
			Started started = start(returnTo);
			MvcResult done = callback(IDP.authorize(started.location(), ref), started.cookie());
			assertThat(done.getResponse().getHeader("Location")).as(returnTo).isEqualTo("/");
			assertThat(done.getResponse().getCookie("ASTRA_SESSION")).as(returnTo).isNotNull();
		}
		Started duplicated = start(get("/api/auth/login").param("returnTo", "/a", "/b"));
		assertThat(callback(IDP.authorize(duplicated.location(), ref), duplicated.cookie()).getResponse()
			.getHeader("Location")).isEqualTo("/");
	}

	@Test
	void rejectsMissingAlteredOrForeignState() throws Exception {
		String ref = studentNumber();
		long userId = this.data.userWithRef(ref, "STUDENT");

		Started started = start("/");
		URI callback = IDP.authorize(started.location(), ref);
		Map<String, String> parameters = query(callback);
		assertFailed(callback(callback("code", parameters.get("code")), started.cookie()));
		assertFailed(callback(callback("code", parameters.get("code"), "state", parameters.get("state") + "x"),
				started.cookie()));
		// 로그인 전용 쿠키가 없는 브라우저(다른 사람이 보낸 callback 주소)는 로그인되지 않는다.
		assertFailed(callback(callback));

		Started first = start("/");
		Started second = start("/");
		assertFailed(callback(IDP.authorize(first.location(), ref), second.cookie()));
		assertFailed(callback(IDP.authorize(first.location(), ref), second.cookie(), first.cookie()));

		assertThat(sessions(userId)).isZero();
	}

	@Test
	void rejectsReplayedCallback() throws Exception {
		String ref = studentNumber();
		long userId = this.data.userWithRef(ref, "STUDENT");
		Started started = start("/");
		URI callback = IDP.authorize(started.location(), ref);

		assertThat(callback(callback, started.cookie()).getResponse().getCookie("ASTRA_SESSION")).isNotNull();
		assertFailed(callback(callback, started.cookie()));

		assertThat(sessions(userId)).isOne();
	}

	@Test
	void rejectsIdTokensThatFailValidation(CapturedOutput output) throws Exception {
		String ref = studentNumber();
		long userId = this.data.userWithRef(ref, "STUDENT");
		long now = Instant.now().getEpochSecond();
		Map<String, Runnable> cases = new LinkedHashMap<>();
		cases.put("wrong nonce", () -> IDP.claims((c) -> c.put("nonce", "another-nonce")));
		cases.put("wrong issuer", () -> IDP.claims((c) -> c.put("iss", "https://evil.example")));
		cases.put("wrong audience", () -> IDP.claims((c) -> c.put("aud", "another-client")));
		cases.put("several audiences without azp",
				() -> IDP.claims((c) -> c.put("aud", List.of(MockOpenIdProvider.CLIENT_ID, "another-client"))));
		cases.put("foreign azp", () -> IDP.claims((c) -> c.put("azp", "another-client")));
		cases.put("expired", () -> IDP.claims((c) -> {
			c.put("iat", now - 400);
			c.put("exp", now - 120);
		}));
		cases.put("issued in the future", () -> IDP.claims((c) -> c.put("iat", now + 600)));
		cases.put("bad signature", () -> IDP.token((t) -> t.substring(0, t.length() - 6) + "AAAAAA"));
		cases.put("alg none", () -> {
			IDP.header((h) -> h.put("alg", "none"));
			IDP.token((t) -> t.substring(0, t.lastIndexOf('.') + 1));
		});
		cases.put("alg HS256", () -> IDP.header((h) -> h.put("alg", "HS256")));
		cases.put("unknown kid", () -> IDP.signWithUnpublishedKey());
		cases.put("missing user claim", () -> IDP.claims((c) -> c.remove(MockOpenIdProvider.USER_CLAIM)));

		for (Map.Entry<String, Runnable> scenario : cases.entrySet()) {
			IDP.reset();
			scenario.getValue().run();
			Started started = start("/");
			MvcResult done = callback(IDP.authorize(started.location(), ref), started.cookie());
			assertThat(done.getResponse().getHeader("Location")).as(scenario.getKey()).isEqualTo("/?login=failed");
		}

		assertThat(sessions(userId)).isZero();
		assertThat(output).contains("SSO login rejected: reason=id_token_nonce",
				"SSO login rejected: reason=id_token_unknown_key", "SSO login rejected: reason=id_token_alg");
		assertNoSecrets(output, Map.of());
	}

	@Test
	void acceptsSigningKeyRotation() throws Exception {
		String ref = studentNumber();
		long userId = this.data.userWithRef(ref, "STUDENT");
		login(ref);

		IDP.rotate();
		MvcResult done = login(ref);

		assertThat(done.getResponse().getCookie("ASTRA_SESSION")).isNotNull();
		assertThat(sessions(userId)).isEqualTo(2);
	}

	@Test
	void admitsOnlyExistingActiveUsersOtherThanBreakGlass() throws Exception {
		assertFailed(login(studentNumber()));

		String suspended = studentNumber();
		long suspendedId = this.data.userWithRef(suspended, "STUDENT");
		this.jdbc.sql("UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = :id").param("id", suspendedId).update();
		assertFailed(login(suspended));

		long breakGlassId = breakGlassUser();
		assertFailed(login(BREAK_GLASS_REF));

		assertThat(sessions(suspendedId)).isZero();
		assertThat(sessions(breakGlassId)).isZero();
		assertThat(failureReasons(suspendedId)).containsExactly("SSO_INACTIVE");
		assertThat(failureReasons(breakGlassId)).contains("SSO_BREAK_GLASS");
	}

	@Test
	void startsWithoutTheIdpAndKeepsBreakGlassWhileItIsDown() throws Exception {
		assertThat(REQUESTS_AT_STARTUP).hasValue(0);
		IDP.down(true);

		assertThat(methods()).contains("oidc", "breakGlass");

		long adminId = breakGlassUser();
		Cookie csrf = this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		MvcResult login = this.mvc
			.perform(post("/api/auth/break-glass").cookie(csrf)
				.header("X-XSRF-TOKEN", csrf.getValue())
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"username\":\"" + BREAK_GLASS_REF + "\",\"password\":\"" + BREAK_GLASS_PASSWORD
						+ "\",\"code\":\"" + BreakGlassCodes.current() + "\"}"))
			.andExpect(status().isNoContent())
			.andReturn();
		this.mvc.perform(get("/api/me").cookie(login.getResponse().getCookie("ASTRA_SESSION")))
			.andExpect(jsonPath("$.userId").value(adminId))
			.andExpect(jsonPath("$.breakGlass").value(true))
			.andExpect(jsonPath("$.stepUpMethod").value("totp"));
	}

	@Test
	void failsGenericallyWhenTheIdpGoesDownBeforeCallback() throws Exception {
		String ref = studentNumber();
		long userId = this.data.userWithRef(ref, "STUDENT");
		Started started = start("/");
		URI callback = IDP.authorize(started.location(), ref);

		IDP.down(true);
		assertFailed(callback(callback, started.cookie()));

		assertThat(sessions(userId)).isZero();
	}

	@Test
	void listsLoginMethodsOfThisRun() throws Exception {
		MvcResult result = this.mvc.perform(get("/api/auth/methods")).andExpect(status().isOk()).andReturn();

		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		assertThat(methods()).containsExactly("oidc", "breakGlass", "devLogin");
	}

	private record Started(Cookie cookie, URI location, MvcResult result) {
	}

	private Started start(String returnTo) throws Exception {
		return start(get("/api/auth/login").param("returnTo", returnTo));
	}

	private Started start(MockHttpServletRequestBuilder request) throws Exception {
		MvcResult result = this.mvc.perform(request).andExpect(status().isFound()).andReturn();
		return new Started(result.getResponse().getCookie("ASTRA_LOGIN"),
				URI.create(result.getResponse().getHeader("Location")), result);
	}

	private MvcResult login(String ref) throws Exception {
		Started started = start("/");
		return callback(IDP.authorize(started.location(), ref), started.cookie());
	}

	// IdP에서 돌아오는 요청은 다른 사이트에서 시작한 top-level 이동이다.
	private MvcResult callback(URI callback, Cookie... cookies) throws Exception {
		MockHttpServletRequestBuilder request = get(URI.create(callback.getRawPath() + "?" + callback.getRawQuery()))
			.header("Sec-Fetch-Site", "cross-site");
		if (cookies.length > 0) {
			request.cookie(cookies);
		}
		return this.mvc.perform(request).andReturn();
	}

	private static URI callback(String... pairs) {
		StringBuilder query = new StringBuilder();
		for (int i = 0; i < pairs.length; i += 2) {
			query.append(i == 0 ? "" : "&").append(pairs[i]).append('=').append(pairs[i + 1]);
		}
		return URI.create(REDIRECT_URI + "?" + query);
	}

	private static void assertFailed(MvcResult result) {
		MockHttpServletResponse response = result.getResponse();
		assertThat(response.getStatus()).isEqualTo(302);
		assertThat(response.getHeader("Location")).isEqualTo("/?login=failed");
		assertThat(response.getCookie("ASTRA_SESSION")).isNull();
		assertThat(response.getCookie("ASTRA_LOGIN").getMaxAge()).isZero();
	}

	private void assertNoSecrets(CapturedOutput output, Map<String, String> authorization,
			MockHttpServletResponse... responses) {
		List<String> secrets = new ArrayList<>(IDP.issuedSecrets());
		secrets.add(MockOpenIdProvider.CLIENT_SECRET);
		authorization.forEach((name, value) -> {
			if (List.of("state", "nonce", "code_challenge").contains(name)) {
				secrets.add(value);
			}
		});
		for (String secret : secrets) {
			assertThat(output.getAll()).doesNotContain(secret);
			for (MockHttpServletResponse response : responses) {
				for (String header : response.getHeaderNames()) {
					if (!"Location".equals(header) || !response.getHeader(header).startsWith(IDP.issuer())) {
						assertThat(response.getHeaders(header)).noneMatch((v) -> v.contains(secret));
					}
				}
			}
		}
	}

	private List<String> methods() throws Exception {
		List<String> methods = new ArrayList<>();
		ApiClient.json(this.mvc.perform(get("/api/auth/methods"))).path("methods").forEach((m) -> methods.add(m.asString()));
		return methods;
	}

	private long breakGlassUser() {
		return this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", BREAK_GLASS_REF)
			.query(Long.class)
			.optional()
			.orElseGet(() -> this.data.userWithRef(BREAK_GLASS_REF, "STAFF"));
	}

	private int sessions(long userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM user_sessions WHERE user_id = :id")
			.param("id", userId)
			.query(Integer.class)
			.single();
	}

	private List<String> failureReasons(long userId) {
		return this.jdbc.sql("SELECT failure_reason FROM login_events WHERE user_id = :id AND login_result = 'FAILURE'")
			.param("id", userId)
			.query(String.class)
			.list();
	}

	private static Map<String, String> query(URI uri) {
		Map<String, String> values = new LinkedHashMap<>();
		for (String pair : uri.getRawQuery().split("&")) {
			int eq = pair.indexOf('=');
			values.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
		}
		return values;
	}

	private static String decode(String value) {
		return java.net.URLDecoder.decode(value, java.nio.charset.StandardCharsets.UTF_8);
	}

	private static String studentNumber() {
		return "2026" + ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999);
	}

}
