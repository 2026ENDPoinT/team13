package com.stackcorp.astra.local;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestcontainersConfiguration;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.identity.session.DevLoginConfiguration;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.job.SummaryRunnerDriver;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 로컬 실행 구성(TestStackBackendApplication과 같은 설정): 개발용 로그인, 표본 데이터, CDN 없이 앱이 응답하는 전달 경로(ADR-0009).
 */
@SpringBootTest(properties = { "astra.summary.poll-interval=1h", "astra.summary.lease-renewal=1h",
		"astra.summary.lease=2h", "astra.transcoder.worker.poll-interval=1h", "astra.studychat.poll-interval=1h",
		"astra.studychat.sweep-interval=1h" })
@AutoConfigureMockMvc
@ActiveProfiles({ "local", "worker" })
@Import({ TestcontainersConfiguration.class, FakeLlm.Configuration.class, DevLoginConfiguration.class, LocalSeed.class })
class LocalRunTests {

	private static final String DEV_LOGIN = "/api/auth/dev-login";

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Autowired
	FakeLlm llm;

	@Autowired
	ObjectStore store;

	/** 표본이 쓰는 테이블. 두 번 넣어도 행 수가 같아야 한다. */
	private static final List<String> SEEDED_TABLES = List.of("users", "terms", "courses", "course_instructors",
			"course_enrollments", "course_weeks", "course_schedules", "course_seat_counters", "non_regular_offerings",
			"non_regular_targets", "academic_calendar_events", "boards", "posts", "comments", "messages",
			"message_recipients", "surveys", "survey_targets", "survey_questions", "survey_question_options",
			"learning_activities", "assignments", "assignment_submissions", "assignment_submission_versions",
			"quizzes", "quiz_questions", "quiz_question_options", "quiz_attempts", "quiz_attempt_answers",
			"quiz_attempt_answer_options", "grade_settings", "grade_items", "grade_scores", "attendance_settings",
			"attendance_sessions", "attendance_records", "attendance_record_changes", "course_groups",
			"course_group_members", "course_syllabi", "course_syllabus_weeks", "syllabus_instructors",
			"online_lecture_items", "online_lecture_item_progress", "learning_sessions", "learning_heartbeats",
			"learning_credit_ledger", "activity_progress", "course_continue_pointers", "notification_events",
			"notifications", "file_blobs", "files", "upload_sessions", "filebox_items", "filebox_quotas",
			"llm_consents");

	@Test
	void studentSeesSeededDataOnEveryScreen() throws Exception {
		ApiClient me = new ApiClient(this.mvc, login(LocalSeed.STUDENT_REF).getValue());
		long student = LocalSql.user(this.jdbc, LocalSeed.STUDENT_REF);
		long bi = LocalSql.course(this.jdbc, "bi");
		String course = "/api/courses/" + bi;

		JsonNode home = ok(me, "/api/me/home");
		assertThat(home.get("todo").get("items")).isNotEmpty();
		assertThat(home.get("newGrades")).isNotEmpty();
		JsonNode inbox = ok(me, "/api/me/notifications?size=50");
		assertThat(inbox.get("items")).isNotEmpty();
		assertThat(home.get("unreadNotifications").asInt()).isEqualTo(inbox.get("unreadCount").asInt())
			.isEqualTo(count(inbox.get("items"), (n) -> !n.get("read").asBoolean()))
			.isLessThan(inbox.get("items").size());

		// 할 일: 임시저장만 한 과제는 남고, 최종 제출한 과제와 응시 횟수를 다 쓴 퀴즈는 빠진다.
		JsonNode todo = ok(me, "/api/me/todo?size=50");
		List<String> due = titles(todo.get("items"));
		assertThat(due).contains(LocalAssignmentSeed.DRAFTED, LocalQuizSeed.STARTABLE)
			.doesNotContain(LocalAssignmentSeed.SUBMITTED, LocalQuizSeed.SUBMITTED);
		assertThat(todo.get("total").asInt()).isEqualTo(due.size());

		JsonNode assignments = ok(me, course + "/assignments");
		assertThat(assignments).isNotEmpty();
		assertThat(field(assignments, LocalAssignmentSeed.SUBMITTED, "status")).isEqualTo("submitted");
		assertThat(field(assignments, LocalAssignmentSeed.DRAFTED, "status")).isEqualTo("not_submitted");
		JsonNode drafted = ok(me, course + "/assignments/" + id(assignments, LocalAssignmentSeed.DRAFTED, "assignmentId"));
		assertThat(drafted.get("submission").get("version").asInt()).isZero();
		assertThat(drafted.get("submission").get("draft").get("body").asString()).isNotBlank();
		JsonNode released = ok(me, course + "/assignments/" + id(assignments, LocalAssignmentSeed.RELEASED, "assignmentId"));
		assertThat(released.get("submission").get("grade").get("score").decimalValue()).isEqualByComparingTo("9");
		JsonNode hidden = ok(me, course + "/assignments/" + id(assignments, LocalAssignmentSeed.UNRELEASED, "assignmentId"));
		assertThat(hidden.get("submission").has("grade")).isFalse();

		// 응시 한 번이 허용된 퀴즈를 이미 냈으면 시작할 수 없고, 공개한 점수가 보인다.
		JsonNode quizzes = ok(me, course + "/quizzes");
		assertThat(titles(quizzes)).contains(LocalQuizSeed.SUBMITTED, LocalQuizSeed.STARTABLE, LocalQuizSeed.PENDING)
			.doesNotContain(LocalQuizSeed.UPCOMING);
		JsonNode done = ok(me, course + "/quizzes/" + id(quizzes, LocalQuizSeed.SUBMITTED, "quizId"));
		assertThat(done.get("canStart").asBoolean()).isFalse();
		assertThat(done.get("attemptsUsed").asInt()).isEqualTo(done.get("quiz").get("attemptLimit").asInt());
		assertThat(done.get("score").decimalValue()).isEqualByComparingTo(done.get("quiz").get("maxScore").decimalValue());
		assertThat(ok(me, course + "/quizzes/" + id(quizzes, LocalQuizSeed.STARTABLE, "quizId")).get("canStart").asBoolean())
			.isTrue();

		// 합계는 공개한 항목의 점수만 더한다.
		JsonNode grades = ok(me, course + "/grades/me");
		List<JsonNode> scored = new ArrayList<>();
		grades.get("items").forEach((i) -> {
			if (i.get("released").asBoolean() && i.has("score")) {
				scored.add(i);
			}
		});
		assertThat(scored).hasSizeLessThan(grades.get("items").size());
		assertThat(grades.get("total").get("itemCount").asInt()).isEqualTo(scored.size());
		assertThat(grades.get("total").get("score").decimalValue()).isEqualByComparingTo(
				scored.stream().map((i) -> i.get("score").decimalValue()).reduce(BigDecimal.ZERO, BigDecimal::add));

		// 휴강한 차시는 세지 않고, 상태별 수의 합이 차시 수다.
		JsonNode attendance = ok(me, course + "/attendance");
		JsonNode summary = attendance.get("summary");
		assertThat(summary.get("sessions").asInt()).isEqualTo(sessions(attendance, false));
		assertThat(summary.get("present").asInt() + summary.get("late").asInt() + summary.get("absent").asInt()
				+ summary.get("excused").asInt() + summary.get("unmarked").asInt())
			.isEqualTo(summary.get("sessions").asInt());
		assertThat(sessions(attendance, true)).isEqualTo(1);

		JsonNode groups = ok(me, course + "/groups");
		assertThat(groups).hasSize(1);
		assertThat(groups.get(0).get("members")).hasSize(3);
		JsonNode syllabus = ok(me, course + "/syllabus");
		assertThat(syllabus.get("weeks")).hasSize(15);
		assertThat(syllabus.get("instructors").get(0).get("name").asString()).isEqualTo("박은주");

		// 지난 강의는 콘텐츠 둘을 다 인정받아 활동이 완료됐고, 지금 강의의 영상은 아직 준비 중이다.
		JsonNode progress = ok(me, course + "/lecture-progress");
		JsonNode first = progress.get(0);
		assertThat(first.get("metCount").asInt()).isEqualTo(first.get("itemCount").asInt()).isEqualTo(2);
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM activity_progress p JOIN learning_activities a ON a.activity_id = p.activity_id
				 WHERE p.user_id = :u AND a.course_id = :c AND a.activity_type = 'lecture_weeks' AND p.completed_at IS NOT NULL
				""").param("u", student).param("c", bi).query(Integer.class).single()).isEqualTo(1);
		long current = this.jdbc.sql("SELECT activity_id FROM learning_activities WHERE course_id = :c AND title LIKE '10주차 온라인%'")
			.param("c", bi)
			.query(Long.class)
			.single();
		assertThat(ok(me, course + "/activities/" + current).get("lectureItems").get(0).get("state").asString())
			.isEqualTo("processing");

		JsonNode calendar = ok(me, "/api/me/calendar");
		assertThat(calendar.get("events")).isNotEmpty();
		assertThat(calendar.get("activities")).isNotEmpty();
		JsonNode week = ok(me, "/api/me/timetable");
		String today = week.get("today").asString();
		assertThat(count(week.get("slots"), (s) -> s.get("date").asString().equals(today))).isPositive();

		JsonNode filebox = ok(me, "/api/filebox/items");
		assertThat(filebox.get("items")).isNotEmpty();
		assertThat(filebox.get("usage").get("itemCount").asInt()).isEqualTo(this.jdbc.sql(
				"SELECT COUNT(*) FROM filebox_items WHERE owner_user_id = :u AND item_state IN ('active', 'trashed')")
			.param("u", student)
			.query(Integer.class)
			.single());
		assertThat(ok(me, "/api/filebox/trash").get("items")).hasSize(1);
		long pdf = this.jdbc.sql("SELECT filebox_item_id FROM filebox_items WHERE owner_user_id = :u AND item_name LIKE '%.pdf'")
			.param("u", student)
			.query(Long.class)
			.single();
		me.get("/api/filebox/items/" + pdf + "/content").andExpect(status().isOk());

		JsonNode messages = ok(me, "/api/messages/inbox");
		assertThat(messages.get("items")).isNotEmpty();
		assertThat(messages.get("unreadCount").asInt()).isEqualTo(count(messages.get("items"), (m) -> !m.get("read").asBoolean()));
		assertThat(ok(me, course + "/notices").get("items")).isNotEmpty();
		assertThat(ok(me, course + "/qna").get("items")).isNotEmpty();
		assertThat(ok(me, course + "/open-board/posts").get("items")).isNotEmpty();
	}

	@Test
	void professorSeesSeededDataOnEveryScreen() throws Exception {
		ApiClient me = new ApiClient(this.mvc, login(LocalSeed.PROFESSOR_REF).getValue());
		long bi = LocalSql.course(this.jdbc, "bi");
		String course = "/api/courses/" + bi;
		int roster = this.jdbc.sql("""
				SELECT COUNT(*) FROM course_enrollments WHERE course_id = :c AND enrollment_status = 'ENROLLED'
				   AND enrollment_role = 'STUDENT'
				""").param("c", bi).query(Integer.class).single();
		assertThat(roster).isEqualTo(9);

		JsonNode home = ok(me, "/api/me/home");
		assertThat(home.get("courses")).isNotEmpty();
		assertThat(home.get("todo").get("total").asInt()).isZero();
		assertThat(ok(me, "/api/me/todo").get("items")).isEmpty();

		// 제출 수는 최종 제출한 학생 수다. 임시저장은 세지 않는다.
		JsonNode assignments = ok(me, course + "/assignments");
		assertThat(assignments).hasSize(4);
		assertThat(field(assignments, LocalAssignmentSeed.RELEASED, "submittedCount")).isEqualTo("9");
		assertThat(field(assignments, LocalAssignmentSeed.RELEASED, "lateCount")).isEqualTo("1");
		long drafted = id(assignments, LocalAssignmentSeed.DRAFTED, "assignmentId");
		assertThat(field(assignments, LocalAssignmentSeed.DRAFTED, "submittedCount")).isEqualTo(String.valueOf(this.jdbc
			.sql("SELECT COUNT(*) FROM assignment_submissions WHERE assignment_id = :a AND current_version_no > 0")
			.param("a", drafted)
			.query(Integer.class)
			.single()));
		assertThat(ok(me, course + "/assignments/" + id(assignments, LocalAssignmentSeed.RELEASED, "assignmentId")
				+ "/submissions")).hasSize(9);

		// 채점 대기 수는 단답형 채점을 기다리는 응시 수다. 응시가 있는 퀴즈는 문항을 바꿀 수 없다.
		JsonNode quizzes = ok(me, course + "/quizzes");
		assertThat(titles(quizzes)).contains(LocalQuizSeed.UPCOMING);
		long pending = id(quizzes, LocalQuizSeed.PENDING, "quizId");
		assertThat(field(quizzes, LocalQuizSeed.PENDING, "awaitingGradingCount")).isEqualTo(String.valueOf(this.jdbc
			.sql("SELECT COUNT(*) FROM quiz_attempts WHERE quiz_id = :q AND status = 'submitted'")
			.param("q", pending)
			.query(Integer.class)
			.single())).isEqualTo("6");
		JsonNode attempts = ok(me, course + "/quizzes/" + pending + "/attempts");
		assertThat(count(attempts.get("items"), (a) -> a.get("pendingCount").asInt() > 0)).isEqualTo(6);
		assertThat(ok(me, course + "/quizzes/" + pending).get("questionsLocked").asBoolean()).isTrue();

		// 성적부 학생 수는 수강생 수, 직접 입력 항목의 채점 수는 점수 행 수다.
		JsonNode gradebook = ok(me, course + "/gradebook");
		assertThat(gradebook.get("totalStudents").asInt()).isEqualTo(roster);
		assertThat(gradebook.get("students")).hasSize(roster);
		JsonNode manual = find(gradebook.get("items"), "name", LocalGradebookSeed.MANUAL);
		assertThat(manual.get("gradedCount").asInt()).isEqualTo(roster);
		assertThat(manual.get("weight").decimalValue()).isEqualByComparingTo("30");
		assertThat(manual.get("released").asBoolean()).isTrue();

		// 출석부: 차시마다 상태별 수의 합이 수강생 수이고, 휴강한 차시에는 기록이 없다.
		JsonNode attendanceRoster = ok(me, course + "/attendance/roster");
		assertThat(attendanceRoster.get("total").asInt()).isEqualTo(roster);
		assertThat(attendanceRoster.get("students")).hasSize(roster);
		JsonNode attendance = ok(me, course + "/attendance");
		int sessions = 0;
		for (JsonNode week : attendance.get("weeks")) {
			for (JsonNode session : week.get("sessions")) {
				int marked = 0;
				for (JsonNode n : session.get("counts")) {
					marked += n.asInt();
				}
				assertThat(marked).isEqualTo(session.get("cancelled").asBoolean() ? 0 : roster);
				sessions++;
			}
		}
		assertThat(sessions).isEqualTo(attendanceRoster.get("sessions").size()).isEqualTo(7);
		assertThat(ok(me, course + "/attendance/settings").get("latesPerAbsence").asInt()).isEqualTo(3);

		JsonNode groups = ok(me, course + "/groups");
		assertThat(groups).hasSize(3);
		int members = 0;
		for (JsonNode g : groups) {
			members += g.get("members").size();
		}
		assertThat(members).isEqualTo(roster);
		assertThat(ok(me, course + "/syllabus").get("version").asInt()).isEqualTo(1);
		JsonNode progress = ok(me, course + "/lecture-progress/students");
		assertThat(progress.get("students")).hasSize(roster);
		assertThat(progress.get("items")).hasSize(3);

		assertThat(ok(me, "/api/me/calendar").get("activities")).isNotEmpty();
		assertThat(ok(me, "/api/me/timetable").get("slots")).isNotEmpty();
		JsonNode inbox = ok(me, "/api/me/notifications");
		assertThat(inbox.get("items")).hasSize(2);
		assertThat(inbox.get("unreadCount").asInt()).isEqualTo(1);
		assertThat(ok(me, "/api/filebox/items").get("items")).isNotEmpty();
		assertThat(ok(me, "/api/filebox/trash").get("items").get(0).get("itemCount").asInt()).isEqualTo(2);
		assertThat(ok(me, "/api/messages/inbox").get("items")).isNotEmpty();
		assertThat(ok(me, "/api/messages/sent").get("items")).isNotEmpty();
		assertThat(ok(me, course + "/qna").get("items")).isNotEmpty();
		assertThat(ok(me, course + "/notices").get("items")).isNotEmpty();
		// 외부 LLM 처리 동의는 표본에 넣지 않는다.
		JsonNode consents = ok(me, "/api/courses/" + LocalSql.course(this.jdbc, "ai") + "/llm-consents");
		assertThat(count(consents, (c) -> c.get("granted").asBoolean())).isZero();

		ApiClient second = new ApiClient(this.mvc, login(LocalPeopleSeed.SECOND_PROFESSOR_REF).getValue());
		assertThat(ok(second, "/api/me/courses")).hasSize(2);
	}

	@Test
	void seedingTwiceChangesNoRowCount() {
		Map<String, Long> before = rowCounts();
		LocalSeed.seed(this.jdbc, this.store);
		assertThat(rowCounts()).isEqualTo(before);
	}

	private Map<String, Long> rowCounts() {
		Map<String, Long> counts = new LinkedHashMap<>();
		for (String table : SEEDED_TABLES) {
			counts.put(table, this.jdbc.sql("SELECT COUNT(*) FROM " + table).query(Long.class).single());
		}
		return counts;
	}

	private static JsonNode ok(ApiClient client, String path) throws Exception {
		return ApiClient.json(client.get(path).andExpect(status().isOk()));
	}

	private static List<String> titles(JsonNode items) {
		List<String> titles = new ArrayList<>();
		items.forEach((i) -> titles.add(i.get("title").asString()));
		return titles;
	}

	private static JsonNode find(JsonNode items, String key, String value) {
		for (JsonNode item : items) {
			if (item.get(key).asString().equals(value)) {
				return item;
			}
		}
		throw new AssertionError(value + " not found");
	}

	private static String field(JsonNode items, String title, String name) {
		return find(items, "title", title).get(name).asString();
	}

	private static long id(JsonNode items, String title, String name) {
		return find(items, "title", title).get(name).asLong();
	}

	private static int count(JsonNode items, Predicate<JsonNode> test) {
		int n = 0;
		for (JsonNode item : items) {
			n += test.test(item) ? 1 : 0;
		}
		return n;
	}

	/** 학생 출석 화면의 차시 중 휴강한(또는 하지 않은) 차시 수. */
	private static int sessions(JsonNode attendance, boolean cancelled) {
		int n = 0;
		for (JsonNode week : attendance.get("weeks")) {
			n += count(week.get("sessions"), (s) -> s.get("cancelled").asBoolean() == cancelled);
		}
		return n;
	}

	@Test
	void logsInASeededPersonaOnlyFromThisMachine() throws Exception {
		MockHttpServletResponse response = this.mvc.perform(devLogin(LocalSeed.STUDENT_REF))
			.andExpect(status().isNoContent())
			.andReturn()
			.getResponse();
		Cookie session = response.getCookie("ASTRA_SESSION");
		assertThat(session.isHttpOnly()).isTrue();
		this.mvc.perform(get("/api/me").cookie(session))
			.andExpect(jsonPath("$.name").value("박민준"))
			.andExpect(jsonPath("$.role").value("STUDENT"));
		this.mvc.perform(get("/api/me/courses").cookie(session))
			.andExpect(jsonPath("$.length()").value(7))
			.andExpect(jsonPath("$[?(@.key == 'bi')].role").value("STUDENT"))
			.andExpect(jsonPath("$[?(@.key == 'bi')].instructors[0]").value("박은주"));
		this.mvc.perform(get("/api/me/home").cookie(session))
			.andExpect(jsonPath("$.courses.length()").value(7))
			.andExpect(jsonPath("$.notices.length()").value(5))
			.andExpect(jsonPath("$.notices[0].pinned").value(true));

		// proxy 뒤에서 온 요청, 다른 컴퓨터의 요청은 로그인 경로가 없는 것처럼 답한다.
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("X-Forwarded-For", "203.0.113.9, 127.0.0.1"))
			.andExpect(status().isNotFound());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("CF-Connecting-IP", "203.0.113.9"))
			.andExpect(status().isNotFound());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).with((request) -> {
			request.setRemoteAddr("10.0.0.5");
			return request;
		})).andExpect(status().isNotFound());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("X-Forwarded-For", "127.0.0.1"))
			.andExpect(status().isNoContent());

		this.mvc.perform(devLogin("no-such-user")).andExpect(status().isUnauthorized());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("Origin", "https://evil.example"))
			.andExpect(status().isForbidden());
		this.mvc.perform(devLogin(LocalSeed.STUDENT_REF).header("Origin", "http://127.0.0.1:4190"))
			.andExpect(status().isNoContent());
		this.mvc
			.perform(post(DEV_LOGIN).contentType(MediaType.APPLICATION_JSON)
				.content("{\"externalRef\":\"" + LocalSeed.STUDENT_REF + "\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void servesPageImagesOnlyWithTheCookieIssuedForThatDocument() throws Exception {
		this.llm.reset(FakeLlm::honest);
		ApiClient professor = new ApiClient(this.mvc, login(LocalSeed.PROFESSOR_REF).getValue());
		Cookie studentSession = login(LocalSeed.STUDENT_REF);
		long course = this.jdbc.sql("SELECT course_id FROM courses WHERE legacy_kjkey = 'bi'").query(Long.class).single();
		String base = publishedSummary(professor, studentSession, course, 2);
		Cookie delivery = this.lastDeliveryCookie;
		assertThat(delivery.getName()).isEqualTo("ASTRA-Delivery");
		assertThat(delivery.getPath()).isEqualTo(base);
		assertThat(delivery.isHttpOnly()).isTrue();

		this.mvc.perform(get(base + "001.jpg").cookie(studentSession, delivery))
			.andExpect(status().isOk())
			.andExpect(content().contentType("image/jpeg"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Cache-Control", "max-age=300, private"));
		this.mvc.perform(get(base + "003.jpg").cookie(studentSession, delivery)).andExpect(status().isNotFound());
		this.mvc.perform(get(base + "document.json").cookie(studentSession, delivery)).andExpect(status().isNotFound());

		// cookie가 없거나, 값이 바뀌었거나, 다른 문서의 cookie면 거부한다.
		this.mvc.perform(get(base + "001.jpg").cookie(studentSession)).andExpect(status().isForbidden());
		this.mvc.perform(get(base + "001.jpg").cookie(studentSession, new Cookie("ASTRA-Delivery", "9999999999.AAAA")))
			.andExpect(status().isForbidden());
		String expired = "1." + delivery.getValue().substring(delivery.getValue().indexOf('.') + 1);
		this.mvc.perform(get(base + "001.jpg").cookie(studentSession, new Cookie("ASTRA-Delivery", expired)))
			.andExpect(status().isForbidden());
		String otherBase = publishedSummary(professor, studentSession, course, 1);
		assertThat(otherBase).isNotEqualTo(base);
		this.mvc.perform(get(otherBase + "001.jpg").cookie(studentSession, delivery)).andExpect(status().isForbidden());
		// 서명한 cookie가 있어도 로그인하지 않은 요청은 받지 않는다.
		this.mvc.perform(get(base + "001.jpg").cookie(delivery)).andExpect(status().isUnauthorized());
	}

	private Cookie lastDeliveryCookie;

	// 교수자가 화면에서 보는 처리 조건을 읽고 그대로 동의한다(ADR-0032).
	private void consentToPageSummary(ApiClient professor, long course) throws Exception {
		JsonNode policy = ApiClient.json(professor.get("/api/llm/policy").andExpect(status().isOk()));
		professor
			.send(MockMvcRequestBuilders.put("/api/courses/" + course + "/llm-consents/page_summary")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"noticeVersion\":\"" + policy.get("noticeVersion").asString() + "\",\"profileDigest\":\""
						+ policy.get("profileDigest").asString() + "\",\"acknowledged\":true}"))
			.andExpect(status().isOk());
	}

	/** 자료를 올리고 요약을 게시한 뒤, 학생이 받은 pageImageBase를 돌려준다. */
	private String publishedSummary(ApiClient professor, Cookie studentSession, long course, int pages) throws Exception {
		while (SummaryRunnerDriver.runOnce(this.context)) {
			// 남은 작업을 비운다.
		}
		long uploadId = professor.uploaded(course, "week" + pages + ".pdf", TestPdfs.pdf(pages));
		JsonNode material = ApiClient.json(professor
			.postJson("/api/courses/" + course + "/materials",
					"{\"weekNo\":9,\"title\":\"9주차\",\"uploadIds\":[" + uploadId + "]}")
			.andExpect(status().isCreated()));
		long fileId = material.get("files").get(0).get("fileId").asLong();
		consentToPageSummary(professor, course);
		long jobId = ApiClient
			.json(professor.post("/api/courses/" + course + "/files/" + fileId + "/summary-jobs")
				.andExpect(status().isAccepted()))
			.get("id")
			.asLong();
		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();
		professor.post("/api/courses/" + course + "/summary-jobs/" + jobId + "/publish").andExpect(status().isOk());
		MockHttpServletResponse response = this.mvc
			.perform(get("/api/courses/" + course + "/files/" + fileId + "/summary").cookie(studentSession))
			.andExpect(status().isOk())
			.andReturn()
			.getResponse();
		this.lastDeliveryCookie = response.getCookie("ASTRA-Delivery");
		return ApiClient.json(response).get("pageImageBase").asString();
	}

	private Cookie login(String externalRef) throws Exception {
		return this.mvc.perform(devLogin(externalRef)).andReturn().getResponse().getCookie("ASTRA_SESSION");
	}

	private MockHttpServletRequestBuilder devLogin(String externalRef) throws Exception {
		Cookie csrf = this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return post(DEV_LOGIN).contentType(MediaType.APPLICATION_JSON)
			.content("{\"externalRef\":\"" + externalRef + "\"}")
			.cookie(csrf)
			.header("X-XSRF-TOKEN", csrf.getValue());
	}

}
