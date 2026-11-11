package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

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
import com.stackcorp.astra.shared.audit.AuditRows;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;

/**
 * 출석 차시와 출석부(EP-0011, EP-0012 대체 계약). 담당자는 차시를 관리하고 상태를 사유와 함께 정한다. 학생은 자기 기록만 본다.
 */
class AttendanceApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	AttendanceSummaries summaries;

	long course;

	long otherCourse;

	long prof;

	long stu;

	long mate;

	String profSession;

	ApiClient professor;

	ApiClient student;

	ApiClient classmate;

	ApiClient outsider;

	ApiClient anonymous;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.otherCourse = this.data.course();
		this.prof = this.data.user("PROFESSOR");
		this.stu = this.data.user("STUDENT");
		this.mate = this.data.user("STUDENT");
		this.data.instructor(this.course, this.prof);
		this.data.instructor(this.otherCourse, this.prof);
		this.data.enroll(this.course, this.stu);
		this.data.enroll(this.course, this.mate);
		this.data.enroll(this.otherCourse, this.stu);
		this.profSession = this.data.session(this.prof);
		this.professor = new ApiClient(this.mvc, this.profSession);
		this.student = new ApiClient(this.mvc, this.data.session(this.stu));
		this.classmate = new ApiClient(this.mvc, this.data.session(this.mate));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		this.anonymous = new ApiClient(this.mvc, null);
	}

	@Test
	void instructorManagesSessionsWithVersionGuard() throws Exception {
		JsonNode created = ApiClient.json(createSession(this.professor, this.course, 3, 1)
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.method").value("manual"))
			.andExpect(jsonPath("$.classDate").value("2026-09-17")));
		long session = created.get("sessionId").asLong();
		// 같은 주차의 같은 차시는 하나다.
		createSession(this.professor, this.course, 3, 1).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("session_exists"));

		String update = sessionBody(3, 2, "\"cancelled\":true");
		put(this.professor, sessionPath(this.course, session), null, update).andExpect(status().isPreconditionRequired());
		put(this.professor, sessionPath(this.course, session), "\"7\"", update).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		put(this.professor, sessionPath(this.course, session), "\"1\"", update).andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"2\""))
			.andExpect(jsonPath("$.sessionNo").value(2))
			.andExpect(jsonPath("$.cancelled").value(true));
		// 휴강한 차시에는 상태를 정하지 않는다.
		setRecord(this.professor, this.course, session, this.stu, "\"0\"", "present", "확인").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("session_cancelled"));
		// 휴강한 차시의 보강은 원 차시를 가리킨다.
		JsonNode makeup = ApiClient.json(this.professor
			.postJson(sessions(this.course), sessionBody(4, 2, "\"makeupOfSessionId\":" + session))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.makeup").value(true)));
		this.professor.send(MockMvcRequestBuilders.delete(sessionPath(this.course, session)).header("If-Match", "\"2\""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("session_in_use"));
		this.professor
			.send(MockMvcRequestBuilders.delete(sessionPath(this.course, makeup.get("sessionId").asLong()))
				.header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());
		ResultActions deleted = this.professor
			.send(MockMvcRequestBuilders.delete(sessionPath(this.course, session)).header("If-Match", "\"2\""))
			.andExpect(status().isNoContent());
		assertThat(AuditRows.single(this.jdbc, deleted).operation()).isEqualTo("attendance_session.delete");
		assertThat(count("SELECT COUNT(*) FROM attendance_sessions WHERE attendance_session_id = :id", session)).isZero();
	}

	@Test
	void sessionWithRecordsOrLinkedActivityIsNotDeleted() throws Exception {
		long session = sessionId(createSession(this.professor, this.course, 1, 1));
		setRecord(this.professor, this.course, session, this.stu, "\"0\"", "present", "출석 확인").andExpect(status().isOk());
		this.professor.send(MockMvcRequestBuilders.delete(sessionPath(this.course, session)).header("If-Match", "\"1\""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("session_in_use"));

		long linked = sessionId(createSession(this.professor, this.course, 2, 1));
		long activity = this.data.activity(this.course, "lecture_weeks", true, null, null);
		this.jdbc.sql("UPDATE learning_activities SET attendance_session_id = :s WHERE activity_id = :a")
			.param("s", linked)
			.param("a", activity)
			.update();
		this.professor.send(MockMvcRequestBuilders.delete(sessionPath(this.course, linked)).header("If-Match", "\"1\""))
			.andExpect(status().isConflict());
		// 지운 활동(ADR-0011)의 연결은 끊고 차시를 지운다.
		this.jdbc.sql("UPDATE learning_activities SET deleted_at = CURRENT_TIMESTAMP(3) WHERE activity_id = :a")
			.param("a", activity)
			.update();
		this.professor.send(MockMvcRequestBuilders.delete(sessionPath(this.course, linked)).header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());
		assertThat(this.jdbc.sql("SELECT attendance_session_id FROM learning_activities WHERE activity_id = :a")
			.param("a", activity)
			.query(Long.class)
			.optional()).isEmpty();
	}

	@Test
	void manualCorrectionIsGuardedAuditedAndKeepsReason() throws Exception {
		long session = sessionId(createSession(this.professor, this.course, 1, 1));
		String path = recordPath(this.course, session, this.stu);
		setRecord(this.professor, this.course, session, this.stu, null, "present", "확인")
			.andExpect(status().isPreconditionRequired());
		setRecord(this.professor, this.course, session, this.stu, "\"0\"", "unmarked", "확인")
			.andExpect(status().isBadRequest());
		setRecord(this.professor, this.course, session, this.stu, "\"0\"", "present", " ").andExpect(status().isBadRequest());
		setRecord(this.professor, this.course, session, this.stu, "\"0\"", "present", "a".repeat(201))
			.andExpect(status().isBadRequest());
		setRecord(this.professor, this.course, session, this.stu, "\"0\"", "present", "줄\u0007바꿈")
			.andExpect(status().isBadRequest());
		// 요청 본문으로 판정자나 출처를 정하지 못한다(SC-02).
		this.professor
			.send(MockMvcRequestBuilders.put(path)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"status\":\"present\",\"reason\":\"확인\",\"source\":\"online\"}")
				.header("If-Match", "\"0\""))
			.andExpect(status().isBadRequest());
		assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE attendance_session_id = :id", session)).isZero();

		ResultActions first = setRecord(this.professor, this.course, session, this.stu, "\"0\"", "late", "10분 늦음")
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.status").value("late"))
			.andExpect(jsonPath("$.source").value("manual"))
			.andExpect(jsonPath("$.version").value(1));
		AuditRows.Row audit = AuditRows.single(this.jdbc, first);
		assertThat(audit.operation()).isEqualTo("attendance_record.set");
		assertThat(audit.actor()).isEqualTo(this.prof);
		assertThat(audit.course()).isEqualTo(this.course);
		// 읽은 version이 낡았으면 아무것도 바꾸지 않는다.
		setRecord(this.professor, this.course, session, this.stu, "\"0\"", "absent", "다시").andExpect(status().isConflict());
		setRecord(this.professor, this.course, session, this.stu, "\"1\"", "excused", "병결 서류 확인")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(2));
		assertThat(this.jdbc.sql("""
				SELECT c.previous_status, c.new_status, c.reason, c.changed_by
				  FROM attendance_record_changes c JOIN attendance_records r ON r.attendance_record_id = c.attendance_record_id
				 WHERE r.attendance_session_id = :s AND r.user_id = :u ORDER BY c.attendance_record_change_id
				""").param("s", session).param("u", this.stu).query((rs, n) -> List.of(String.valueOf(rs.getString(1)),
				rs.getString(2), rs.getString(3), rs.getString(4))).list())
			.containsExactly(List.of("null", "지각", "10분 늦음", Long.toString(this.prof)),
					List.of("지각", "공결", "병결 서류 확인", Long.toString(this.prof)));
		// 감사 기록에는 사유가 없다.
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = 'attendance_record.set' AND course_id = :c")
			.param("c", this.course)
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

	@Test
	void fillOnlyTouchesUndecidedStudents() throws Exception {
		long session = sessionId(createSession(this.professor, this.course, 1, 1));
		setRecord(this.professor, this.course, session, this.stu, "\"0\"", "late", "늦음").andExpect(status().isOk());
		long third = this.data.user("STUDENT");
		this.data.enroll(this.course, third);
		this.data.insert("""
				INSERT INTO attendance_records (attendance_session_id, user_id, status) VALUES (:s, :u, '미처리')
				""", Map.of("s", session, "u", third));
		this.student.postJson(fillPath(this.course, session), "{\"status\":\"absent\",\"reason\":\"미출석\"}")
			.andExpect(status().isNotFound());
		this.professor.postJson(fillPath(this.course, session), "{\"status\":\"absent\",\"reason\":\"미출석\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.updated").value(2));
		assertThat(statusOf(session, this.stu)).isEqualTo("지각");
		assertThat(statusOf(session, this.mate)).isEqualTo("결석");
		assertThat(statusOf(session, third)).isEqualTo("결석");
		this.professor.postJson(fillPath(this.course, session), "{\"status\":\"present\",\"reason\":\"다시\"}")
			.andExpect(jsonPath("$.updated").value(0));
	}

	@Test
	void studentSeesOnlyOwnRecordsAndSummary() throws Exception {
		long first = sessionId(createSession(this.professor, this.course, 1, 1));
		long second = sessionId(createSession(this.professor, this.course, 1, 2));
		long third = sessionId(createSession(this.professor, this.course, 2, 1));
		long cancelled = sessionId(createSession(this.professor, this.course, 2, 2));
		setRecord(this.professor, this.course, first, this.stu, "\"0\"", "late", "늦음");
		setRecord(this.professor, this.course, second, this.stu, "\"0\"", "late", "늦음");
		setRecord(this.professor, this.course, third, this.stu, "\"0\"", "absent", "결석");
		setRecord(this.professor, this.course, first, this.mate, "\"0\"", "present", "출석");
		put(this.professor, sessionPath(this.course, cancelled), "\"1\"", sessionBody(2, 2, "\"cancelled\":true"))
			.andExpect(status().isOk());
		put(this.professor, settingsPath(this.course), "\"0\"",
				"{\"lateAfterMinutes\":5,\"roundMinutes\":10,\"latesPerAbsence\":2}")
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"1\""));

		this.student.get(attendancePath(this.course))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.role").value("STUDENT"))
			.andExpect(jsonPath("$.summary.sessions").value(3))
			.andExpect(jsonPath("$.summary.late").value(2))
			.andExpect(jsonPath("$.summary.absent").value(1))
			.andExpect(jsonPath("$.summary.absenceEquivalent").value(2))
			.andExpect(jsonPath("$.weeks[0].sessions[0].record.status").value("late"))
			.andExpect(jsonPath("$.weeks[0].sessions[0].counts").doesNotExist())
			.andExpect(jsonPath("$.weeks[1].sessions[1].cancelled").value(true));
		// 같은 과목 학생도 다른 학생의 기록은 보지 못한다.
		this.classmate.get(attendancePath(this.course))
			.andExpect(jsonPath("$.summary.present").value(1))
			.andExpect(jsonPath("$.summary.late").value(0))
			.andExpect(jsonPath("$.weeks[0].sessions[1].record.status").value("unmarked"));
		this.classmate.get(attendancePath(this.course) + "/roster").andExpect(status().isNotFound());
		this.student.get(attendancePath(this.course) + "?week=2")
			.andExpect(jsonPath("$.weeks.length()").value(1))
			.andExpect(jsonPath("$.weeks[0].weekNo").value(2));
		this.student.get(attendancePath(this.course) + "?week=0").andExpect(status().isBadRequest());
		this.professor.get(attendancePath(this.course))
			.andExpect(jsonPath("$.role").value("INSTRUCTOR"))
			.andExpect(jsonPath("$.summary").doesNotExist())
			.andExpect(jsonPath("$.weeks[0].sessions[0].counts.late").value(1))
			.andExpect(jsonPath("$.weeks[0].sessions[0].counts.present").value(1));

		assertThat(this.summaries.of(this.stu, List.of(this.course, this.otherCourse)))
			.containsEntry(this.course,
					new AttendanceSummaries.AttendanceSummary(this.course, 3, 0, 2, 1, 0, 0, 2))
			.containsEntry(this.otherCourse,
					new AttendanceSummaries.AttendanceSummary(this.otherCourse, 0, 0, 0, 0, 0, 0, 0));
	}

	@Test
	void rosterIsPagedSortedAndVersioned() throws Exception {
		long session = sessionId(createSession(this.professor, this.course, 1, 1));
		setRecord(this.professor, this.course, session, this.mate, "\"0\"", "present", "출석");
		for (int i = 0; i < 3; i++) {
			this.data.enroll(this.course, this.data.user("STUDENT"));
		}
		JsonNode page1 = ApiClient.json(this.professor.get(attendancePath(this.course) + "/roster?size=2&sort=name")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.students.length()").value(2))
			.andExpect(jsonPath("$.sessions[0].sessionId").value(session)));
		JsonNode page3 = ApiClient.json(this.professor.get(attendancePath(this.course) + "/roster?size=2&page=3&sort=name")
			.andExpect(jsonPath("$.students.length()").value(1)));
		assertThat(page1.get("students").get(0).get("userId").asLong())
			.isLessThan(page1.get("students").get(1).get("userId").asLong());
		assertThat(page3.get("students").get(0).get("userId").asLong())
			.isGreaterThan(page1.get("students").get(1).get("userId").asLong());
		JsonNode all = ApiClient.json(this.professor.get(attendancePath(this.course) + "/roster"));
		for (JsonNode row : all.get("students")) {
			JsonNode cell = row.get("cells").get(0);
			if (row.get("userId").asLong() == this.mate) {
				assertThat(cell.get("status").asString()).isEqualTo("present");
				assertThat(cell.get("version").asInt()).isEqualTo(1);
			}
			else {
				assertThat(cell.get("status").asString()).isEqualTo("unmarked");
				assertThat(cell.get("version").asInt()).isZero();
			}
		}
		this.professor.get(attendancePath(this.course) + "/roster?size=101").andExpect(status().isBadRequest());
		this.professor.get(attendancePath(this.course) + "/roster?sort=user_id").andExpect(status().isBadRequest());
	}

	@Test
	void authorizationMatrix() throws Exception {
		long session = sessionId(createSession(this.professor, this.course, 1, 1));
		String body = sessionBody(1, 2, "\"cancelled\":false");
		// 읽기
		for (ApiClient member : List.of(this.professor, this.student, this.classmate)) {
			member.get(attendancePath(this.course)).andExpect(status().isOk());
			member.get(settingsPath(this.course)).andExpect(status().isOk());
		}
		this.outsider.get(attendancePath(this.course)).andExpect(status().isNotFound());
		this.outsider.get(settingsPath(this.course)).andExpect(status().isNotFound());
		this.anonymous.get(attendancePath(this.course)).andExpect(status().isUnauthorized());
		this.student.get(attendancePath(this.course) + "/roster").andExpect(status().isNotFound());
		this.outsider.get(attendancePath(this.course) + "/roster").andExpect(status().isNotFound());
		this.anonymous.get(attendancePath(this.course) + "/roster").andExpect(status().isUnauthorized());
		// 쓰기: 학생, 외부인, 다른 과목 경로는 404, 익명은 401, CSRF가 없으면 403
		for (ApiClient other : List.of(this.student, this.outsider)) {
			createSession(other, this.course, 5, 1).andExpect(status().isNotFound());
			put(other, sessionPath(this.course, session), "\"1\"", body).andExpect(status().isNotFound());
			other.send(MockMvcRequestBuilders.delete(sessionPath(this.course, session)).header("If-Match", "\"1\""))
				.andExpect(status().isNotFound());
			setRecord(other, this.course, session, this.stu, "\"0\"", "present", "확인").andExpect(status().isNotFound());
			other.postJson(fillPath(this.course, session), "{\"status\":\"present\",\"reason\":\"확인\"}")
				.andExpect(status().isNotFound());
			put(other, settingsPath(this.course), "\"0\"", "{\"lateAfterMinutes\":5,\"roundMinutes\":10,\"latesPerAbsence\":0}")
				.andExpect(status().isNotFound());
		}
		createSession(this.anonymous, this.course, 5, 1).andExpect(status().isUnauthorized());
		setRecord(this.anonymous, this.course, session, this.stu, "\"0\"", "present", "확인")
			.andExpect(status().isUnauthorized());
		// 같은 담당자라도 다른 과목 경로로는 이 과목의 차시에 닿지 않는다(SC-03).
		put(this.professor, sessionPath(this.otherCourse, session), "\"1\"", body).andExpect(status().isNotFound());
		setRecord(this.professor, this.otherCourse, session, this.stu, "\"0\"", "present", "확인")
			.andExpect(status().isNotFound());
		this.professor.postJson(fillPath(this.otherCourse, session), "{\"status\":\"present\",\"reason\":\"확인\"}")
			.andExpect(status().isNotFound());
		// 수강생이 아닌 사람의 기록은 만들지 않는다.
		long stranger = this.data.user("STUDENT");
		setRecord(this.professor, this.course, session, stranger, "\"0\"", "present", "확인").andExpect(status().isNotFound());
		setRecord(this.professor, this.course, session, this.prof, "\"0\"", "present", "확인").andExpect(status().isNotFound());
		Cookie cookie = new Cookie("ASTRA_SESSION", this.profSession);
		this.mvc.perform(MockMvcRequestBuilders.put(recordPath(this.course, session, this.stu))
			.cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"present\",\"reason\":\"확인\"}")
			.header("If-Match", "\"0\"")).andExpect(status().isForbidden());
		this.mvc.perform(MockMvcRequestBuilders.post(sessions(this.course))
			.cookie(cookie)
			.contentType(MediaType.APPLICATION_JSON)
			.content(sessionBody(6, 1, "")))
			.andExpect(status().isForbidden());
		this.mvc.perform(MockMvcRequestBuilders.delete(sessionPath(this.course, session)).cookie(cookie).header("If-Match", "\"1\""))
			.andExpect(status().isForbidden());
		assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE attendance_session_id = :id", session)).isZero();
		assertThat(count("SELECT COUNT(*) FROM attendance_sessions WHERE course_id = :id", this.course)).isEqualTo(1);
	}

	@Test
	void createIsIdempotentWithKey() throws Exception {
		String body = sessionBody(8, 1, "");
		var request = MockMvcRequestBuilders.post(sessions(this.course))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header("Idempotency-Key", "attendance-session-1");
		long first = sessionId(this.professor.send(request).andExpect(status().isCreated()));
		long second = sessionId(this.professor.send(MockMvcRequestBuilders.post(sessions(this.course))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header("Idempotency-Key", "attendance-session-1")).andExpect(status().isCreated()));
		assertThat(second).isEqualTo(first);
		assertThat(count("SELECT COUNT(*) FROM attendance_sessions WHERE course_id = :id", this.course)).isEqualTo(1);
	}

	@Test
	void settingsAreValidatedAndVersioned() throws Exception {
		this.student.get(settingsPath(this.course))
			.andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.lateAfterMinutes").value(AttendancePolicy.DEFAULT_LATE_AFTER_MINUTES))
			.andExpect(jsonPath("$.roundMinutes").value(AttendancePolicy.DEFAULT_ROUND_MINUTES))
			.andExpect(jsonPath("$.latesPerAbsence").value(0));
		put(this.professor, settingsPath(this.course), "\"0\"", "{\"lateAfterMinutes\":11,\"roundMinutes\":10,\"latesPerAbsence\":0}")
			.andExpect(status().isBadRequest());
		put(this.professor, settingsPath(this.course), "\"0\"", "{\"lateAfterMinutes\":3,\"roundMinutes\":241,\"latesPerAbsence\":0}")
			.andExpect(status().isBadRequest());
		put(this.professor, settingsPath(this.course), "\"0\"", "{\"lateAfterMinutes\":3,\"roundMinutes\":15,\"latesPerAbsence\":3}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(1));
		put(this.professor, settingsPath(this.course), "\"0\"", "{\"lateAfterMinutes\":3,\"roundMinutes\":15,\"latesPerAbsence\":3}")
			.andExpect(status().isConflict());
		this.student.get(settingsPath(this.course)).andExpect(jsonPath("$.roundMinutes").value(15));
	}

	private ResultActions createSession(ApiClient client, long courseId, int week, int no) throws Exception {
		return client.postJson(sessions(courseId), sessionBody(week, no, ""));
	}

	/** method는 manual. extra는 쉼표 없이 붙일 필드다. */
	static String sessionBody(int week, int no, String extra) {
		return "{\"weekNo\":" + week + ",\"sessionNo\":" + no
				+ ",\"classDate\":\"2026-09-17\",\"startTime\":\"14:00\",\"endTime\":\"15:00\",\"method\":\"manual\""
				+ (extra.isEmpty() ? "" : "," + extra) + "}";
	}

	private ResultActions put(ApiClient client, String path, String ifMatch, String body) throws Exception {
		var request = MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(body);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return client.send(request);
	}

	static ResultActions setRecord(ApiClient client, long courseId, long session, long userId, String ifMatch,
			String status, String reason) throws Exception {
		var request = MockMvcRequestBuilders.put(recordPath(courseId, session, userId))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"status\":\"" + status + "\",\"reason\":\"" + reason + "\"}");
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return client.send(request);
	}

	static long sessionId(ResultActions result) throws Exception {
		return ApiClient.json(result).get("sessionId").asLong();
	}

	private String statusOf(long session, long userId) {
		return this.jdbc.sql("SELECT status FROM attendance_records WHERE attendance_session_id = :s AND user_id = :u")
			.param("s", session)
			.param("u", userId)
			.query(String.class)
			.single();
	}

	private int count(String sql, long id) {
		return this.jdbc.sql(sql).param("id", id).query(Integer.class).single();
	}

	static String sessions(long courseId) {
		return "/api/courses/" + courseId + "/attendance-sessions";
	}

	static String sessionPath(long courseId, long session) {
		return sessions(courseId) + "/" + session;
	}

	static String recordPath(long courseId, long session, long userId) {
		return sessionPath(courseId, session) + "/records/" + userId;
	}

	static String fillPath(long courseId, long session) {
		return sessionPath(courseId, session) + "/records/fill";
	}

	static String attendancePath(long courseId) {
		return "/api/courses/" + courseId + "/attendance";
	}

	static String settingsPath(long courseId) {
		return attendancePath(courseId) + "/settings";
	}

}
