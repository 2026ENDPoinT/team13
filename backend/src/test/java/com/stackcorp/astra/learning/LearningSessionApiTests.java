package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

/**
 * 온라인 강의 학습 session의 적립 규칙(ADR-0014). 시계는 테스트가 움직이고, 적립은 서버 시각과 원장으로만 정해진다.
 * 기본 주기는 60초다. 최소 간격 30초, 공백 상한 120초, lease 180초.
 */
class LearningSessionApiTests extends IntegrationTest {

	private static final AtomicInteger LINK_SEQ = new AtomicInteger();

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestLearningClock clock;

	@Autowired
	LearningSessionService service;

	long course;

	long activity;

	long item;

	long stu;

	ApiClient student;

	ApiClient professor;

	LocalDateTime t0;

	@BeforeEach
	void lecture() {
		this.course = this.data.course();
		this.stu = this.data.user("STUDENT");
		long prof = this.data.user("PROFESSOR");
		this.data.enroll(this.course, this.stu);
		this.data.instructor(this.course, prof);
		this.activity = this.data.activity(this.course, "lecture_weeks", true, null, null);
		this.item = lectureItem(this.activity, 600, 120);
		this.student = new ApiClient(this.mvc, this.data.session(this.stu));
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.t0 = this.clock.freeze();
	}

	@AfterEach
	void release() {
		this.clock.release();
	}

	@Test
	void creditsServerTimeUntilRequiredAndRecordsAttendanceOnce() throws Exception {
		long attendance = attendanceSession(this.activity);
		MockHttpServletResponse started = this.student.post(start(this.course, this.item))
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.heartbeatIntervalSec").value(60))
			.andExpect(jsonPath("$.leaseSec").value(180))
			.andExpect(jsonPath("$.creditable").value(true))
			.andExpect(jsonPath("$.progress.requiredSec").value(120))
			.andExpect(jsonPath("$.progress.status").value("under_required"))
			.andExpect(jsonPath("$.playback.manifestUrl").exists())
			.andReturn()
			.getResponse();
		assertThat(started.getHeaders("Set-Cookie")).hasSize(3);
		long session = ApiClient.json(started).get("sessionId").asLong();

		// 시작 직후의 하트비트는 적립하지 않는다.
		beat(this.student, session, 1).andExpect(jsonPath("$.outcome").value("too_early"))
			.andExpect(jsonPath("$.creditedSec").value(0));
		this.clock.advance(Duration.ofSeconds(60));
		MockHttpServletResponse second = beat(this.student, session, 2).andExpect(status().isOk())
			.andExpect(jsonPath("$.outcome").value("credited"))
			.andExpect(jsonPath("$.creditedSec").value(60))
			.andExpect(jsonPath("$.progress.creditedSec").value(60))
			.andExpect(jsonPath("$.progress.progressPercent").value(50))
			.andExpect(jsonPath("$.leaseRemainingSec").value(180))
			.andExpect(jsonPath("$.playback.expiresInSeconds").value(600))
			.andReturn()
			.getResponse();
		assertThat(second.getHeaders("Set-Cookie")).hasSize(3);
		this.clock.advance(Duration.ofSeconds(60));
		JsonNode met = ApiClient.json(beat(this.student, session, 3).andExpect(jsonPath("$.progress.status").value("met")));
		assertThat(LocalDateTime.parse(met.get("progress").get("attendedAt").asString())).isEqualTo(this.t0.plusSeconds(120));
		this.clock.advance(Duration.ofSeconds(60));
		JsonNode later = ApiClient.json(beat(this.student, session, 4).andExpect(jsonPath("$.creditedSec").value(60)));
		assertThat(later.get("progress").get("creditedSec").asInt()).isEqualTo(180);
		assertThat(LocalDateTime.parse(later.get("progress").get("attendedAt").asString()))
			.isEqualTo(this.t0.plusSeconds(120));

		this.clock.advance(Duration.ofSeconds(10));
		this.student.postJson(end(this.course, session), "{\"positionSec\":300}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ended"))
			.andExpect(jsonPath("$.endReason").value("button"))
			.andExpect(jsonPath("$.creditedSec").value(10))
			.andExpect(jsonPath("$.progress.resumePositionSec").value(300));
		this.student.postJson(end(this.course, session), "{\"reason\":\"unload\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ended"))
			.andExpect(jsonPath("$.endReason").value("button"))
			.andExpect(jsonPath("$.creditedSec").value(0));
		beat(this.student, session, 5).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("session_closed"));

		assertThat(credited(session)).isEqualTo(190);
		assertThat(this.jdbc.sql("""
				SELECT status, source, decided_at FROM attendance_records WHERE attendance_session_id = :s AND user_id = :u
				""").param("s", attendance).param("u", this.stu).query((rs, n) -> List.of(rs.getString(1), rs.getString(2),
				rs.getObject(3, LocalDateTime.class).toString())).list())
			.containsExactly(List.of("출석", "online", this.t0.plusSeconds(120).toString()));
		assertThat(this.jdbc.sql("""
				SELECT completed_at FROM activity_progress WHERE user_id = :u AND activity_id = :a
				""").param("u", this.stu).param("a", this.activity).query(LocalDateTime.class).single())
			.isEqualTo(this.t0.plusSeconds(120));
		assertThat(this.jdbc.sql("SELECT activity_id FROM course_continue_pointers WHERE user_id = :u AND course_id = :c")
			.param("u", this.stu)
			.param("c", this.course)
			.query(Long.class)
			.single()).isEqualTo(this.activity);
	}

	@Test
	void replayedAndReorderedHeartbeatsAddNothing() throws Exception {
		long session = started(this.student);
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, session, 2).andExpect(jsonPath("$.creditedSec").value(60));
		beat(this.student, session, 2).andExpect(jsonPath("$.outcome").value("duplicate"))
			.andExpect(jsonPath("$.creditedSec").value(0));
		beat(this.student, session, 1).andExpect(jsonPath("$.outcome").value("duplicate"));
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, session, 2).andExpect(jsonPath("$.outcome").value("duplicate"));
		beat(this.student, session, 1).andExpect(jsonPath("$.outcome").value("duplicate"));
		beat(this.student, session, 5).andExpect(jsonPath("$.creditedSec").value(60));

		assertThat(credited(session)).isEqualTo(120);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_heartbeats WHERE learning_session_id = :s")
			.param("s", session)
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

	@Test
	void burstHeartbeatsCannotOutrunTheWallClock() throws Exception {
		long session = started(this.student);
		for (int seq = 1; seq <= 40; seq++) {
			this.clock.advance(Duration.ofSeconds(1));
			beat(this.student, session, seq).andExpect(status().isOk());
		}
		for (int seq = 41; seq <= 60; seq++) {
			beat(this.student, session, seq).andExpect(jsonPath("$.outcome").value("too_early"));
		}

		// 30초째 하트비트가 0~30초를, 나머지는 아무것도 적립하지 않는다. 40초 중 10초는 다음 하트비트 몫이다.
		assertThat(credited(session)).isEqualTo(30);
		this.clock.advance(Duration.ofSeconds(20));
		beat(this.student, session, 61).andExpect(jsonPath("$.creditedSec").value(30));
		assertThat(credited(session)).isEqualTo(60);
	}

	@Test
	void ignoresElapsedTimeAndPositionTheClientClaims() throws Exception {
		long session = started(this.student);
		this.clock.advance(Duration.ofSeconds(60));
		this.student.postJson(heartbeats(this.course, session), "{\"seq\":1,\"elapsedSec\":3600}")
			.andExpect(status().isBadRequest());
		this.student.postJson(heartbeats(this.course, session), "{\"seq\":1,\"intervalSec\":3600}")
			.andExpect(status().isBadRequest());
		this.student.postJson(heartbeats(this.course, session), "{\"seq\":1,\"positionSec\":601}")
			.andExpect(status().isBadRequest());
		this.student.postJson(heartbeats(this.course, session), "{\"seq\":1,\"positionSec\":-1}")
			.andExpect(status().isBadRequest());
		this.student.postJson(heartbeats(this.course, session), "{\"seq\":0}").andExpect(status().isBadRequest());
		this.student.postJson(heartbeats(this.course, session), "{\"positionSec\":10}").andExpect(status().isBadRequest());

		// 끝 위치로 건너뛰어도 진도는 적립 시간만큼이다. 위치는 이어보기 힌트로만 남는다.
		this.student.postJson(heartbeats(this.course, session), "{\"seq\":1,\"positionSec\":600,\"playing\":true}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.creditedSec").value(60))
			.andExpect(jsonPath("$.progress.status").value("under_required"))
			.andExpect(jsonPath("$.progress.progressPercent").value(50))
			.andExpect(jsonPath("$.progress.resumePositionSec").value(600));
		assertThat(credited(session)).isEqualTo(60);
	}

	@Test
	void twoDevicesAndTwoSessionsNeverEarnTheSameWallClockTimeTwice() throws Exception {
		ApiClient phone = new ApiClient(this.mvc, this.data.session(this.stu));
		long first = started(this.student);
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, first, 1).andExpect(jsonPath("$.creditedSec").value(60));

		this.clock.advance(Duration.ofSeconds(30));
		long second = started(phone);
		beat(this.student, first, 2).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("session_superseded"));
		beat(phone, second, 1).andExpect(jsonPath("$.outcome").value("too_early"));
		this.clock.advance(Duration.ofSeconds(60));
		beat(phone, second, 2).andExpect(jsonPath("$.creditedSec").value(60));
		beat(this.student, first, 3).andExpect(status().isConflict());

		// 다른 기기가 이미 적립한 구간(여기서는 원장에 직접 넣는다)과 겹치는 부분은 다시 적립하지 않는다.
		this.jdbc.sql("""
				INSERT INTO learning_credit_ledger (learning_session_id, from_at, to_at, credited_sec, in_period_yn)
				VALUES (:s, :from, :to, 40, 1)
				""").param("s", first).param("from", this.t0.plusSeconds(150)).param("to", this.t0.plusSeconds(190)).update();
		this.clock.advance(Duration.ofSeconds(60));
		beat(phone, second, 3).andExpect(jsonPath("$.creditedSec").value(20));

		List<LocalDateTime[]> intervals = this.jdbc.sql("""
				SELECT l.from_at, l.to_at FROM learning_credit_ledger l
				  JOIN learning_sessions s ON s.learning_session_id = l.learning_session_id
				 WHERE s.user_id = :u ORDER BY l.from_at
				""").param("u", this.stu).query((rs, n) -> new LocalDateTime[] { rs.getObject(1, LocalDateTime.class),
				rs.getObject(2, LocalDateTime.class) }).list();
		for (int i = 1; i < intervals.size(); i++) {
			assertThat(intervals.get(i)[0]).isAfterOrEqualTo(intervals.get(i - 1)[1]);
		}
		assertThat(Duration.between(this.t0, this.clock.now()).toSeconds()).isEqualTo(210);
		assertThat(this.jdbc.sql("""
				SELECT SUM(l.credited_sec) FROM learning_credit_ledger l
				  JOIN learning_sessions s ON s.learning_session_id = l.learning_session_id WHERE s.user_id = :u
				""").param("u", this.stu).query(Integer.class).single()).isEqualTo(60 + 60 + 40 + 20);
	}

	@Test
	void parallelStartsLeaveOneActiveSession() throws Exception {
		ApiClient phone = new ApiClient(this.mvc, this.data.session(this.stu));
		List<Integer> statuses = parallel(2, (i) -> () -> (i == 0 ? this.student : phone).post(start(this.course, this.item))
			.andReturn()
			.getResponse()
			.getStatus());

		assertThat(statuses).containsOnly(201);
		assertThat(this.jdbc.sql("SELECT state FROM learning_sessions WHERE user_id = :u ORDER BY learning_session_id")
			.param("u", this.stu)
			.query(String.class)
			.list()).containsExactly("superseded", "active");
	}

	@Test
	void gapLongerThanTwoIntervalsIsNotCreditedAndLeaseEndsTheSession() throws Exception {
		long session = started(this.student);
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, session, 1).andExpect(jsonPath("$.creditedSec").value(60));
		this.clock.advance(Duration.ofSeconds(121));
		beat(this.student, session, 2).andExpect(jsonPath("$.outcome").value("gap"))
			.andExpect(jsonPath("$.creditedSec").value(0));
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, session, 3).andExpect(jsonPath("$.creditedSec").value(60));

		this.clock.advance(Duration.ofSeconds(181));
		beat(this.student, session, 4).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("session_closed"));
		assertThat(this.jdbc.sql("SELECT state, ended_at FROM learning_sessions WHERE learning_session_id = :s")
			.param("s", session)
			.query((rs, n) -> rs.getString(1) + " " + rs.getObject(2, LocalDateTime.class))
			.single()).isEqualTo("lease_expired " + this.t0.plusSeconds(241));
		assertThat(credited(session)).isEqualTo(120);
	}

	@Test
	void creditStopsExactlyAtCloseAtWhilePlaybackContinues() throws Exception {
		LocalDateTime closeAt = this.t0.plusSeconds(90);
		this.jdbc.sql("UPDATE learning_activities SET close_at = :close WHERE activity_id = :a")
			.param("close", closeAt)
			.param("a", this.activity)
			.update();
		JsonNode started = ApiClient.json(this.student.post(start(this.course, this.item)).andExpect(status().isCreated()));
		assertThat(LocalDateTime.parse(started.get("creditUntil").asString())).isEqualTo(closeAt);
		long session = started.get("sessionId").asLong();
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, session, 1).andExpect(jsonPath("$.creditedSec").value(60))
			.andExpect(jsonPath("$.creditOpen").value(true));
		this.clock.advance(Duration.ofSeconds(30));
		beat(this.student, session, 2).andExpect(jsonPath("$.creditedSec").value(30))
			.andExpect(jsonPath("$.creditOpen").value(false));
		this.clock.advance(Duration.ofSeconds(60));
		MockHttpServletResponse after = beat(this.student, session, 3)
			.andExpect(jsonPath("$.outcome").value("window_closed"))
			.andExpect(jsonPath("$.creditedSec").value(0))
			.andExpect(jsonPath("$.playback.manifestUrl").exists())
			.andReturn()
			.getResponse();
		assertThat(after.getHeaders("Set-Cookie")).hasSize(3);
		this.clock.advance(Duration.ofSeconds(30));
		this.student.post(end(this.course, session)).andExpect(jsonPath("$.creditedSec").value(0));

		assertThat(credited(session)).isEqualTo(90);
		assertThat(this.jdbc.sql("SELECT MAX(to_at) FROM learning_credit_ledger WHERE learning_session_id = :s")
			.param("s", session)
			.query(LocalDateTime.class)
			.single()).isEqualTo(closeAt);
	}

	@Test
	void losingAccessStopsCreditAndCookiesButKeepsEarnedTime() throws Exception {
		long session = started(this.student);
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, session, 1).andExpect(jsonPath("$.creditedSec").value(60));
		setPublished(false);
		this.clock.advance(Duration.ofSeconds(60));
		MockHttpServletResponse hidden = beat(this.student, session, 2).andExpect(status().isNotFound())
			.andReturn()
			.getResponse();
		assertThat(hidden.getHeaders("Set-Cookie")).noneMatch((cookie) -> cookie.contains("Path=/vod/"));
		assertThat(this.jdbc.sql("SELECT CONCAT(state, ' ', end_reason) FROM learning_sessions WHERE learning_session_id = :s")
			.param("s", session)
			.query(String.class)
			.single()).isEqualTo("ended forced");
		setPublished(true);
		beat(this.student, session, 3).andExpect(status().isConflict());
		assertThat(credited(session)).isEqualTo(60);

		long next = started(this.student);
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, next, 1).andExpect(jsonPath("$.creditedSec").value(60));
		this.jdbc.sql("UPDATE course_enrollments SET enrollment_status = 'CANCELLED' WHERE course_id = :c AND user_id = :u")
			.param("c", this.course)
			.param("u", this.stu)
			.update();
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, next, 2).andExpect(status().isNotFound());
		this.student.post(start(this.course, this.item)).andExpect(status().isNotFound());
		assertThat(credited(next)).isEqualTo(60);

		this.data.enroll(this.course, this.stu);
		long third = started(this.student);
		this.jdbc.sql("UPDATE user_sessions SET revoked_at = CURRENT_TIMESTAMP(3) WHERE user_id = :u")
			.param("u", this.stu)
			.update();
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, third, 1).andExpect(status().isUnauthorized());
		assertThat(credited(third)).isZero();
	}

	@Test
	void sessionsAreBoundToTheirOwnerCourseAndState() throws Exception {
		long session = started(this.student);
		long classmate = this.data.user("STUDENT");
		this.data.enroll(this.course, classmate);
		ApiClient other = new ApiClient(this.mvc, this.data.session(classmate));
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.stu);
		this.clock.advance(Duration.ofSeconds(60));

		beat(other, session, 1).andExpect(status().isNotFound());
		other.post(end(this.course, session)).andExpect(status().isNotFound());
		this.student.postJson(heartbeats(otherCourse, session), "{\"seq\":1}").andExpect(status().isNotFound());
		this.student.post(end(otherCourse, session)).andExpect(status().isNotFound());
		beat(this.student, session + 1_000_000, 1).andExpect(status().isNotFound());
		this.mvc.perform(post(heartbeats(this.course, session)).cookie(new Cookie("ASTRA_SESSION", this.data.session(this.stu)))
			.contentType("application/json")
			.content("{\"seq\":1}")).andExpect(status().isForbidden());
		new ApiClient(this.mvc, null).postJson(heartbeats(this.course, session), "{\"seq\":1}")
			.andExpect(status().isUnauthorized());
		assertThat(credited(session)).isZero();

		ApiClient outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		outsider.post(start(this.course, this.item)).andExpect(status().isNotFound());
		this.student.post(start(otherCourse, this.item)).andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).post(start(this.course, this.item)).andExpect(status().isUnauthorized());
		long noVideo = this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind) VALUES (:a, :seq, '링크', 'link')
				""", Map.of("a", this.activity, "seq", LINK_SEQ.incrementAndGet() + 100_000));
		this.student.post(start(this.course, noVideo)).andExpect(status().isNotFound());
		this.jdbc.sql("UPDATE learning_activities SET open_at = :open WHERE activity_id = :a")
			.param("open", this.t0.plusDays(1))
			.param("a", this.activity)
			.update();
		this.student.post(start(this.course, this.item)).andExpect(status().isNotFound());

		// 담당자는 시작 전 활동도 미리 볼 수 있지만 적립하지 않는다.
		long preview = ApiClient.json(this.professor.post(start(this.course, this.item))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.creditable").value(false))).get("sessionId").asLong();
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.professor, preview, 1).andExpect(jsonPath("$.outcome").value("not_creditable"))
			.andExpect(jsonPath("$.creditedSec").value(0))
			.andExpect(jsonPath("$.playback.manifestUrl").exists());
		assertThat(credited(preview)).isZero();
	}

	@Test
	void workerClosesExpiredLeasesOnceEvenWithTwoWorkers() throws Exception {
		long session = started(this.student);
		this.clock.advance(Duration.ofSeconds(60));
		beat(this.student, session, 1).andExpect(jsonPath("$.creditedSec").value(60));
		this.clock.advance(Duration.ofSeconds(180));
		this.service.expireLeases();
		assertThat(state(session)).as("lease 경계 시각에는 아직 닫지 않는다").isEqualTo("active");
		this.clock.advance(Duration.ofSeconds(1));
		int expected = this.jdbc.sql("""
				SELECT COUNT(*) FROM learning_sessions
				 WHERE state = 'active' AND TIMESTAMPADD(SECOND, lease_sec, COALESCE(last_heartbeat_at, started_at)) < :now
				""").param("now", this.clock.now()).query(Integer.class).single();

		List<Integer> closed = parallel(2, (i) -> this.service::expireLeases);

		assertThat(closed.get(0) + closed.get(1)).isEqualTo(expected);
		assertThat(state(session)).isEqualTo("lease_expired");
		assertThat(this.jdbc.sql("SELECT ended_at FROM learning_sessions WHERE learning_session_id = :s")
			.param("s", session)
			.query(LocalDateTime.class)
			.single()).isEqualTo(this.t0.plusSeconds(60));
		beat(this.student, session, 2).andExpect(status().isConflict());
		this.student.post(end(this.course, session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("lease_expired"));
		assertThat(credited(session)).isEqualTo(60);
	}

	@Test
	void attendanceIsRecordedOnceUnderConcurrentHeartbeats() throws Exception {
		long attendance = attendanceSession(this.activity);
		this.jdbc.sql("UPDATE online_lecture_items SET required_sec = 60 WHERE online_lecture_item_id = :i")
			.param("i", this.item)
			.update();
		long session = started(this.student);
		this.clock.advance(Duration.ofSeconds(60));

		List<Integer> credited = parallel(8, (i) -> () -> ApiClient.json(beat(this.student, session, i + 1))
			.get("creditedSec")
			.asInt());

		assertThat(credited).containsOnlyOnce(60).filteredOn((c) -> c != 60).containsOnly(0);
		assertThat(credited(session)).isEqualTo(60);
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM attendance_records WHERE attendance_session_id = :s AND user_id = :u AND status = '출석'
				""").param("s", attendance).param("u", this.stu).query(Integer.class).single()).isEqualTo(1);
		assertThat(this.jdbc.sql("""
				SELECT met_at FROM online_lecture_item_progress WHERE user_id = :u AND online_lecture_item_id = :i
				""").param("u", this.stu).param("i", this.item).query(LocalDateTime.class).single())
			.isEqualTo(this.t0.plusSeconds(60));
	}

	private long started(ApiClient client) throws Exception {
		return ApiClient.json(client.post(start(this.course, this.item)).andExpect(status().isCreated()))
			.get("sessionId")
			.asLong();
	}

	private ResultActions beat(ApiClient client, long session, long seq) throws Exception {
		return client.postJson(heartbeats(this.course, session), "{\"seq\":" + seq + "}");
	}

	private String state(long session) {
		return this.jdbc.sql("SELECT state FROM learning_sessions WHERE learning_session_id = :s")
			.param("s", session)
			.query(String.class)
			.single();
	}

	private int credited(long session) {
		return this.jdbc.sql("SELECT COALESCE(SUM(credited_sec), 0) FROM learning_credit_ledger WHERE learning_session_id = :s")
			.param("s", session)
			.query(Integer.class)
			.single();
	}

	private void setPublished(boolean published) {
		this.jdbc.sql("UPDATE learning_activities SET published_yn = :p WHERE activity_id = :a")
			.param("p", published)
			.param("a", this.activity)
			.update();
	}

	private long lectureItem(long activityId, Integer durationSec, Integer requiredSec) {
		Map<String, Object> params = new java.util.HashMap<>();
		params.put("a", activityId);
		params.put("seq", LINK_SEQ.incrementAndGet());
		params.put("path", "vod/lecture-" + UUID.randomUUID().toString().substring(0, 8) + "/main.m3u8");
		params.put("duration", durationSec);
		params.put("required", requiredSec);
		return this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path, duration_sec, required_sec)
				VALUES (:a, :seq, '9주차 강의', 'video', :path, :duration, :required)
				""", params);
	}

	private long attendanceSession(long activityId) {
		long week = this.jdbc.sql("SELECT course_week_id FROM learning_activities WHERE activity_id = :a")
			.param("a", activityId)
			.query(Long.class)
			.single();
		long id = this.data.insert("""
				INSERT INTO attendance_sessions (course_id, course_week_id, session_no, attendance_method)
				VALUES (:c, :w, 1, '온라인')
				""", Map.of("c", this.course, "w", week));
		this.jdbc.sql("UPDATE learning_activities SET attendance_session_id = :s WHERE activity_id = :a")
			.param("s", id)
			.param("a", activityId)
			.update();
		return id;
	}

	/** 모든 작업을 같은 순간에 출발시켜 실제 thread로 실행한다. */
	static <T> List<T> parallel(int threads, IntFunction<Callable<T>> task) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch go = new CountDownLatch(1);
		try {
			List<Future<T>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				Callable<T> callable = task.apply(i);
				futures.add(pool.submit(() -> {
					go.await();
					return callable.call();
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

	static String start(long courseId, long itemId) {
		return "/api/courses/" + courseId + "/lecture-items/" + itemId + "/learning-sessions";
	}

	static String heartbeats(long courseId, long sessionId) {
		return "/api/courses/" + courseId + "/learning-sessions/" + sessionId + "/heartbeats";
	}

	static String end(long courseId, long sessionId) {
		return "/api/courses/" + courseId + "/learning-sessions/" + sessionId + "/end";
	}

}
