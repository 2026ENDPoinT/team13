package com.stackcorp.astra.learning;

import static com.stackcorp.astra.learning.AttendanceApiTests.sessionPath;
import static com.stackcorp.astra.learning.AttendanceApiTests.sessions;
import static com.stackcorp.astra.learning.AttendanceApiTests.setRecord;
import static com.stackcorp.astra.learning.LearningSessionApiTests.heartbeats;
import static com.stackcorp.astra.learning.LearningSessionApiTests.parallel;
import static com.stackcorp.astra.learning.LearningSessionApiTests.start;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sql.DataSource;

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
import com.stackcorp.astra.learning.api.AttendanceSummaries;
import com.stackcorp.astra.learning.api.AttendanceSummaries.AttendanceSummary;

import tools.jackson.databind.JsonNode;

/**
 * 온라인 강의 충족의 출석부 자동 반영(ADR-0030 8번). 영상 활동이 차시를 가리키지 않으면 그 주차의 휴강하지 않은 온라인 차시가 정확히
 * 하나일 때 그 차시에 반영한다. 차시에 걸린 영상 활동을 모두 충족해야 출석이고, 자동 판정은 빈 칸만 채운다.
 */
class OnlineAttendanceLinkTests extends IntegrationTest {

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
	AttendanceSummaries summaries;

	@Autowired
	DataSource dataSource;

	final ExecutorService pool = Executors.newCachedThreadPool();

	long course;

	long stu;

	long mate;

	ApiClient student;

	ApiClient classmate;

	ApiClient professor;

	LocalDateTime t0;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.stu = this.data.user("STUDENT");
		this.mate = this.data.user("STUDENT");
		long prof = this.data.user("PROFESSOR");
		this.data.enroll(this.course, this.stu);
		this.data.enroll(this.course, this.mate);
		this.data.instructor(this.course, prof);
		this.student = new ApiClient(this.mvc, this.data.session(this.stu));
		this.classmate = new ApiClient(this.mvc, this.data.session(this.mate));
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.t0 = this.clock.freeze();
	}

	@AfterEach
	void release() {
		this.clock.release();
		this.pool.shutdownNow();
	}

	@Test
	void linksTheWeeksOnlyOnlineSessionAndShowsItWhereTheBookIsRead() throws Exception {
		long week = week(3);
		long item = lectureItem(lecture(week));
		long online = createSession(3, 1, "online");

		meet(this.student, item);

		assertThat(record(online, this.stu)).containsExactly("출석", "online");
		assertThat(record(online, this.mate)).isEmpty();
		// 학생 출석부와 요약(attendance-remote.js가 읽는 값), 담당자 출석부 칸에 같은 기록이 보인다.
		JsonNode book = ApiClient.json(this.student.get(AttendanceApiTests.attendancePath(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.weeks[0].sessions[0].method").value("online"))
			.andExpect(jsonPath("$.weeks[0].sessions[0].record.status").value("present"))
			.andExpect(jsonPath("$.weeks[0].sessions[0].record.source").value("online"))
			.andExpect(jsonPath("$.summary.present").value(1))
			.andExpect(jsonPath("$.summary.unmarked").value(0)));
		assertThat(LocalDateTime.parse(book.get("weeks").get(0).get("sessions").get(0).get("record").get("decidedAt")
			.asString())).isEqualTo(this.t0.plusSeconds(60));
		this.professor.get("/api/courses/" + this.course + "/attendance/roster?size=100")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.students[?(@.userId == " + this.stu + ")].cells[0].status").value("present"))
			.andExpect(jsonPath("$.students[?(@.userId == " + this.stu + ")].cells[0].source").value("online"))
			.andExpect(jsonPath("$.students[?(@.userId == " + this.mate + ")].cells[0].status").value("unmarked"));
		// 메인 과목 카드의 출석(STAC-111: present + late + excused / sessions - unmarked)이 읽는 요약이다.
		AttendanceSummary summary = this.summaries.of(this.stu, List.of(this.course)).get(this.course);
		assertThat(summary.present()).isEqualTo(1);
		assertThat(summary.sessions() - summary.unmarked()).isEqualTo(1);
	}

	@Test
	void everyLectureLinkedToTheSessionMustBeMet() throws Exception {
		long week = week(3);
		long first = lectureItem(lecture(week));
		long second = lectureItem(lecture(week));
		long online = createSession(3, 1, "online");

		meet(this.student, first);
		assertThat(record(online, this.stu)).isEmpty();
		meet(this.student, second);
		assertThat(record(online, this.stu)).containsExactly("출석", "online");
	}

	@Test
	void noOnlineSessionOrSeveralLinkNothingUntilExactlyOneRemains() throws Exception {
		long week = week(3);
		long item = lectureItem(lecture(week));
		long manual = createSession(3, 1, "manual");
		long smart = createSession(3, 2, "smart");
		long first = createSession(4, 1, "online");
		long secondWeek = week(4);
		long other = lectureItem(lecture(secondWeek));
		long second = createSession(4, 2, "online");

		meet(this.student, item);
		meet(this.student, other);

		assertThat(record(manual, this.stu)).isEmpty();
		assertThat(record(smart, this.stu)).isEmpty();
		assertThat(record(first, this.stu)).isEmpty();
		assertThat(record(second, this.stu)).isEmpty();

		// 둘 중 하나를 지우면 남은 차시가 주차의 유일한 온라인 차시다. 이미 충족한 학생이 채워진다.
		this.professor.send(MockMvcRequestBuilders.delete(sessionPath(this.course, second)).header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());
		assertThat(record(first, this.stu)).containsExactly("출석", "online");
		assertThat(decidedAt(first, this.stu)).isEqualTo(this.clock.now());
		assertThat(record(first, this.mate)).isEmpty();
	}

	@Test
	void sessionCreatedOrSwitchedToOnlineLaterCreditsStudentsWhoAlreadyMetTheLecture() throws Exception {
		long item = lectureItem(lecture(week(3)));
		meet(this.student, item);
		this.clock.advance(Duration.ofMinutes(5));

		long online = createSession(3, 1, "online");
		assertThat(record(online, this.stu)).containsExactly("출석", "online");
		assertThat(decidedAt(online, this.stu)).isEqualTo(this.clock.now());
		assertThat(record(online, this.mate)).isEmpty();

		long later = lectureItem(lecture(week(5)));
		long manual = createSession(5, 1, "manual");
		meet(this.student, later);
		assertThat(record(manual, this.stu)).isEmpty();
		updateSession(manual, 5, 1, "online", false, 1).andExpect(status().isOk());
		assertThat(record(manual, this.stu)).containsExactly("출석", "online");
	}

	@Test
	void recordsSurviveMethodChangeAndASecondOnlineSessionStopsNewLinks() throws Exception {
		long item = lectureItem(lecture(week(3)));
		long online = createSession(3, 1, "online");
		meet(this.student, item);
		assertThat(record(online, this.stu)).containsExactly("출석", "online");

		// 이미 쓴 기록은 방식이 바뀌어도 그대로다.
		updateSession(online, 3, 1, "manual", false, 1).andExpect(status().isOk());
		assertThat(record(online, this.stu)).containsExactly("출석", "online");
		updateSession(online, 3, 1, "online", false, 2).andExpect(status().isOk());

		// 두 번째 온라인 차시가 생기면 새 충족은 어느 쪽에도 쓰지 않는다. 앞서 쓴 기록은 남는다.
		long second = createSession(3, 2, "online");
		meet(this.classmate, item);
		assertThat(record(online, this.mate)).isEmpty();
		assertThat(record(second, this.mate)).isEmpty();
		assertThat(record(second, this.stu)).isEmpty();
		assertThat(record(online, this.stu)).containsExactly("출석", "online");

		// 두 번째 차시를 휴강하면 첫 차시가 다시 유일하다. 그 사이 충족한 학생이 채워진다. 휴강한 차시에는 쓰지 않는다.
		updateSession(second, 3, 2, "online", true, 1).andExpect(status().isOk());
		assertThat(record(online, this.mate)).containsExactly("출석", "online");
		assertThat(record(second, this.mate)).isEmpty();
	}

	@Test
	void cancelledOnlineSessionIsNotCreditedUntilRestored() throws Exception {
		long item = lectureItem(lecture(week(3)));
		long online = createSession(3, 1, "online");
		updateSession(online, 3, 1, "online", true, 1).andExpect(status().isOk());

		meet(this.student, item);
		assertThat(record(online, this.stu)).isEmpty();

		updateSession(online, 3, 1, "online", false, 2).andExpect(status().isOk());
		assertThat(record(online, this.stu)).containsExactly("출석", "online");
	}

	@Test
	void professorsDecisionIsNeverOverwritten() throws Exception {
		long item = lectureItem(lecture(week(3)));
		long session = createSession(3, 1, "manual");
		setRecord(this.professor, this.course, session, this.mate, "\"0\"", "excused", "병가").andExpect(status().isOk());
		meet(this.student, item);
		meet(this.classmate, item);

		// 방식을 온라인으로 바꾸면 빈 칸만 채운다.
		updateSession(session, 3, 1, "online", false, 1).andExpect(status().isOk());
		assertThat(record(session, this.stu)).containsExactly("출석", "online");
		assertThat(record(session, this.mate)).containsExactly("공결", "manual");

		// 충족 전에 담당자가 정한 칸은 충족해도 그대로다.
		long next = lectureItem(lecture(week(4)));
		long online = createSession(4, 1, "online");
		setRecord(this.professor, this.course, online, this.stu, "\"0\"", "absent", "미수강 확인").andExpect(status().isOk());
		meet(this.student, next);
		assertThat(record(online, this.stu)).containsExactly("결석", "manual");
		// 온라인 기록도 담당자가 바꿀 수 있다.
		setRecord(this.professor, this.course, session, this.stu, "\"1\"", "late", "확인").andExpect(status().isOk());
		assertThat(record(session, this.stu)).containsExactly("지각", "manual");
	}

	@Test
	void explicitLinkWinsOverTheWeeksOnlineSession() throws Exception {
		long week = week(3);
		long linked = lecture(week);
		long linkedItem = lectureItem(linked);
		long unlinkedItem = lectureItem(lecture(week));
		long online = createSession(3, 1, "online");
		long target = createSession(6, 1, "manual");
		long sibling = lecture(week(7));
		long siblingItem = lectureItem(sibling);
		link(linked, target);
		link(sibling, target);

		// 직접 가리키는 차시에 걸린 활동(다른 주차의 활동 포함)을 모두 충족해야 출석이다.
		meet(this.student, linkedItem);
		assertThat(record(target, this.stu)).isEmpty();
		meet(this.student, siblingItem);
		assertThat(record(target, this.stu)).containsExactly("출석", "online");
		// 주차의 온라인 차시에는 직접 연결한 활동이 걸리지 않는다. 연결 없는 활동만 걸린다.
		assertThat(record(online, this.stu)).isEmpty();
		meet(this.student, unlinkedItem);
		assertThat(record(online, this.stu)).containsExactly("출석", "online");
	}

	@Test
	void creditAfterTheLectureClosesDoesNotCountButTheSessionDateDoesNotMatter() throws Exception {
		long week = week(3);
		long closing = lecture(week, this.t0.plusSeconds(30));
		long item = lectureItem(closing);
		long online = createSession(3, 1, "online");

		meet(this.student, item);
		assertThat(record(online, this.stu)).isEmpty();
		this.clock.advance(Duration.ofMinutes(5));
		meet(this.student, item);
		assertThat(record(online, this.stu)).isEmpty();

		// 차시 수업일(2026-09-17)과 관계없이 활동 기간 안의 충족이면 출석이다.
		long open = lectureItem(lecture(week(4)));
		long past = createSession(4, 1, "online");
		meet(this.student, open);
		assertThat(record(past, this.stu)).containsExactly("출석", "online");
	}

	@Test
	void lectureEditsAndDeletionReconcileTheWeek() throws Exception {
		long week = week(3);
		long first = lectureItem(lecture(week));
		long hidden = lecture(week);
		long hiddenItem = lectureItem(hidden);
		long deleted = lecture(week);
		lectureItem(deleted);
		long online = createSession(3, 1, "online");
		meet(this.student, first);
		assertThat(record(online, this.stu)).isEmpty();

		// 비공개로 바꾼 활동은 걸리지 않는다. 지운 활동도 걸리지 않는다.
		this.professor
			.send(MockMvcRequestBuilders.put("/api/courses/" + this.course + "/lectures/" + hidden)
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"weekNo\":3,\"title\":\"숨김\",\"published\":false,\"items\":{\"keep\":[{\"itemId\":"
						+ hiddenItem + ",\"title\":\"숨김\",\"requiredSec\":60}],\"add\":[]}}"))
			.andExpect(status().isOk());
		assertThat(record(online, this.stu)).isEmpty();
		this.professor
			.send(MockMvcRequestBuilders.delete("/api/courses/" + this.course + "/lectures/" + deleted)
				.header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());
		assertThat(record(online, this.stu)).containsExactly("출석", "online");
	}

	@Test
	void concurrentHeartbeatsRecordAttendanceOnce() throws Exception {
		long item = lectureItem(lecture(week(3)));
		long online = createSession(3, 1, "online");
		long session = started(this.student, item);
		this.clock.advance(Duration.ofSeconds(60));

		List<Integer> credited = parallel(8, (i) -> () -> ApiClient.json(beat(this.student, session, i + 1))
			.get("creditedSec")
			.asInt());

		assertThat(credited).containsOnlyOnce(60);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM attendance_records WHERE attendance_session_id = :s")
			.param("s", online)
			.query(Integer.class)
			.single()).isEqualTo(1);
		assertThat(record(online, this.stu)).containsExactly("출석", "online");
	}

	/** 충족하는 하트비트와 차시 등록이 동시에 와도 어느 쪽이 먼저든 한 번 채워진다. */
	@Test
	void completionRacingSessionCreationIsCreditedExactlyOnce() throws Exception {
		for (int round = 0; round < 6; round++) {
			int weekNo = 10 + round;
			long item = lectureItem(lecture(week(weekNo)));
			long session = started(this.student, item);
			this.clock.advance(Duration.ofSeconds(60));
			List<Long> created = parallel(2, (i) -> () -> {
				if (i == 0) {
					beat(this.student, session, 1).andExpect(status().isOk()).andExpect(jsonPath("$.creditedSec").value(60));
					return 0L;
				}
				return AttendanceApiTests.sessionId(this.professor
					.postJson(sessions(this.course), body(weekNo, 1, "online", ""))
					.andExpect(status().isCreated()));
			});
			long online = created.get(1);
			assertThat(record(online, this.stu)).as("round %d", round).containsExactly("출석", "online");
			assertThat(this.jdbc.sql("SELECT COUNT(*) FROM attendance_records WHERE attendance_session_id = :s")
				.param("s", online)
				.query(Integer.class)
				.single()).isEqualTo(1);
		}
	}

	/**
	 * 하트비트가 snapshot을 정한 뒤 주차 잠금을 기다리는 동안 담당자가 같은 활동의 아직 충족하지 않은 콘텐츠를 뺐다. 하트비트의 snapshot은
	 * 뺀 콘텐츠를 아직 살아 있는 것으로 보지만 출석 판정은 최신 값으로 한다.
	 */
	@Test
	void heartbeatWaitingBehindALectureEditJudgesTheEditedLecture() throws Exception {
		long week = week(3);
		long lecture = lecture(week);
		long removed = lectureItem(lecture);
		long kept = lectureItem(lecture);
		long online = createSession(3, 1, "online");
		long session = started(this.student, kept);
		this.clock.advance(Duration.ofSeconds(60));

		try (WeekLock edit = new WeekLock(week)) {
			edit.execute("UPDATE online_lecture_items SET deleted_at = CURRENT_TIMESTAMP(3) WHERE online_lecture_item_id = ?",
					removed);
			Future<String> heartbeat = async(() -> ApiClient.json(beat(this.student, session, 1).andExpect(status().isOk()))
				.get("progress")
				.get("status")
				.asString());
			edit.awaitWaiter();
			edit.commit();
			assertThat(heartbeat.get(30, TimeUnit.SECONDS)).isEqualTo("met");
		}
		assertThat(record(online, this.stu)).containsExactly("출석", "online");
	}

	/** 충족하지 않는 하트비트는 출석부를 바꾸지 않으므로 주차 잠금을 기다리지 않는다. */
	@Test
	void heartbeatThatMeetsNothingDoesNotWaitForTheWeek() throws Exception {
		long week = week(3);
		long item = lectureItem(lecture(week));
		this.jdbc.sql("UPDATE online_lecture_items SET required_sec = 600 WHERE online_lecture_item_id = :i")
			.param("i", item)
			.update();
		createSession(3, 1, "online");
		long session = started(this.student, item);
		this.clock.advance(Duration.ofSeconds(60));

		try (WeekLock edit = new WeekLock(week)) {
			Future<Integer> heartbeat = async(() -> ApiClient.json(beat(this.student, session, 1).andExpect(status().isOk()))
				.get("creditedSec")
				.asInt());
			assertThat(heartbeat.get(30, TimeUnit.SECONDS)).isEqualTo(60);
		}
	}

	/** 주차 잠금을 기다리는 동안 활동이 다른 주차로 옮겨지면 하트비트는 처음부터 다시 실행해 옮긴 주차의 차시에 반영한다. */
	@Test
	void lectureMovedWhileTheHeartbeatWaitsIsCreditedInItsNewWeek() throws Exception {
		long week = week(3);
		long lecture = lecture(week);
		long item = lectureItem(lecture);
		long before = createSession(3, 1, "online");
		long after = createSession(4, 1, "online");
		long moved = week(4);
		long session = started(this.student, item);
		this.clock.advance(Duration.ofSeconds(60));

		try (WeekLock edit = new WeekLock(week)) {
			edit.execute("UPDATE learning_activities SET course_week_id = ? WHERE activity_id = ?", moved, lecture);
			Future<Integer> heartbeat = async(() -> ApiClient.json(beat(this.student, session, 1).andExpect(status().isOk()))
				.get("creditedSec")
				.asInt());
			edit.awaitWaiter();
			edit.commit();
			assertThat(heartbeat.get(30, TimeUnit.SECONDS)).isEqualTo(60);
		}
		assertThat(record(after, this.stu)).containsExactly("출석", "online");
		assertThat(record(before, this.stu)).isEmpty();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_credit_ledger WHERE learning_session_id = :s")
			.param("s", session)
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	/** 휴강 요청이 주차를 찾은 뒤 잠금을 기다리는 동안 열린 스마트출석 회차를 휴강 검사가 본다. */
	@Test
	void cancelWaitingForTheWeekSeesARoundOpenedMeanwhile() throws Exception {
		long session = createSession(3, 1, "smart");
		try (WeekLock other = new WeekLock(week(3))) {
			Future<Integer> cancel = async(
					() -> updateSession(session, 3, 1, "smart", true, 1).andReturn().getResponse().getStatus());
			other.awaitWaiter();
			this.professor.postJson(sessionPath(this.course, session) + "/smart-rounds", "{\"method\":\"pin\"}")
				.andExpect(status().isCreated());
			other.commit();
			assertThat(cancel.get(30, TimeUnit.SECONDS)).isEqualTo(409);
		}
		assertThat(this.jdbc.sql("SELECT session_state FROM attendance_sessions WHERE attendance_session_id = :s")
			.param("s", session)
			.query(String.class)
			.single()).isNotEqualTo("cancelled");
	}

	/** 삭제 요청이 주차를 찾은 뒤 잠금을 기다리는 동안 쓴 출석 기록을 사용 중 검사가 본다(FK 오류 500이 아니라 409). */
	@Test
	void deleteWaitingForTheWeekSeesARecordWrittenMeanwhile() throws Exception {
		long session = createSession(3, 1, "manual");
		try (WeekLock other = new WeekLock(week(3))) {
			Future<ResultActions> delete = async(() -> this.professor
				.send(MockMvcRequestBuilders.delete(sessionPath(this.course, session)).header("If-Match", "\"1\"")));
			other.awaitWaiter();
			setRecord(this.professor, this.course, session, this.stu, "\"0\"", "present", "확인")
				.andExpect(status().isOk());
			other.commit();
			delete.get(30, TimeUnit.SECONDS)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("session_in_use"));
		}
		assertThat(record(session, this.stu)).containsExactly("출석", "manual");
	}

	private <T> Future<T> async(Callable<T> task) {
		return this.pool.submit(task);
	}

	/**
	 * 주차 행을 배타 잠금한 두 번째 transaction. 차시·영상 활동 명령이 주차를 쥔 상태를 흉내 낸다. 다른 연결이 이 주차의 잠금 문장을
	 * 실행 중이면 이 잠금을 기다리는 것이다(이 transaction이 끝나기 전에는 끝날 수 없다). 시간이 아니라 그 상태를 process 목록으로
	 * 확인한다. 같은 계정의 연결은 PROCESS 권한 없이 보인다.
	 */
	private final class WeekLock implements AutoCloseable {

		private final Connection connection;

		private final long weekId;

		private final long connectionId;

		WeekLock(long weekId) throws Exception {
			this.weekId = weekId;
			this.connection = OnlineAttendanceLinkTests.this.dataSource.getConnection();
			this.connection.setAutoCommit(false);
			execute("SELECT course_week_id FROM course_weeks WHERE course_week_id = ? FOR UPDATE", weekId);
			try (PreparedStatement statement = this.connection.prepareStatement("SELECT CONNECTION_ID()");
					ResultSet rs = statement.executeQuery()) {
				rs.next();
				this.connectionId = rs.getLong(1);
			}
		}

		void execute(String sql, Object... args) throws Exception {
			try (PreparedStatement statement = this.connection.prepareStatement(sql)) {
				for (int i = 0; i < args.length; i++) {
					statement.setObject(i + 1, args[i]);
				}
				statement.execute();
			}
		}

		void awaitWaiter() throws Exception {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
			while (OnlineAttendanceLinkTests.this.jdbc.sql("""
					SELECT COUNT(*) FROM information_schema.PROCESSLIST
					 WHERE ID NOT IN (:me, CONNECTION_ID()) AND INFO LIKE :statement
					""")
				.param("me", this.connectionId)
				.param("statement", "%FROM course_weeks WHERE course_week_id = " + this.weekId + " FOR %")
				.query(Integer.class)
				.single() == 0) {
				assertThat(System.nanoTime()).as("다른 transaction이 주차 잠금을 기다리지 않았다").isLessThan(deadline);
				Thread.sleep(10);
			}
		}

		void commit() throws Exception {
			this.connection.commit();
		}

		@Override
		public void close() throws Exception {
			this.connection.rollback();
			this.connection.close();
		}

	}

	/** 콘텐츠 하나를 새 session으로 60초 들어 충족한다(필요 시간 60초). */
	private void meet(ApiClient client, long item) throws Exception {
		long session = started(client, item);
		this.clock.advance(Duration.ofSeconds(60));
		beat(client, session, 1).andExpect(status().isOk());
	}

	private long started(ApiClient client, long item) throws Exception {
		return ApiClient.json(client.post(start(this.course, item)).andExpect(status().isCreated()))
			.get("sessionId")
			.asLong();
	}

	private ResultActions beat(ApiClient client, long session, long seq) throws Exception {
		return client.postJson(heartbeats(this.course, session), "{\"seq\":" + seq + "}");
	}

	private long createSession(int weekNo, int no, String method) throws Exception {
		return AttendanceApiTests.sessionId(this.professor.postJson(sessions(this.course), body(weekNo, no, method, ""))
			.andExpect(status().isCreated()));
	}

	private ResultActions updateSession(long session, int weekNo, int no, String method, boolean cancelled,
			int version) throws Exception {
		return this.professor.send(MockMvcRequestBuilders.put(sessionPath(this.course, session))
			.header("If-Match", "\"" + version + "\"")
			.contentType(MediaType.APPLICATION_JSON)
			.content(body(weekNo, no, method, "\"cancelled\":" + cancelled)));
	}

	private static String body(int weekNo, int no, String method, String extra) {
		return "{\"weekNo\":" + weekNo + ",\"sessionNo\":" + no
				+ ",\"classDate\":\"2026-09-17\",\"startTime\":\"14:00\",\"endTime\":\"15:00\",\"method\":\"" + method
				+ "\"" + (extra.isEmpty() ? "" : "," + extra) + "}";
	}

	/** 주차를 찾거나 만든다. */
	private long week(int weekNo) {
		return this.jdbc.sql("SELECT course_week_id FROM course_weeks WHERE course_id = :c AND week_no = :n")
			.param("c", this.course)
			.param("n", weekNo)
			.query(Long.class)
			.optional()
			.orElseGet(() -> this.data.insert("INSERT INTO course_weeks (course_id, week_no) VALUES (:c, :n)",
					Map.of("c", this.course, "n", weekNo)));
	}

	private long lecture(long week) {
		return lecture(week, null);
	}

	private long lecture(long week, LocalDateTime closeAt) {
		Map<String, Object> params = new HashMap<>();
		params.put("c", this.course);
		params.put("w", week);
		params.put("close", closeAt);
		return this.data.insert("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, published_yn, close_at)
				VALUES (:c, :w, 'lecture_weeks', '강의', 1, :close)
				""", params);
	}

	private long lectureItem(long activityId) {
		Map<String, Object> params = new HashMap<>();
		params.put("a", activityId);
		params.put("seq", LINK_SEQ.incrementAndGet());
		params.put("path", "vod/lecture-" + UUID.randomUUID().toString().substring(0, 8) + "/main.m3u8");
		return this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path, duration_sec, required_sec)
				VALUES (:a, :seq, '강의', 'video', :path, 600, 60)
				""", params);
	}

	private void link(long activityId, long sessionId) {
		this.jdbc.sql("UPDATE learning_activities SET attendance_session_id = :s WHERE activity_id = :a")
			.param("s", sessionId)
			.param("a", activityId)
			.update();
	}

	private List<String> record(long session, long user) {
		return this.jdbc.sql("""
				SELECT status, source FROM attendance_records WHERE attendance_session_id = :s AND user_id = :u
				""")
			.param("s", session)
			.param("u", user)
			.query((rs, n) -> List.of(rs.getString(1), rs.getString(2)))
			.optional()
			.orElse(List.of());
	}

	private LocalDateTime decidedAt(long session, long user) {
		return this.jdbc.sql("SELECT decided_at FROM attendance_records WHERE attendance_session_id = :s AND user_id = :u")
			.param("s", session)
			.param("u", user)
			.query(LocalDateTime.class)
			.single();
	}

}
