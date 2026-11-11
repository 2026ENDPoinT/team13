package com.stackcorp.astra.learning;

import static com.stackcorp.astra.learning.LearningSessionApiTests.end;
import static com.stackcorp.astra.learning.LearningSessionApiTests.heartbeats;
import static com.stackcorp.astra.learning.LearningSessionApiTests.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.OidcIntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.astra.identity.session.TestSessions;
import com.stackcorp.astra.identity.stepup.LearningAuthFlow;

import tools.jackson.databind.JsonNode;

/**
 * 학습 인증을 요구하는 배포의 적립(SC-06-A, 규칙 v2, ADR-0029). 학습 시계는 테스트가 움직인다. grant 만료는 DB 시계로 정해지므로
 * 경계 사례는 grant 만료를 고정한 학습 시계 기준으로 직접 둔다. 주기 60초, 최소 간격 30초, lease 180초.
 */
class LearningAuthCreditTests extends OidcIntegrationTest {

	private static final AtomicInteger LINK_SEQ = new AtomicInteger(1_000_000);

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestLearningClock clock;

	@Autowired
	SessionStore sessions;

	long course;

	long activity;

	long item;

	long stu;

	String token;

	ApiClient student;

	LocalDateTime t0;

	@BeforeEach
	void lecture() {
		this.course = this.data.course();
		this.stu = this.data.user("STUDENT");
		this.data.enroll(this.course, this.stu);
		this.activity = this.data.activity(this.course, "lecture_weeks", true, null, null);
		this.item = lectureItem(this.activity);
		this.token = this.data.session(this.stu);
		this.student = new ApiClient(this.mvc, this.token);
		this.t0 = this.clock.freeze();
	}

	@AfterEach
	void release() {
		this.clock.release();
	}

	@Test
	void requiresAGrantToStartCreditingButNotForInstructors() throws Exception {
		this.student.post(start(this.course, this.item))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("learning_auth_required"));
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_sessions WHERE user_id = :u")
			.param("u", this.stu)
			.query(Integer.class)
			.single()).isZero();

		long prof = this.data.user("PROFESSOR");
		this.data.instructor(this.course, prof);
		new ApiClient(this.mvc, this.data.session(prof)).post(start(this.course, this.item))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.creditable").value(false));

		LearningAuthFlow.grant(this.student);
		JsonNode started = ApiClient.json(this.student.post(start(this.course, this.item)).andExpect(status().isCreated()));
		LocalDateTime until = LocalDateTime.parse(started.get("creditUntil").asString());
		assertThat(until).isAfter(this.t0.plusHours(1));
		assertThat(authorizedUntil(started.get("sessionId").asLong())).isEqualTo(until);
	}

	@Test
	void cutsCreditAtGrantExpiryAndKeepsEarnedTime() throws Exception {
		long session = startWithGrant();
		expireGrantAt(this.t0.plusSeconds(90));

		beat(session, 1).andExpect(jsonPath("$.outcome").value("too_early"));
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 2).andExpect(jsonPath("$.creditedSec").value(60))
			.andExpect(jsonPath("$.creditState").value("authorized"))
			.andExpect(jsonPath("$.creditOpen").value(true));
		this.clock.advance(Duration.ofSeconds(60));
		JsonNode cut = ApiClient.json(beat(session, 3).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("credited"))
			.andExpect(jsonPath("$.creditedSec").value(30))
			.andExpect(jsonPath("$.creditState").value("reauth_required"))
			.andExpect(jsonPath("$.creditOpen").value(false)));
		assertThat(LocalDateTime.parse(cut.get("creditUntil").asString())).isEqualTo(this.t0.plusSeconds(90));
		// 재생은 이어지고 영상 cookie도 받지만 적립은 없다.
		assertThat(cut.get("playback").isNull()).isFalse();
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 4).andExpect(jsonPath("$.outcome").value("reauth_required"))
			.andExpect(jsonPath("$.creditedSec").value(0))
			.andExpect(jsonPath("$.progress.creditedSec").value(90));

		assertThat(state(session)).isEqualTo("reauth_required");
		assertThat(ledger(session)).containsExactly(60, 30);
		assertThat(rules(session)).containsOnly("v2");

		// 끝내도 경계 뒤는 적립하지 않는다.
		this.clock.advance(Duration.ofSeconds(30));
		this.student.postJson(end(this.course, session), "{}")
			.andExpect(jsonPath("$.state").value("ended"))
			.andExpect(jsonPath("$.creditedSec").value(0));
		assertThat(ledger(session)).containsExactly(60, 30);
	}

	@Test
	void resumesAfterReauthenticationWithoutBackCredit() throws Exception {
		long session = startWithGrant();
		expireGrantAt(this.t0.plusSeconds(90));
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 1).andExpect(jsonPath("$.creditedSec").value(60));
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 2).andExpect(jsonPath("$.creditState").value("reauth_required"));
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 3).andExpect(jsonPath("$.creditedSec").value(0));

		// 다시 인증한다. 새 grant는 지금부터다.
		LearningAuthFlow.grant(this.student);
		this.clock.advance(Duration.ofSeconds(20));
		beat(session, 4).andExpect(jsonPath("$.outcome").value("resumed"))
			.andExpect(jsonPath("$.creditedSec").value(0))
			.andExpect(jsonPath("$.creditState").value("authorized"))
			.andExpect(jsonPath("$.creditOpen").value(true));
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 5).andExpect(jsonPath("$.creditedSec").value(60))
			.andExpect(jsonPath("$.progress.creditedSec").value(150));

		assertThat(state(session)).isEqualTo("active");
		assertThat(ledger(session)).containsExactly(60, 30, 60);
		// 인증이 없던 t0+90 ~ t0+200은 적립하지 않았다.
		assertThat(this.jdbc.sql("SELECT MIN(from_at) FROM learning_credit_ledger WHERE learning_session_id = :s AND from_at > :t")
			.param("s", session)
			.param("t", this.t0.plusSeconds(90))
			.query(LocalDateTime.class)
			.single()).isEqualTo(this.t0.plusSeconds(200));
	}

	@Test
	void stopsCreditingWhenTheGrantIsRevoked() throws Exception {
		long session = startWithGrant();
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 1).andExpect(jsonPath("$.creditedSec").value(60));

		this.student.post("/api/me/learning-auth/revoke").andExpect(status().isNoContent());
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 2).andExpect(jsonPath("$.outcome").value("reauth_required"))
			.andExpect(jsonPath("$.creditedSec").value(0))
			.andExpect(jsonPath("$.progress.creditedSec").value(60));
		assertThat(ledger(session)).containsExactly(60);

		// 새 학습 session도 grant 없이 시작하지 못한다.
		this.student.post(start(this.course, this.item)).andExpect(status().isForbidden());
	}

	@Test
	void revokedLoginSessionLosesAccessAndAnotherSessionHasNoGrant() throws Exception {
		long session = startWithGrant();
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 1).andExpect(jsonPath("$.creditedSec").value(60));

		this.sessions.revoke(TestSessions.id(this.jdbc, this.token), "LOGOUT");
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 2).andExpect(status().isUnauthorized());

		// 같은 사람이 다시 로그인해도 grant는 로그인 session마다 따로다. 그 session으로는 적립하지 않는다.
		ApiClient relogged = new ApiClient(this.mvc, this.data.session(this.stu));
		relogged.postJson(heartbeats(this.course, session), "{\"seq\":3}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("reauth_required"))
			.andExpect(jsonPath("$.creditedSec").value(0));
		assertThat(ledger(session)).containsExactly(60);
	}

	@Test
	void closeAtBeforeTheGrantEndsTheWindowNotTheAuthorization() throws Exception {
		this.jdbc.sql("UPDATE learning_activities SET close_at = :c WHERE activity_id = :a")
			.param("c", this.t0.plusSeconds(90))
			.param("a", this.activity)
			.update();
		long session = startWithGrant();
		assertThat(authorizedUntil(session)).isEqualTo(this.t0.plusSeconds(90));

		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 1).andExpect(jsonPath("$.creditedSec").value(60));
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 2).andExpect(jsonPath("$.creditedSec").value(30))
			.andExpect(jsonPath("$.creditState").value("authorized"))
			.andExpect(jsonPath("$.creditOpen").value(false));
		this.clock.advance(Duration.ofSeconds(60));
		beat(session, 3).andExpect(jsonPath("$.outcome").value("window_closed"));
		assertThat(state(session)).isEqualTo("active");
	}

	private long startWithGrant() throws Exception {
		LearningAuthFlow.grant(this.student);
		return ApiClient.json(this.student.post(start(this.course, this.item)).andExpect(status().isCreated()))
			.get("sessionId")
			.asLong();
	}

	/** 이 로그인 session의 grant 만료를 학습 시계 기준 시각으로 둔다. */
	private void expireGrantAt(LocalDateTime at) {
		this.jdbc.sql("""
				UPDATE session_step_ups SET expires_at = :at WHERE session_id = :s AND step_up_purpose = 'LEARNING_AUTH'
				""").param("at", at).param("s", TestSessions.id(this.jdbc, this.token)).update();
	}

	private ResultActions beat(long session, long seq) throws Exception {
		return this.student.postJson(heartbeats(this.course, session), "{\"seq\":" + seq + "}");
	}

	private String state(long session) {
		return this.jdbc.sql("SELECT state FROM learning_sessions WHERE learning_session_id = :s")
			.param("s", session)
			.query(String.class)
			.single();
	}

	private LocalDateTime authorizedUntil(long session) {
		return this.jdbc.sql("SELECT credit_authorized_until FROM learning_sessions WHERE learning_session_id = :s")
			.param("s", session)
			.query(LocalDateTime.class)
			.single();
	}

	private List<Integer> ledger(long session) {
		return this.jdbc.sql("SELECT credited_sec FROM learning_credit_ledger WHERE learning_session_id = :s ORDER BY from_at")
			.param("s", session)
			.query(Integer.class)
			.list();
	}

	private List<String> rules(long session) {
		return this.jdbc.sql("SELECT rule_version FROM learning_credit_ledger WHERE learning_session_id = :s")
			.param("s", session)
			.query(String.class)
			.list();
	}

	private long lectureItem(long activityId) {
		Map<String, Object> params = new HashMap<>();
		params.put("a", activityId);
		params.put("seq", LINK_SEQ.incrementAndGet());
		params.put("path", "vod/lecture-" + UUID.randomUUID().toString().substring(0, 8) + "/main.m3u8");
		return this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path, duration_sec, required_sec)
				VALUES (:a, :seq, '9주차 강의', 'video', :path, 600, 600)
				""", params);
	}

}
