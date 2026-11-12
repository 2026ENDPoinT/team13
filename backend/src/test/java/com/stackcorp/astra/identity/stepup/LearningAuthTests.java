package com.stackcorp.astra.identity.stepup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.OidcIntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.identity.session.DevLoginConfiguration;
import com.stackcorp.astra.identity.session.TestSessions;

import jakarta.servlet.http.Cookie;

/**
 * 학습 인증 challenge와 grant(SC-06-A, SEC-04, IDENTITY-03~05, ADR-0029). 수단은 학교 IdP 재인증({@code idp})이고 근거는
 * challenge를 만든 뒤 같은 session에 기록된 재인증이다. 확인 요청의 본문으로는 통과할 수 없다.
 */
class LearningAuthTests extends OidcIntegrationTest {

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
	void grantsAfterIdpReauthenticationDuringTheChallenge() throws Exception {
		this.client.get("/api/me/learning-auth")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.granted").value(false))
			.andExpect(jsonPath("$.expiresAt").doesNotExist())
			.andExpect(jsonPath("$.channels[0]").value("idp"));

		String challengeId = ApiClient.json(create(this.client).andExpect(status().isCreated())
			.andExpect(jsonPath("$.channel").value("idp"))
			.andExpect(jsonPath("$.status").value("pending"))
			.andExpect(jsonPath("$.redirectPath").value("/api/auth/reauth"))
			.andExpect(jsonPath("$.expiresAt").exists())).get("challengeId").asString();
		// 확인 전에는 grant가 없다.
		this.client.get("/api/me/learning-auth").andExpect(jsonPath("$.granted").value(false));

		idpReauthentication();
		verify(this.client, challengeId, "{}").andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("confirmed"))
			.andExpect(jsonPath("$.grantExpiresAt").exists());
		this.client.get("/api/me/learning-auth").andExpect(jsonPath("$.granted").value(true));
		assertThat(this.jdbc.sql("""
				SELECT granted_method FROM session_step_ups WHERE session_id = :s AND step_up_purpose = 'LEARNING_AUTH'
				""").param("s", TestSessions.id(this.jdbc, this.token)).query(String.class).single())
			.isEqualTo("SECONDARY_AUTH");

		// 이미 확인한 challenge를 다시 보내도 새 grant가 생기지 않는다.
		verify(this.client, challengeId, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("challenge_used"));
	}

	@Test
	void neverPassesOnEmptyOddOrClientClaimedPayloads() throws Exception {
		String challengeId = challengeId(this.client);

		verify(this.client, challengeId, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("verification_failed"));
		verify(this.client, challengeId, "{}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("verification_failed"));
		verify(this.client, challengeId, "{\"code\":\"123456\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("verification_failed"));
		verify(this.client, challengeId, "{\"code\":\"\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		verify(this.client, challengeId, "{\"status\":\"confirmed\"}").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		verify(this.client, challengeId, "{\"isError\":false}").andExpect(status().isBadRequest());
		// 본문 형식 오류는 시도로 세지 않는다. 근거 없는 확인 세 번만 셌다.
		assertThat(attempts(challengeId)).isEqualTo(3);
		verify(this.client, challengeId, "[]").andExpect(status().isBadRequest());
		verify(this.client, challengeId, "\"confirmed\"").andExpect(status().isBadRequest());

		this.client.get("/api/me/learning-auth").andExpect(jsonPath("$.granted").value(false));
	}

	@Test
	void reauthenticationBeforeTheChallengeDoesNotCount() throws Exception {
		devReauthentication(this.client);
		this.jdbc.sql("""
				UPDATE session_step_ups SET granted_at = DATE_SUB(granted_at, INTERVAL 1 SECOND)
				 WHERE session_id = :s AND step_up_purpose = 'RECENT_AUTH'
				""").param("s", TestSessions.id(this.jdbc, this.token)).update();
		String challengeId = challengeId(this.client);

		verify(this.client, challengeId, "{}").andExpect(status().isBadRequest());

		devReauthentication(this.client);
		verify(this.client, challengeId, "{}").andExpect(status().isOk());
	}

	@Test
	void locksTheChallengeAfterRepeatedFailuresAndThenTheUser() throws Exception {
		String first = challengeId(this.client);
		for (int i = 1; i < 5; i++) {
			verify(this.client, first, "{}").andExpect(status().isBadRequest());
		}
		verify(this.client, first, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("challenge_locked"));
		// 잠긴 challenge는 맞는 근거가 생겨도 통과하지 않는다.
		devReauthentication(this.client);
		verify(this.client, first, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("challenge_locked"));

		String second = challengeId(this.client);
		for (int i = 0; i < 5; i++) {
			verify(this.client, second, "{\"code\":\"0000\"}");
		}
		create(this.client).andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("learning_auth_locked"));
		this.client.get("/api/me/learning-auth").andExpect(jsonPath("$.granted").value(false));
	}

	@Test
	void rejectsExpiredCancelledAndForeignChallenges() throws Exception {
		String expired = challengeId(this.client);
		this.jdbc.sql("UPDATE secondary_auth_challenges SET expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE auth_seq = :s")
			.param("s", expired)
			.update();
		devReauthentication(this.client);
		verify(this.client, expired, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("challenge_expired"));

		String cancelled = challengeId(this.client);
		String current = challengeId(this.client);
		verify(this.client, cancelled, "{}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("challenge_cancelled"));

		// 다른 사용자와 같은 사용자의 다른 session은 이 challenge를 볼 수 없다.
		ApiClient stranger = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		devReauthentication(stranger);
		verify(stranger, current, "{}").andExpect(status().isNotFound());
		ApiClient otherSession = new ApiClient(this.mvc, this.data.session(this.userId));
		devReauthentication(otherSession);
		verify(otherSession, current, "{}").andExpect(status().isNotFound());
		verify(this.client, "not-a-challenge", "{}").andExpect(status().isNotFound());
		assertThat(attempts(current)).isZero();
		stranger.get("/api/me/learning-auth").andExpect(jsonPath("$.granted").value(false));

		create(this.client, "{\"channel\":\"sms\"}").andExpect(status().isBadRequest());
		create(this.client, "{}").andExpect(status().isBadRequest());
		create(this.client, "{\"channel\":\"idp\",\"destination\":\"010-0000-0000\"}").andExpect(status().isBadRequest());
	}

	@Test
	void confirmsOnceUnderConcurrentVerification() throws Exception {
		String challengeId = challengeId(this.client);
		devReauthentication(this.client);

		List<Integer> statuses = parallel(4, () -> verify(this.client, challengeId, "{}").andReturn()
			.getResponse()
			.getStatus());

		assertThat(statuses).containsOnlyOnce(200).containsOnly(200, 409);
	}

	@Test
	void revokesTheGrantOfThisSessionOnly() throws Exception {
		grant(this.client);
		String otherToken = this.data.session(this.userId);
		ApiClient other = new ApiClient(this.mvc, otherToken);
		grant(other);

		this.client.post("/api/me/learning-auth/revoke").andExpect(status().isNoContent());
		this.client.post("/api/me/learning-auth/revoke").andExpect(status().isNoContent());

		this.client.get("/api/me/learning-auth").andExpect(jsonPath("$.granted").value(false));
		other.get("/api/me/learning-auth").andExpect(jsonPath("$.granted").value(true));
	}

	/** 학교 IdP에서 다시 로그인한다. */
	private void idpReauthentication() throws Exception {
		Cookie session = new Cookie("ASTRA_SESSION", this.token);
		MvcResult start = this.mvc.perform(get("/api/auth/reauth").cookie(session))
			.andExpect(status().isFound())
			.andReturn();
		URI callback = IDP.authorize(URI.create(start.getResponse().getHeader("Location")), this.ref);
		MvcResult done = this.mvc
			.perform(get(URI.create(callback.getRawPath() + "?" + callback.getRawQuery()))
				.header("Sec-Fetch-Site", "cross-site")
				.cookie(start.getResponse().getCookie("ASTRA_LOGIN"), session))
			.andReturn();
		assertThat(done.getResponse().getHeader("Location")).isEqualTo("/");
	}

	private void devReauthentication(ApiClient client) throws Exception {
		client.post(DevLoginConfiguration.STEP_UP_PATH).andExpect(status().isNoContent());
	}

	private void grant(ApiClient client) throws Exception {
		LearningAuthFlow.grant(client);
	}

	private String challengeId(ApiClient client) throws Exception {
		return ApiClient.json(create(client).andExpect(status().isCreated())).get("challengeId").asString();
	}

	private ResultActions create(ApiClient client) throws Exception {
		return create(client, "{\"channel\":\"idp\"}");
	}

	private ResultActions create(ApiClient client, String body) throws Exception {
		return client.postJson("/api/me/learning-auth/challenges", body);
	}

	private ResultActions verify(ApiClient client, String challengeId, String body) throws Exception {
		String path = "/api/me/learning-auth/challenges/" + challengeId + "/verify";
		return body == null ? client.post(path)
				: client.send(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private int attempts(String challengeId) {
		return this.jdbc.sql("SELECT attempt_count FROM secondary_auth_challenges WHERE auth_seq = :s")
			.param("s", challengeId)
			.query(Integer.class)
			.single();
	}

	private static <T> List<T> parallel(int threads, Callable<T> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				futures.add(pool.submit(() -> {
					go.await();
					return task.call();
				}));
			}
			go.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
