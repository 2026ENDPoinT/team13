package com.stackcorp.astra.catalog;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 수업시간표는 요청한 사람이 담당하거나 수강이 확정된 현재 학기 과목의 수업만 담고, 주 경계는 서버가 정한다.
 */
class TimetableApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Test
	void weekContainsOnlyTheCallersCurrentTermClasses() throws Exception {
		LocalDate today = this.jdbc.sql("SELECT CURRENT_DATE").query(LocalDate.class).single();
		LocalDate sunday = today.minusDays(today.getDayOfWeek().getValue() % 7);
		long current = term(true, today.minusDays(60), today.plusDays(60));
		long past = term(false, today.minusDays(400), today.minusDays(300));
		long notStarted = term(true, sunday.plusDays(7), sunday.plusDays(120));
		long user = this.data.user("STUDENT");
		long prof = this.data.user("PROFESSOR");

		long attending = course(current, "기계학습", prof);
		this.data.enroll(attending, user);
		long teaching = course(current, "Database", null);
		this.data.instructor(teaching, user);
		long foreign = course(current, "남의 과목", prof);
		long old = course(past, "지난 학기", prof);
		this.data.enroll(old, user);
		long dropped = course(current, "취소한 과목", prof);
		this.data.enroll(dropped, user);
		this.jdbc.sql("UPDATE course_enrollments SET enrollment_status = 'CANCELLED' WHERE course_id = :c")
			.param("c", dropped)
			.update();
		long later = course(notStarted, "다음 주부터", prof);
		this.data.enroll(later, user);
		slot(attending, 1, "10:00", "13:00", null);
		slot(teaching, 3, "14:00", "17:00", "미래관 401");
		slot(foreign, 2, "09:00", "12:00", null);
		slot(old, 4, "09:00", "12:00", null);
		slot(dropped, 5, "09:00", "12:00", null);
		slot(later, 1, "09:00", "12:00", null);
		ApiClient client = new ApiClient(this.mvc, this.data.session(user));

		client.get("/api/me/timetable?date=" + today)
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.today").value(today.toString()))
			.andExpect(jsonPath("$.weekStart").value(sunday.toString()))
			.andExpect(jsonPath("$.weekEnd").value(sunday.plusDays(6).toString()))
			.andExpect(jsonPath("$.slots[*].courseId").value(contains((int) attending, (int) teaching)))
			.andExpect(jsonPath("$.slots[0].role").value("STUDENT"))
			.andExpect(jsonPath("$.slots[0].date").value(sunday.plusDays(1).toString()))
			.andExpect(jsonPath("$.slots[0].startTime").value(startsWith("10:00")))
			.andExpect(jsonPath("$.slots[0].room").doesNotExist())
			.andExpect(jsonPath("$.slots[0].instructors[*]").value(contains("테스트 PROFESSOR")))
			.andExpect(jsonPath("$.slots[1].role").value("INSTRUCTOR"))
			.andExpect(jsonPath("$.slots[1].room").value("미래관 401"));
		// 날짜를 비우면 오늘이 든 주다. 다음 학기 과목은 그 학기가 시작한 주부터 보인다.
		client.get("/api/me/timetable").andExpect(jsonPath("$.weekStart").value(sunday.toString()));
		client.get("/api/me/timetable?date=" + sunday.plusDays(7))
			.andExpect(jsonPath("$.slots[*].courseId").value(containsInAnyOrder((int) attending, (int) teaching, (int) later)));

		client.get("/api/me/timetable?date=" + today.plusDays(500)).andExpect(status().isBadRequest());
		client.get("/api/me/timetable?date=20261106").andExpect(status().isBadRequest());
		new ApiClient(this.mvc, null).get("/api/me/timetable").andExpect(status().isUnauthorized());
	}

	@Test
	void termSelectionIsLimitedToTermsWhereTheCallerIsAMember() throws Exception {
		LocalDate today = this.jdbc.sql("SELECT CURRENT_DATE").query(LocalDate.class).single();
		long current = term(true, today.minusDays(30), today.plusDays(30));
		LocalDate pastStart = today.minusDays(700);
		long past = term(false, pastStart, pastStart.plusDays(100));
		long foreignTerm = term(false, today.minusDays(500), today.minusDays(400));
		long cancelledTerm = term(false, today.minusDays(300), today.minusDays(200));
		long prof = this.data.user("PROFESSOR");
		long user = this.data.user("STUDENT");
		long now = course(current, "지금 과목", prof);
		this.data.enroll(now, user);
		long old = course(past, "지난 과목", prof);
		this.data.enroll(old, user);
		slot(old, 2, "09:00", "12:00", null);
		long foreign = course(foreignTerm, "남의 과목", prof);
		slot(foreign, 2, "09:00", "12:00", null);
		long cancelled = course(cancelledTerm, "폐강 과목", prof);
		this.data.enroll(cancelled, user);
		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c").param("c", cancelled).update();
		ApiClient client = new ApiClient(this.mvc, this.data.session(user));

		// 고를 수 있는 학기는 구성원인 과목이 있는 학기뿐이다. 폐강 과목만 있는 학기와 남의 학기는 없다.
		client.get("/api/me/timetable")
			.andExpect(jsonPath("$.termId").value((int) current))
			.andExpect(jsonPath("$.terms[*].termId").value(containsInAnyOrder((int) current, (int) past)));
		// 지난 학기를 고르면 날짜 없이도 그 학기의 마지막 주를 돌려준다(오늘을 학기 기간 안으로 옮긴 날). 오늘에서 400일 넘게 떨어져
		// 있어도 학기 안이면 된다.
		LocalDate pastEnd = pastStart.plusDays(100);
		LocalDate lastSunday = pastEnd.minusDays(pastEnd.getDayOfWeek().getValue() % 7);
		client.get("/api/me/timetable?termId=" + past)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.termId").value((int) past))
			.andExpect(jsonPath("$.weekStart").value(lastSunday.toString()));
		client.get("/api/me/timetable?termId=" + past + "&date=" + pastStart.plusDays(10))
			.andExpect(jsonPath("$.slots[*].courseId").value(contains((int) old)));
		client.get("/api/me/timetable?termId=" + past + "&date=" + today).andExpect(status().isBadRequest());
		client.get("/api/me/timetable?termId=" + foreignTerm)
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		client.get("/api/me/timetable?termId=" + cancelledTerm).andExpect(status().isBadRequest());
		client.get("/api/me/timetable?termId=abc").andExpect(status().isBadRequest());
		new ApiClient(this.mvc, null).get("/api/me/timetable?termId=" + past).andExpect(status().isUnauthorized());
	}

	@Test
	void courseTimetableIsForMembersOnly() throws Exception {
		LocalDate today = this.jdbc.sql("SELECT CURRENT_DATE").query(LocalDate.class).single();
		long prof = this.data.user("PROFESSOR");
		long course = course(term(true, today.minusDays(10), today.plusDays(10)), "컴퓨터보안", prof);
		slot(course, 1, "10:00", "13:00", "다산관 101");
		slot(course, 4, "10:00", "11:00", null);
		long student = this.data.user("STUDENT");
		this.data.enroll(course, student);

		new ApiClient(this.mvc, this.data.session(student)).get("/api/courses/" + course + "/timetable")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("STUDENT"))
			.andExpect(jsonPath("$.name").value("컴퓨터보안"))
			.andExpect(jsonPath("$.instructors[*]").value(contains("테스트 PROFESSOR")))
			.andExpect(jsonPath("$.slots[*].dayOfWeek").value(contains(1, 4)));
		new ApiClient(this.mvc, this.data.session(prof)).get("/api/courses/" + course + "/timetable")
			.andExpect(jsonPath("$.role").value("INSTRUCTOR"));
		new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT"))).get("/api/courses/" + course + "/timetable")
			.andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).get("/api/courses/" + course + "/timetable").andExpect(status().isUnauthorized());
	}

	private long term(boolean current, LocalDate start, LocalDate end) {
		return this.data.insert("""
				INSERT INTO terms (term_year, term_code, term_name, is_current, start_date, end_date)
				VALUES (:year, '3', '시험 학기', :current, :start, :end)
				""", Map.of("year", ThreadLocalRandom.current().nextInt(3000, 65000), "current", current, "start", start,
				"end", end));
	}

	/** @param professorId 담당자. 없으면 null */
	private long course(long termId, String name, Long professorId) {
		long course = this.data.insert("INSERT INTO courses (term_id, course_type, course_name) VALUES (:t, '정규', :n)",
				Map.of("t", termId, "n", name));
		if (professorId != null) {
			this.data.instructor(course, professorId);
		}
		return course;
	}

	private void slot(long courseId, int day, String start, String end, String room) {
		this.jdbc.sql("""
				INSERT INTO course_schedules (course_id, day_of_week, start_time, end_time, room_name)
				VALUES (:c, :d, :s, :e, :r)
				""").param("c", courseId).param("d", day).param("s", start).param("e", end).param("r", room).update();
	}

}
