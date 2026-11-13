package com.stackcorp.astra.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;

/**
 * 캘린더는 공개 학사일정과 요청한 사람의 과목 활동 마감만 담는다. 수강 과목은 학생에게 열린 활동만, 담당 과목은 모든 활동이다.
 */
class CalendarApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Test
	void containsPublicAcademicEventsAndOnlyTheCallersVisibleDeadlines() throws Exception {
		LocalDate today = this.jdbc.sql("SELECT CURRENT_DATE").query(LocalDate.class).single();
		long prof = this.data.user("PROFESSOR");
		long user = this.data.user("STUDENT");
		long attending = course(prof);
		this.data.enroll(attending, user);
		long teaching = this.data.course();
		this.data.instructor(teaching, user);
		long foreign = course(prof);

		long open = activity(attending, true, "NULL", "CURRENT_DATE + INTERVAL 12 HOUR");
		long closingTomorrow = activity(attending, true, "NULL", "CURRENT_DATE + INTERVAL 36 HOUR");
		long draft = activity(attending, false, "NULL", "CURRENT_DATE + INTERVAL 12 HOUR");
		long notOpen = activity(attending, true, "CURRENT_TIMESTAMP + INTERVAL 1 DAY", "CURRENT_DATE + INTERVAL 12 HOUR");
		long farAway = activity(attending, true, "NULL", "CURRENT_DATE + INTERVAL 300 DAY");
		long ownDraft = activity(teaching, false, "NULL", "CURRENT_DATE + INTERVAL 12 HOUR");
		long others = activity(foreign, true, "NULL", "CURRENT_DATE + INTERVAL 12 HOUR");
		long holiday = event("휴일 " + user, true, today, today);
		long hidden = event("비공개 " + user, false, today, today);
		long longPast = event("지난 일정 " + user, true, today.minusDays(200), today.minusDays(199));
		long spanning = event("수강철회기간 " + user, true, today.minusDays(1), today.plusDays(1));
		ApiClient client = new ApiClient(this.mvc, this.data.session(user));

		LocalDate first = today.withDayOfMonth(1);
		LocalDate from = first.minusDays(first.getDayOfWeek().getValue() % 7);
		LocalDate last = today.withDayOfMonth(today.lengthOfMonth());
		LocalDate to = last.minusDays(last.getDayOfWeek().getValue() % 7).plusDays(6);
		JsonNode month = ApiClient.json(client.get("/api/me/calendar")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.today").value(today.toString()))
			.andExpect(jsonPath("$.from").value(from.toString()))
			.andExpect(jsonPath("$.to").value(to.toString()))
			.andExpect(jsonPath("$.events[*].eventId").value(hasItem((int) holiday)))
			.andExpect(jsonPath("$.events[*].eventId").value(hasItem((int) spanning)))
			.andExpect(jsonPath("$.events[*].eventId").value(not(hasItem((int) hidden))))
			.andExpect(jsonPath("$.events[*].eventId").value(not(hasItem((int) longPast))))
			.andExpect(jsonPath("$.truncated").value(false)));
		List<Long> inMonth = ids(month);
		assertThat(inMonth).contains(open, ownDraft).doesNotContain(draft, notOpen, farAway, others);
		if (!today.plusDays(1).isAfter(to)) {
			assertThat(inMonth).contains(closingTomorrow);
		}
		client.get("/api/me/calendar")
			.andExpect(jsonPath("$.activities[?(@.activityId == %d)].role", ownDraft).value("INSTRUCTOR"))
			.andExpect(jsonPath("$.activities[?(@.activityId == %d)].published", ownDraft).value(false))
			.andExpect(jsonPath("$.activities[?(@.activityId == %d)].role", open).value("STUDENT"))
			.andExpect(jsonPath("$.activities[?(@.activityId == %d)].courseName", open).value("Database"));

		LocalDate sunday = today.minusDays(today.getDayOfWeek().getValue() % 7);
		client.get("/api/me/calendar?view=week&date=" + today)
			.andExpect(jsonPath("$.from").value(sunday.toString()))
			.andExpect(jsonPath("$.to").value(sunday.plusDays(6).toString()));

		// 하루 일정(화면 16)은 그날에 걸친 학사일정과 그날 마감인 활동이다.
		JsonNode day = ApiClient.json(client.get("/api/me/calendar/days/" + today)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.from").value(today.toString()))
			.andExpect(jsonPath("$.to").value(today.toString())));
		assertThat(ids(day)).containsExactlyInAnyOrder(open, ownDraft);
		assertThat(ids(day, "events", "eventId")).contains(holiday, spanning);

		client.get("/api/me/calendar?view=year").andExpect(status().isBadRequest());
		client.get("/api/me/calendar?date=2026-13-01").andExpect(status().isBadRequest());
		client.get("/api/me/calendar/days/" + today.plusDays(1000)).andExpect(status().isBadRequest());
		new ApiClient(this.mvc, null).get("/api/me/calendar").andExpect(status().isUnauthorized());
	}

	@Test
	void otherPeopleSeeNoneOfTheCallersCourseDeadlines() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long student = this.data.user("STUDENT");
		this.data.enroll(course, student);
		activity(course, true, "NULL", "CURRENT_DATE + INTERVAL 12 HOUR");
		ApiClient outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		outsider.get("/api/me/calendar").andExpect(jsonPath("$.activities.length()").value(0));
		new ApiClient(this.mvc, this.data.session(student)).get("/api/me/calendar")
			.andExpect(jsonPath("$.activities[*].courseId").value(contains((int) course)));
	}

	@Test
	void studentItemsSayWhetherTheCallerAlreadyFinishedTheActivity() throws Exception {
		LocalDate today = this.jdbc.sql("SELECT CURRENT_DATE").query(LocalDate.class).single();
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long student = this.data.user("STUDENT");
		long classmate = this.data.user("STUDENT");
		this.data.enroll(course, student);
		this.data.enroll(course, classmate);
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		LocalDate due = today.plusDays(1);
		JsonNode created = ApiClient.json(professor.postJson("/api/courses/" + course + "/assignments",
				"{\"weekNo\":1,\"title\":\"마감 과제\",\"closeAt\":\"" + due + "T12:00:00\",\"maxScore\":10}")
			.andExpect(status().isCreated())).get("assignment");
		long activity = created.get("activityId").asLong();
		ApiClient mine = new ApiClient(this.mvc, this.data.session(student));
		mine.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post("/api/courses/" + course + "/assignments/" + created.get("assignmentId").asLong() + "/submission")
			.header("Idempotency-Key", "calendar-done")
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"expectedVersion\":0,\"body\":\"제출\"}")).andExpect(status().isCreated());

		String path = "/api/me/calendar/days/" + due;
		mine.get(path).andExpect(jsonPath("$.activities[?(@.activityId == %d)].completed", activity).value(true));
		new ApiClient(this.mvc, this.data.session(classmate)).get(path)
			.andExpect(jsonPath("$.activities[?(@.activityId == %d)].completed", activity).value(false));
		// 담당 과목의 활동에는 완료 표시가 없다.
		professor.get(path)
			.andExpect(jsonPath("$.activities[?(@.activityId == %d)].role", activity).value("INSTRUCTOR"))
			.andExpect(jsonPath("$.activities[?(@.activityId == %d)].completed", activity).isEmpty());
	}

	@Test
	void statementCountDoesNotGrowWithTheNumberOfCourses() throws Exception {
		ApiClient few = studentWithCourses(2);
		ApiClient many = studentWithCourses(10);
		many.get("/api/me/calendar").andExpect(jsonPath("$.activities.length()").value(10));
		// 시계, 과목, 학사일정, 활동, 완료 판정(넷 이하)과 session 확인, transaction 문장뿐이다.
		assertThat(statements(many)).isEqualTo(statements(few)).isLessThanOrEqualTo(20);
	}

	private ApiClient studentWithCourses(int count) {
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		for (int i = 0; i < count; i++) {
			long c = course(prof);
			this.data.enroll(c, stu);
			activity(c, true, "NULL", "CURRENT_DATE + INTERVAL 12 HOUR");
		}
		return new ApiClient(this.mvc, this.data.session(stu));
	}

	/** 세 번 재어 가장 작은 값을 쓴다. 서버 전체 counter라 다른 작업이 끼면 늘어나기만 한다. */
	private long statements(ApiClient client) throws Exception {
		long min = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			long before = questions();
			client.get("/api/me/calendar").andExpect(status().isOk());
			min = Math.min(min, questions() - before);
		}
		return min;
	}

	private long questions() {
		return this.jdbc.sql("SHOW GLOBAL STATUS LIKE 'Questions'").query((rs, n) -> rs.getLong(2)).single();
	}

	private static List<Long> ids(JsonNode view) {
		return ids(view, "activities", "activityId");
	}

	private static List<Long> ids(JsonNode view, String list, String field) {
		List<Long> ids = new java.util.ArrayList<>();
		for (JsonNode item : view.get(list)) {
			ids.add(item.get(field).asLong());
		}
		return ids;
	}

	private long course(long prof) {
		long c = this.data.course();
		this.data.instructor(c, prof);
		return c;
	}

	/**
	 * @param openAt SQL 식
	 * @param closeAt SQL 식
	 */
	private long activity(long courseId, boolean published, String openAt, String closeAt) {
		long week = this.data.insert("INSERT INTO course_weeks (course_id, week_no) VALUES (:c, :n)",
				Map.of("c", courseId, "n", TestData.weekNo()));
		return this.data.insert("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, published_yn, open_at, close_at)
				VALUES (:c, :w, 'report', '과제', :pub, %s, %s)
				""".formatted(openAt, closeAt), Map.of("c", courseId, "w", week, "pub", published));
	}

	private long event(String title, boolean visible, LocalDate start, LocalDate end) {
		return this.data.insert("""
				INSERT INTO academic_calendar_events (event_title, start_date, end_date, is_public)
				VALUES (:title, :start, :end, :pub)
				""", Map.of("title", title, "start", start, "end", end, "pub", visible));
	}

}
