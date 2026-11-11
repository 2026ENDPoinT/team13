package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assumptions.assumeThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.shared.audit.AuditRows;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

/**
 * 스마트출석(PIN/QR, SEC-06, SEC-12, ADR-0030). 시계는 테스트가 움직인다. 기본 정책은 진행 10분, 지각 기준 5분, 틀린 횟수 5번,
 * QR 교체 15초와 유예 5초다.
 */
class SmartAttendanceApiTests extends IntegrationTest {

	private static final AtomicInteger LINK_SEQ = new AtomicInteger(500_000);

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestLearningClock clock;

	long course;

	long otherCourse;

	long prof;

	long stu;

	long mate;

	long third;

	String profSession;

	ApiClient professor;

	ApiClient student;

	ApiClient classmate;

	ApiClient thirdStudent;

	ApiClient outsider;

	ApiClient anonymous;

	long session;

	LocalDateTime t0;

	@BeforeEach
	void course() throws Exception {
		this.course = this.data.course();
		this.otherCourse = this.data.course();
		this.prof = this.data.user("PROFESSOR");
		this.stu = this.data.user("STUDENT");
		this.mate = this.data.user("STUDENT");
		this.third = this.data.user("STUDENT");
		this.data.instructor(this.course, this.prof);
		this.data.instructor(this.otherCourse, this.prof);
		for (long user : List.of(this.stu, this.mate, this.third)) {
			this.data.enroll(this.course, user);
		}
		this.data.enroll(this.otherCourse, this.stu);
		this.profSession = this.data.session(this.prof);
		this.professor = new ApiClient(this.mvc, this.profSession);
		this.student = new ApiClient(this.mvc, this.data.session(this.stu));
		this.classmate = new ApiClient(this.mvc, this.data.session(this.mate));
		this.thirdStudent = new ApiClient(this.mvc, this.data.session(this.third));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		this.anonymous = new ApiClient(this.mvc, null);
		this.session = newSession(1);
		this.t0 = this.clock.freeze();
	}

	@AfterEach
	void release() {
		this.clock.release();
	}

	@Test
	void pinRoundConfirmsOnceWithStatusFromServerClock() throws Exception {
		JsonNode round = ApiClient.json(open(this.session, "{\"method\":\"pin\"}").andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.state").value("open"))
			.andExpect(jsonPath("$.pinLength").value(6))
			.andExpect(jsonPath("$.counts.targets").value(3)));
		assertThat(time(round, "lateAt")).isEqualTo(this.t0.plusMinutes(5));
		assertThat(time(round, "endsAt")).isEqualTo(this.t0.plusMinutes(10));
		long id = round.get("roundId").asLong();
		String pin = round.get("pin").asString();
		assertThat(pin).matches("[0-9]{6}");

		this.student.get(me(id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("pending"))
			.andExpect(jsonPath("$.failuresLeft").value(5))
			.andExpect(jsonPath("$.attendance").doesNotExist())
			.andExpect(jsonPath("$.pin").doesNotExist());
		check(this.student, id, "{\"pin\":\"" + wrong(pin) + "\"}").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("bad_pin"));
		this.student.get(me(id)).andExpect(jsonPath("$.failuresLeft").value(4));

		this.clock.advance(Duration.ofMinutes(5).minusMillis(1));
		ResultActions confirmed = check(this.student, id, "{\"pin\":\"" + pin + "\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("confirmed"))
			.andExpect(jsonPath("$.attendance.status").value("present"))
			.andExpect(jsonPath("$.attendance.source").value("smart"));
		assertThat(AuditRows.single(this.jdbc, confirmed).result()).isEqualTo("ok");
		// 다시 보내도 같은 결과이고 기록은 하나다.
		check(this.student, id, "{\"pin\":\"" + pin + "\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("confirmed"));
		assertThat(records(this.session, this.stu)).isEqualTo(1);
		assertThat(attempts(id, this.stu, "ok")).isEqualTo(1);
		assertThat(attempts(id, this.stu, "duplicate")).isEqualTo(1);
		// 다른 학생의 상태는 그대로다.
		this.classmate.get(me(id)).andExpect(jsonPath("$.state").value("pending"));

		// 지각 기준 시각부터는 지각이다.
		this.clock.set(this.t0.plusMinutes(5));
		check(this.classmate, id, "{\"pin\":\"" + pin + "\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.attendance.status").value("late"));
		assertThat(decidedAt(this.session, this.mate)).isEqualTo(this.t0.plusMinutes(5));
		this.professor.get(roundPath(id))
			.andExpect(jsonPath("$.pin").value(pin))
			.andExpect(jsonPath("$.counts.confirmed").value(2))
			.andExpect(jsonPath("$.counts.present").value(1))
			.andExpect(jsonPath("$.counts.late").value(1))
			.andExpect(jsonPath("$.counts.failedAttempts").value(1));
		this.student.get(AttendanceApiTests.attendancePath(this.course))
			.andExpect(jsonPath("$.weeks[0].sessions[0].record.status").value("present"))
			.andExpect(jsonPath("$.weeks[0].sessions[0].record.source").value("smart"));
	}

	@Test
	void expiredClosedAndCancelledRoundsAcceptNothing() throws Exception {
		JsonNode expiring = ApiClient.json(open(this.session, "{\"method\":\"pin\",\"minutes\":2,\"lateAfterMinutes\":1}"));
		long expiringId = expiring.get("roundId").asLong();
		this.clock.set(this.t0.plusMinutes(2));
		check(this.student, expiringId, "{\"pin\":\"" + expiring.get("pin").asString() + "\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("round_expired"));
		this.student.get(me(expiringId)).andExpect(jsonPath("$.state").value("expired"));
		this.professor.get(roundPath(expiringId))
			.andExpect(jsonPath("$.state").value("expired"))
			.andExpect(jsonPath("$.pin").doesNotExist());
		assertThat(records(this.session, this.stu)).isZero();

		// 시간이 지난 회차가 있는 차시에도 새 회차를 연다.
		JsonNode closing = ApiClient.json(open(this.session, "{\"method\":\"pin\"}").andExpect(status().isCreated()));
		long closingId = closing.get("roundId").asLong();
		this.professor.post(roundPath(closingId) + "/close")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("closed"))
			.andExpect(jsonPath("$.pin").doesNotExist());
		this.professor.post(roundPath(closingId) + "/close").andExpect(status().isOk());
		check(this.student, closingId, "{\"pin\":\"" + closing.get("pin").asString() + "\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("round_closed"));
		this.student.get(me(closingId)).andExpect(jsonPath("$.state").value("closed"));
		assertThat(this.jdbc.sql("SELECT round_secret IS NULL FROM smart_attendance_rounds WHERE smart_attendance_round_id = :r")
			.param("r", closingId)
			.query(Boolean.class)
			.single()).isTrue();

		long other = newSession(2);
		JsonNode cancelling = ApiClient.json(open(other, "{\"method\":\"pin\"}"));
		long cancellingId = cancelling.get("roundId").asLong();
		String pin = cancelling.get("pin").asString();
		check(this.student, cancellingId, "{\"pin\":\"" + pin + "\"}").andExpect(status().isOk());
		assertThat(records(other, this.stu)).isEqualTo(1);
		this.professor.post(roundPath(cancellingId) + "/cancel")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("cancelled"));
		// 취소한 회차는 없던 회차다. 확정됐던 학생도 출석 완료로 보이지 않는다(SEC-06).
		assertThat(records(other, this.stu)).isZero();
		this.student.get(me(cancellingId))
			.andExpect(jsonPath("$.state").value("cancelled"))
			.andExpect(jsonPath("$.attendance").doesNotExist());
		check(this.student, cancellingId, "{\"pin\":\"" + pin + "\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("round_cancelled"));
		check(this.classmate, cancellingId, "{\"pin\":\"" + pin + "\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("round_cancelled"));
		this.professor.post(roundPath(cancellingId) + "/close").andExpect(status().isConflict());
		assertThat(records(other, this.mate)).isZero();
	}

	@Test
	void pinGuessingLocksTheStudentOutOfTheRound() throws Exception {
		JsonNode round = ApiClient.json(open(this.session, "{\"method\":\"pin\"}"));
		long id = round.get("roundId").asLong();
		String pin = round.get("pin").asString();
		for (int i = 0; i < 5; i++) {
			check(this.student, id, "{\"pin\":\"" + wrong(pin, i) + "\"}").andExpect(status().isUnprocessableContent());
		}
		ResultActions locked = check(this.student, id, "{\"pin\":\"" + pin + "\"}")
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("attempts_locked"));
		assertThat(AuditRows.single(this.jdbc, locked).result()).isEqualTo("locked");
		this.student.get(me(id)).andExpect(jsonPath("$.state").value("locked"));
		assertThat(records(this.session, this.stu)).isZero();
		this.professor.get(roundPath(id))
			.andExpect(jsonPath("$.counts.lockedStudents").value(1))
			.andExpect(jsonPath("$.counts.failedAttempts").value(5));
		// 잠금은 회차·학생 단위다.
		check(this.classmate, id, "{\"pin\":\"" + pin + "\"}").andExpect(status().isOk());
		// 다른 회차에서는 다시 제출할 수 있다.
		JsonNode next = ApiClient.json(open(newSession(2), "{\"method\":\"pin\"}"));
		check(this.student, next.get("roundId").asLong(), "{\"pin\":\"" + next.get("pin").asString() + "\"}")
			.andExpect(status().isOk());
	}

	@Test
	void endedRoundTellsALockedStudentFromOneWhoNeverSubmitted() throws Exception {
		JsonNode round = ApiClient.json(open(this.session, "{\"method\":\"pin\",\"minutes\":2,\"lateAfterMinutes\":1}"));
		long id = round.get("roundId").asLong();
		String pin = round.get("pin").asString();
		// PIN은 회차 동안 바뀌지 않는다. 교체 시각은 QR 회차에만 있다.
		this.clock.advance(Duration.ofSeconds(90));
		this.professor.get(roundPath(id))
			.andExpect(jsonPath("$.pin").value(pin))
			.andExpect(jsonPath("$.qrRefreshAt").doesNotExist());
		for (int i = 0; i < 5; i++) {
			check(this.student, id, "{\"pin\":\"" + wrong(pin, i) + "\"}").andExpect(status().isUnprocessableContent());
		}
		check(this.classmate, id, "{\"pin\":\"" + wrong(pin) + "\"}").andExpect(status().isUnprocessableContent());
		this.student.get(me(id))
			.andExpect(jsonPath("$.state").value("locked"))
			.andExpect(jsonPath("$.lockedOut").value(true))
			.andExpect(jsonPath("$.failedAttempts").value(5));

		this.clock.set(this.t0.plusMinutes(2));
		this.student.get(me(id))
			.andExpect(jsonPath("$.state").value("expired"))
			.andExpect(jsonPath("$.lockedOut").value(true))
			.andExpect(jsonPath("$.failedAttempts").value(5));
		this.classmate.get(me(id))
			.andExpect(jsonPath("$.state").value("expired"))
			.andExpect(jsonPath("$.lockedOut").value(false))
			.andExpect(jsonPath("$.failedAttempts").value(1));
		this.thirdStudent.get(me(id))
			.andExpect(jsonPath("$.state").value("expired"))
			.andExpect(jsonPath("$.lockedOut").value(false))
			.andExpect(jsonPath("$.failedAttempts").value(0));
	}

	@Test
	void qrTokensRotateAndOldTokensExpireAfterGrace() throws Exception {
		JsonNode round = ApiClient.json(open(this.session, "{\"method\":\"qr\"}")
			.andExpect(jsonPath("$.pin").doesNotExist()));
		assertThat(time(round, "qrRefreshAt")).isEqualTo(this.t0.plusSeconds(15));
		long id = round.get("roundId").asLong();
		String first = round.get("qrToken").asString();
		check(this.student, id, "{\"pin\":\"123456\"}").andExpect(status().isBadRequest());

		// 바뀐 뒤 유예 시간(5초) 안에는 바로 앞 token을 받는다.
		this.clock.set(this.t0.plusSeconds(19));
		String second = ApiClient.json(this.professor.get(roundPath(id))).get("qrToken").asString();
		assertThat(second).isNotEqualTo(first);
		check(this.student, id, "{\"qrToken\":\"" + first + "\"}").andExpect(status().isOk())
			.andExpect(jsonPath("$.attendance.status").value("present"));
		// 유예가 끝나면 받지 않는다.
		this.clock.set(this.t0.plusSeconds(20));
		check(this.classmate, id, "{\"qrToken\":\"" + first + "\"}").andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("bad_qr"));
		check(this.classmate, id, "{\"qrToken\":\"" + second + "\"}").andExpect(status().isOk());

		// 아직 오지 않은 구간의 token, 다른 회차의 token, 형식이 틀린 token은 받지 않는다.
		byte[] secret = secret(id);
		SmartAttendanceProof.Scope scope = new SmartAttendanceProof.Scope(this.course, this.session, id, "qr");
		check(this.thirdStudent, id, "{\"qrToken\":\"" + SmartAttendanceProof.qrToken(secret, scope, 2) + "\"}")
			.andExpect(jsonPath("$.code").value("bad_qr"));
		JsonNode otherRound = ApiClient.json(open(newSession(2), "{\"method\":\"qr\"}"));
		check(this.thirdStudent, id, "{\"qrToken\":\"" + otherRound.get("qrToken").asString() + "\"}")
			.andExpect(jsonPath("$.code").value("bad_qr"));
		check(this.thirdStudent, id, "{\"qrToken\":\"1.AAAAAAAAAAAAAAAAAAAAAA\"}").andExpect(jsonPath("$.code").value("bad_qr"));
		// 같은 비밀값으로 만든 token이라도 다른 과목·차시·방식에 묶인 값은 맞지 않는다.
		check(this.thirdStudent, id, "{\"qrToken\":\"" + SmartAttendanceProof.qrToken(secret,
				new SmartAttendanceProof.Scope(this.otherCourse, this.session, id, "qr"), 1) + "\"}")
			.andExpect(jsonPath("$.code").value("bad_qr"));
		assertThat(records(this.session, this.third)).isZero();
		this.thirdStudent.get(me(id)).andExpect(jsonPath("$.failuresLeft").value(1));
	}

	@Test
	void proofsDoNotMoveAcrossUsersRoundsOrCourses() throws Exception {
		JsonNode first = ApiClient.json(open(this.session, "{\"method\":\"pin\"}"));
		long firstId = first.get("roundId").asLong();
		JsonNode second = ApiClient.json(open(newSession(2), "{\"method\":\"pin\"}"));
		long secondId = second.get("roundId").asLong();
		String pin = first.get("pin").asString();
		assumeThat(second.get("pin").asString()).isNotEqualTo(pin);
		// 요청으로 사용자나 회차 상태를 정할 수 없다(SC-02).
		check(this.student, firstId, "{\"pin\":\"" + pin + "\",\"userId\":" + this.mate + "}")
			.andExpect(status().isBadRequest());
		// 다른 회차의 PIN은 맞지 않는다.
		check(this.student, secondId, "{\"pin\":\"" + pin + "\"}").andExpect(jsonPath("$.code").value("bad_pin"));
		// 다른 과목 경로로는 회차가 없다.
		check(this.student, firstId, this.otherCourse, "{\"pin\":\"" + pin + "\"}").andExpect(status().isNotFound());
		this.student.get("/api/courses/" + this.otherCourse + "/smart-rounds/" + firstId + "/me")
			.andExpect(status().isNotFound());
		check(this.student, firstId, "{\"pin\":\"" + pin + "\"}").andExpect(status().isOk());
		// 한 학생의 확정은 다른 학생의 것이 되지 않는다.
		this.classmate.get(me(firstId)).andExpect(jsonPath("$.state").value("pending"));
		assertThat(records(this.session, this.mate)).isZero();
		assertThat(this.jdbc.sql("SELECT used_jti FROM smart_attendance_attempts WHERE smart_attendance_round_id = :r AND result = 'ok'")
			.param("r", firstId)
			.query(String.class)
			.list()).containsExactly("r" + firstId + "-u" + this.stu);
	}

	@Test
	void concurrentSubmitsEndInOneRecordPerStudent() throws Exception {
		JsonNode round = ApiClient.json(open(this.session, "{\"method\":\"pin\"}"));
		long id = round.get("roundId").asLong();
		String body = "{\"pin\":\"" + round.get("pin").asString() + "\"}";
		List<ApiClient> clients = List.of(this.student, this.classmate);

		List<Integer> statuses = LearningSessionApiTests.parallel(8,
				(i) -> () -> check(clients.get(i % 2), id, body).andReturn().getResponse().getStatus());

		assertThat(statuses).containsOnly(200);
		for (long user : List.of(this.stu, this.mate)) {
			assertThat(records(this.session, user)).isEqualTo(1);
			assertThat(attempts(id, user, "ok")).isEqualTo(1);
			assertThat(attempts(id, user, "duplicate")).isEqualTo(3);
		}
	}

	@Test
	void groupScopedRoundOnlyAcceptsMembers() throws Exception {
		long group = this.data.insert("INSERT INTO course_groups (course_id, group_type, group_name) VALUES (:c, '출석분반', 'A반')",
				Map.of("c", this.course));
		this.data.insert("INSERT INTO course_group_members (course_group_id, user_id) VALUES (:g, :u)",
				Map.of("g", group, "u", this.stu));
		long foreignGroup = this.data.insert(
				"INSERT INTO course_groups (course_id, group_type, group_name) VALUES (:c, '출석분반', 'B반')",
				Map.of("c", this.otherCourse));
		open(this.session, "{\"method\":\"pin\",\"groupId\":" + foreignGroup + "}").andExpect(status().isBadRequest());
		JsonNode round = ApiClient.json(open(this.session, "{\"method\":\"pin\",\"groupId\":" + group + "}")
			.andExpect(jsonPath("$.counts.targets").value(1)));
		long id = round.get("roundId").asLong();
		String body = "{\"pin\":\"" + round.get("pin").asString() + "\"}";
		this.classmate.get(me(id)).andExpect(jsonPath("$.state").value("not_target"));
		check(this.classmate, id, body).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_target"));
		check(this.student, id, body).andExpect(status().isOk());
		assertThat(records(this.session, this.mate)).isZero();
		this.classmate.get("/api/courses/" + this.course + "/smart-rounds/active")
			.andExpect(jsonPath("$.mine[0].state").value("not_target"));
	}

	@Test
	void roundsAreOnePerSessionAndValidated() throws Exception {
		open(this.session, "{\"method\":\"sms\"}").andExpect(status().isBadRequest());
		open(this.session, "{\"method\":\"pin\",\"minutes\":3,\"lateAfterMinutes\":4}").andExpect(status().isBadRequest());
		open(this.session, "{\"method\":\"pin\",\"minutes\":0}").andExpect(status().isBadRequest());
		open(this.session, "{\"method\":\"pin\"}").andExpect(status().isCreated());
		open(this.session, "{\"method\":\"qr\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("round_open"));
		// 휴강한 차시에는 열지 않고, 진행 중인 회차가 있는 차시는 휴강으로 바꾸지 않는다.
		this.professor
			.send(MockMvcRequestBuilders.put(AttendanceApiTests.sessionPath(this.course, this.session))
				.contentType(MediaType.APPLICATION_JSON)
				.content(AttendanceApiTests.sessionBody(1, 1, "\"cancelled\":true"))
				.header("If-Match", "\"1\""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("round_open"));
		long cancelled = newSession(2);
		this.professor
			.send(MockMvcRequestBuilders.put(AttendanceApiTests.sessionPath(this.course, cancelled))
				.contentType(MediaType.APPLICATION_JSON)
				.content(AttendanceApiTests.sessionBody(1, 2, "\"cancelled\":true"))
				.header("If-Match", "\"1\""))
			.andExpect(status().isOk());
		open(cancelled, "{\"method\":\"pin\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("session_cancelled"));
		this.professor.get("/api/courses/" + this.course + "/smart-rounds/active")
			.andExpect(jsonPath("$.rounds.length()").value(1))
			.andExpect(jsonPath("$.mine.length()").value(0));
	}

	@Test
	void onlineSmartAndManualAttendanceNeverDowngradeOrDuplicate() throws Exception {
		// 1. 온라인 학습으로 출석한 차시에 스마트출석을 해도 기록은 그대로 하나다(ADR-0014).
		long onlineItem = linkedLecture(this.session);
		learnUntilMet(onlineItem);
		assertThat(record(this.session, this.stu)).containsExactly("출석", "online");
		JsonNode round = ApiClient.json(open(this.session, "{\"method\":\"pin\"}"));
		check(this.student, round.get("roundId").asLong(), "{\"pin\":\"" + round.get("pin").asString() + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("confirmed"))
			.andExpect(jsonPath("$.attendance.source").value("online"));
		assertThat(record(this.session, this.stu)).containsExactly("출석", "online");
		assertThat(records(this.session, this.stu)).isEqualTo(1);

		// 2. 스마트출석 지각 뒤에 온라인 학습이 출석인정을 채워도 지각은 바뀌지 않는다.
		long late = newSession(2);
		long lateItem = linkedLecture(late);
		JsonNode lateRound = ApiClient.json(open(late, "{\"method\":\"pin\"}"));
		this.clock.advance(Duration.ofMinutes(6));
		check(this.student, lateRound.get("roundId").asLong(), "{\"pin\":\"" + lateRound.get("pin").asString() + "\"}")
			.andExpect(jsonPath("$.attendance.status").value("late"));
		learnUntilMet(lateItem);
		assertThat(record(late, this.stu)).containsExactly("지각", "smart");
		assertThat(records(late, this.stu)).isEqualTo(1);

		// 3. 담당자가 정한 상태는 스마트출석과 온라인 학습이 바꾸지 않는다.
		long manual = newSession(3);
		long manualItem = linkedLecture(manual);
		AttendanceApiTests.setRecord(this.professor, this.course, manual, this.stu, "\"0\"", "absent", "무단 결석")
			.andExpect(status().isOk());
		JsonNode manualRound = ApiClient.json(open(manual, "{\"method\":\"pin\"}"));
		check(this.student, manualRound.get("roundId").asLong(),
				"{\"pin\":\"" + manualRound.get("pin").asString() + "\"}")
			.andExpect(jsonPath("$.attendance.status").value("absent"))
			.andExpect(jsonPath("$.attendance.source").value("manual"));
		learnUntilMet(manualItem);
		assertThat(record(manual, this.stu)).containsExactly("결석", "manual");
		// 온라인·스마트가 만든 기록을 담당자가 고치면 version이 맞아야 한다. 자동 판정도 version을 올린다.
		AttendanceApiTests.setRecord(this.professor, this.course, this.session, this.stu, "\"0\"", "absent", "확인")
			.andExpect(status().isConflict());
	}

	@Test
	void authorizationMatrix() throws Exception {
		String body = "{\"method\":\"pin\"}";
		for (ApiClient other : List.of(this.student, this.outsider)) {
			open(other, this.course, this.session, body).andExpect(status().isNotFound());
		}
		open(this.anonymous, this.course, this.session, body).andExpect(status().isUnauthorized());
		open(this.professor, this.otherCourse, this.session, body).andExpect(status().isNotFound());
		Cookie cookie = new Cookie("ASTRA_SESSION", this.profSession);
		this.mvc.perform(MockMvcRequestBuilders.post(openPath(this.course, this.session))
			.cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)).andExpect(status().isForbidden());
		JsonNode round = ApiClient.json(open(this.session, body).andExpect(status().isCreated()));
		long id = round.get("roundId").asLong();
		String pin = "{\"pin\":\"" + round.get("pin").asString() + "\"}";

		// 회차 화면(PIN 포함)은 담당자에게만 있다.
		this.student.get(roundPath(id)).andExpect(status().isNotFound());
		this.outsider.get(roundPath(id)).andExpect(status().isNotFound());
		this.professor.get("/api/courses/" + this.otherCourse + "/smart-rounds/" + id).andExpect(status().isNotFound());
		this.student.get("/api/courses/" + this.course + "/smart-rounds/active")
			.andExpect(jsonPath("$.rounds.length()").value(0))
			.andExpect(jsonPath("$.mine[0].state").value("pending"))
			.andExpect(jsonPath("$.mine[0].pin").doesNotExist());
		this.outsider.get("/api/courses/" + this.course + "/smart-rounds/active").andExpect(status().isNotFound());
		this.anonymous.get("/api/courses/" + this.course + "/smart-rounds/active").andExpect(status().isUnauthorized());
		this.professor.get(me(id)).andExpect(status().isNotFound());
		this.outsider.get(me(id)).andExpect(status().isNotFound());
		this.anonymous.get(me(id)).andExpect(status().isUnauthorized());

		// 제출은 그 과목 학생만, CSRF token과 함께.
		check(this.professor, id, pin).andExpect(status().isNotFound());
		check(this.outsider, id, pin).andExpect(status().isNotFound());
		check(this.anonymous, id, pin).andExpect(status().isUnauthorized());
		this.mvc.perform(MockMvcRequestBuilders.post(roundPath(id) + "/check")
			.cookie(new Cookie("ASTRA_SESSION", this.data.session(this.stu)))
			.contentType(MediaType.APPLICATION_JSON)
			.content(pin)).andExpect(status().isForbidden());
		assertThat(records(this.session, this.stu)).isZero();

		// 학생은 회차를 닫거나 취소하지 못한다(LEARN-10).
		for (ApiClient other : List.of(this.student, this.outsider)) {
			other.post(roundPath(id) + "/close").andExpect(status().isNotFound());
			other.post(roundPath(id) + "/cancel").andExpect(status().isNotFound());
		}
		this.mvc.perform(MockMvcRequestBuilders.post(roundPath(id) + "/cancel").cookie(cookie))
			.andExpect(status().isForbidden());
		this.student.get(me(id)).andExpect(jsonPath("$.state").value("pending"));
		check(this.student, id, pin).andExpect(status().isOk());
	}

	private long newSession(int no) throws Exception {
		return AttendanceApiTests.sessionId(this.professor
			.postJson(AttendanceApiTests.sessions(this.course), AttendanceApiTests.sessionBody(1, no, ""))
			.andExpect(status().isCreated()));
	}

	private ResultActions open(long sessionId, String body) throws Exception {
		return open(this.professor, this.course, sessionId, body);
	}

	private ResultActions open(ApiClient client, long courseId, long sessionId, String body) throws Exception {
		return client.postJson(openPath(courseId, sessionId), body);
	}

	private ResultActions check(ApiClient client, long roundId, String body) throws Exception {
		return check(client, roundId, this.course, body);
	}

	private ResultActions check(ApiClient client, long roundId, long courseId, String body) throws Exception {
		return client.postJson("/api/courses/" + courseId + "/smart-rounds/" + roundId + "/check", body);
	}

	private String roundPath(long roundId) {
		return "/api/courses/" + this.course + "/smart-rounds/" + roundId;
	}

	private String me(long roundId) {
		return roundPath(roundId) + "/me";
	}

	private static String openPath(long courseId, long sessionId) {
		return AttendanceApiTests.sessionPath(courseId, sessionId) + "/smart-rounds";
	}

	private static String wrong(String pin) {
		return wrong(pin, 0);
	}

	// 맞는 PIN과 다른 같은 길이의 PIN.
	private static String wrong(String pin, int n) {
		char[] digits = pin.toCharArray();
		digits[n % digits.length] = (char) ('0' + (digits[n % digits.length] - '0' + 1) % 10);
		return new String(digits);
	}

	private static LocalDateTime time(JsonNode node, String field) {
		return LocalDateTime.parse(node.get(field).asString());
	}

	private byte[] secret(long roundId) {
		return this.jdbc.sql("SELECT round_secret FROM smart_attendance_rounds WHERE smart_attendance_round_id = :r")
			.param("r", roundId)
			.query(byte[].class)
			.single();
	}

	private int records(long sessionId, long userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM attendance_records WHERE attendance_session_id = :s AND user_id = :u")
			.param("s", sessionId)
			.param("u", userId)
			.query(Integer.class)
			.single();
	}

	private List<String> record(long sessionId, long userId) {
		return this.jdbc.sql("SELECT status, source FROM attendance_records WHERE attendance_session_id = :s AND user_id = :u")
			.param("s", sessionId)
			.param("u", userId)
			.query((rs, n) -> List.of(rs.getString(1), rs.getString(2)))
			.single();
	}

	private LocalDateTime decidedAt(long sessionId, long userId) {
		return this.jdbc.sql("SELECT decided_at FROM attendance_records WHERE attendance_session_id = :s AND user_id = :u")
			.param("s", sessionId)
			.param("u", userId)
			.query(LocalDateTime.class)
			.single();
	}

	private int attempts(long roundId, long userId, String result) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM smart_attendance_attempts
				 WHERE smart_attendance_round_id = :r AND user_id = :u AND result = :result
				""").param("r", roundId).param("u", userId).param("result", result).query(Integer.class).single();
	}

	// 출석 차시에 연결한 영상 활동과 출석인정 60초짜리 콘텐츠.
	private long linkedLecture(long sessionId) {
		long activity = this.data.activity(this.course, "lecture_weeks", true, null, null);
		this.jdbc.sql("UPDATE learning_activities SET attendance_session_id = :s WHERE activity_id = :a")
			.param("s", sessionId)
			.param("a", activity)
			.update();
		Map<String, Object> params = new HashMap<>();
		params.put("a", activity);
		params.put("seq", LINK_SEQ.incrementAndGet());
		params.put("path", "vod/lecture-" + UUID.randomUUID().toString().substring(0, 8) + "/main.m3u8");
		return this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path, duration_sec, required_sec)
				VALUES (:a, :seq, '강의', 'video', :path, 600, 60)
				""", params);
	}

	// 학습 session을 열고 60초 뒤 하트비트로 출석인정을 채운다(ADR-0014).
	private void learnUntilMet(long itemId) throws Exception {
		long learning = ApiClient.json(this.student.post(LearningSessionApiTests.start(this.course, itemId))
			.andExpect(status().isCreated())).get("sessionId").asLong();
		this.clock.advance(Duration.ofSeconds(60));
		this.student.postJson(LearningSessionApiTests.heartbeats(this.course, learning), "{\"seq\":1}")
			.andExpect(status().isOk());
		assertThat(this.jdbc.sql("""
				SELECT met_at IS NOT NULL FROM online_lecture_item_progress WHERE user_id = :u AND online_lecture_item_id = :i
				""").param("u", this.stu).param("i", itemId).query(Boolean.class).single()).isTrue();
	}

}
