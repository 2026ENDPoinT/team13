package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolActivity;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceSession;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolAttendanceWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolGradeItem;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabus;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabusInstructor;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolSyllabusWeek;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideo;
import com.stackcorp.astra.identity.api.SchoolCourseScreens.SchoolVideoContent;
import com.stackcorp.astra.identity.session.SchoolSessionRegistry;
import com.stackcorp.astra.learning.SchoolContentTests.FakeSchool;

import tools.jackson.databind.JsonNode;

/**
 * 학교 영상 상세, 강의계획서, 출석, 성적: 그 학생이 화면을 열 때 그 학생의 학교 session으로 읽고, 서비스 화면의 경로와 모양으로 준다
 * (ADR-0043 4·5단계).
 */
class SchoolScreensTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	SchoolActivities activities;

	@Autowired
	SchoolSessionRegistry registry;

	long course;

	long video;

	FakeSchool school;

	FakeSchool otherSchool;

	ApiClient student;

	ApiClient other;

	ApiClient outsider;

	@BeforeEach
	void course() {
		// 학교에서 가져온 과목에는 STack 담당자가 없다.
		this.course = this.data.course();
		String key = SchoolContentTests.KEY + this.course;
		this.jdbc.sql("UPDATE courses SET legacy_kjkey = :key WHERE course_id = :id")
			.param("key", key)
			.param("id", this.course)
			.update();
		long studentId = this.data.user("STUDENT");
		long otherId = this.data.user("STUDENT");
		this.data.enroll(this.course, studentId);
		this.data.enroll(this.course, otherId);
		this.student = new ApiClient(this.mvc, this.data.session(studentId));
		this.other = new ApiClient(this.mvc, this.data.session(otherId));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		this.activities.save(this.course, studentId, new SchoolCourseRoom(List.of(new SchoolWeek(1,
				LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7),
				List.of(new SchoolActivity("lecture_weeks", null, "41", "1주 영상", LocalDateTime.of(2026, 9, 1, 0, 0),
						LocalDateTime.of(2026, 9, 13, 23, 59), false, "1 주 1차시", 1819, false, true,
						"https://eclass.example.ac.kr/connect?SEQ=41"),
						new SchoolActivity("report", "report1", "51", "과제 1", LocalDateTime.of(2026, 9, 1, 0, 0),
								LocalDateTime.of(2026, 10, 22, 23, 59), false, null, null, false, true,
								"https://eclass.example.ac.kr/connect?SEQ=51"))))));
		this.video = this.jdbc
			.sql("SELECT school_activity_id FROM school_activities WHERE course_id = :course AND school_item_id = '41'")
			.param("course", this.course)
			.query(Long.class)
			.single();
		this.school = new FakeSchool(key);
		this.otherSchool = new FakeSchool(key);
		assertThat(this.registry.keep(sessionOf(studentId), this.school)).isTrue();
		assertThat(this.registry.keep(sessionOf(otherId), this.otherSchool)).isTrue();
		this.school.video = new SchoolVideo("1 주 1차시", LocalDateTime.of(2026, 9, 1, 0, 0),
				LocalDateTime.of(2026, 9, 13, 23, 59),
				List.of(new SchoolVideoContent(1, "오프닝", 100, new BigDecimal("100.0"), new BigDecimal("12.5"), 1819,
						1694), new SchoolVideoContent(2, "참고 영상", 0, BigDecimal.ZERO, null, 0, 0)));
		this.school.attendance = List.of(new SchoolAttendanceWeek(1, List.of(
				new SchoolAttendanceSession(1, LocalDate.of(2026, 9, 3), LocalTime.of(14, 0), LocalTime.of(17, 0), "온라인",
						false, false, "attend", "출석"),
				new SchoolAttendanceSession(2, LocalDate.of(2026, 9, 3), null, null, "온라인", false, false, "absence",
						"결석 (0%)"))),
				new SchoolAttendanceWeek(2, List.of(new SchoolAttendanceSession(1, null, null, null, "휴강", false, true,
						null, null), new SchoolAttendanceSession(1, null, null, null, "보강", true, false, null, null))));
		this.otherSchool.attendance = List.of(new SchoolAttendanceWeek(1, List.of(new SchoolAttendanceSession(1,
				LocalDate.of(2026, 9, 3), null, null, "온라인", false, false, "tardy", "지각"))));
		this.school.grades = List.of(
				new SchoolGradeItem("report", "report1", "51", "과제 1", LocalDateTime.of(2026, 10, 22, 23, 59), true, false,
						true, new BigDecimal("8.5"), new BigDecimal("10")),
				new SchoolGradeItem("test", "test1", "61", "중간고사", null, false, true, false, null,
						new BigDecimal("90")));
		this.otherSchool.grades = List.of(new SchoolGradeItem("report", "report1", "51", "과제 1", null, false, false,
				true, new BigDecimal("3"), new BigDecimal("10")));
	}

	@Test
	void videoDetailIsReadOnOpenAndCarriesNothingPlayable() throws Exception {
		JsonNode view = ApiClient.json(this.student.get(path("/activities/school-" + this.video)).andExpect(status().isOk()));

		assertThat(view.path("activityId").asText()).isEqualTo("school-" + this.video);
		assertThat(view.path("origin").asText()).isEqualTo("school");
		assertThat(view.path("type").asText()).isEqualTo("lecture_weeks");
		assertThat(view.path("playable").asBoolean()).isFalse();
		assertThat(view.path("lectureItems").isArray()).isTrue();
		assertThat(view.path("lectureItems").size()).isZero();
		assertThat(view.path("schoolUrl").asText()).endsWith("SEQ=41");
		JsonNode video = view.path("video");
		assertThat(video.path("attendanceWeek").asText()).isEqualTo("1 주 1차시");
		assertThat(video.path("periodEndAt").asText()).isEqualTo("2026-09-13T23:59:00");
		assertThat(video.path("attendanceInSchoolOnly").asBoolean()).isTrue();
		JsonNode first = video.path("contents").get(0);
		assertThat(first.path("learningTimeSec").asInt()).isEqualTo(1819);
		assertThat(first.path("attendanceTimeSec").asInt()).isEqualTo(1694);
		assertThat(first.path("inPeriodPercent").decimalValue()).isEqualByComparingTo("100");
		assertThat(first.path("outOfPeriodPercent").decimalValue()).isEqualByComparingTo("12.5");
		assertThat(first.path("attendanceInSchoolOnly").asBoolean()).isTrue();
		assertThat(video.path("contents").get(1).path("attendanceInSchoolOnly").asBoolean()).isFalse();
		assertThat(view.toString()).doesNotContain("url\":\"http", "mediaUrl", "playback");
		assertThat(this.school.screens.get()).isEqualTo(1);
		// 학교 활동 ID로는 STack 학습 session과 하트비트를 시작할 수 없다.
		this.student.postJson(path("/lecture-items/school-" + this.video + "/learning-sessions"), "{}")
			.andExpect(status().is4xxClientError());
		this.student.postJson(path("/learning-sessions/school-1/heartbeats"),
				"{\"seq\":1,\"positionSec\":0,\"playing\":true}")
			.andExpect(status().is4xxClientError());
		this.outsider.get(path("/activities/school-" + this.video)).andExpect(status().isNotFound());
		assertThat(this.school.screens.get()).isEqualTo(1);
	}

	@Test
	void syllabusOfRegularCourseIsReadFromTheSchoolAndNonRegularIsNotProvided() throws Exception {
		this.school.syllabus = new SchoolSyllabus("2026/2학기", "Database", "999999/01001", "전선", "3", "목(6~8)",
				List.of(new SchoolSyllabusInstructor("담당 교수", "컴퓨터공학과", null, null, null, null, null)), "개요", "목표",
				null, "교재", null, "평가", List.of(new SchoolSyllabusWeek(1, "소개", null)));

		JsonNode view = ApiClient.json(this.student.get(path("/syllabus")).andExpect(status().isOk()));
		assertThat(view.path("origin").asText()).isEqualTo("school");
		assertThat(view.path("courseName").asText()).isEqualTo("Database");
		assertThat(view.path("overview").asText()).isEqualTo("개요");
		assertThat(view.path("classTimeRoom").asText()).isEqualTo("목(6~8)");
		assertThat(view.path("instructors").get(0).path("name").asText()).isEqualTo("담당 교수");
		assertThat(view.path("weeks").get(0).path("content").asText()).isEqualTo("소개");
		assertThat(view.has("version")).isFalse();
		this.outsider.get(path("/syllabus")).andExpect(status().isOk()).andExpect(jsonPath("$.origin").doesNotExist());
		assertThat(this.school.screens.get()).isEqualTo(1);

		this.jdbc.sql("UPDATE courses SET course_type = '비정규' WHERE course_id = :id").param("id", this.course).update();
		this.student.get(path("/syllabus"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("syllabus_not_provided"));
		assertThat(this.school.screens.get()).isEqualTo(1);
	}

	@Test
	void attendanceIsEachStudentsOwnAndUsesTheNativeShape() throws Exception {
		JsonNode mine = ApiClient.json(this.student.get(path("/attendance")).andExpect(status().isOk()));
		assertThat(mine.path("origin").asText()).isEqualTo("school");
		assertThat(mine.path("role").asText()).isEqualTo("STUDENT");
		JsonNode summary = mine.path("summary");
		assertThat(summary.path("sessions").asInt()).isEqualTo(3);
		assertThat(summary.path("present").asInt()).isEqualTo(1);
		assertThat(summary.path("absent").asInt()).isEqualTo(1);
		assertThat(summary.path("unmarked").asInt()).isEqualTo(1);
		JsonNode session = mine.path("weeks").get(0).path("sessions").get(1);
		assertThat(session.path("sessionId").asText()).isEqualTo("school-2");
		assertThat(session.path("record").path("status").asText()).isEqualTo("absent");
		assertThat(session.path("record").path("statusLabel").asText()).isEqualTo("결석 (0%)");
		assertThat(mine.path("weeks").get(1).path("sessions").get(0).path("cancelled").asBoolean()).isTrue();
		assertThat(mine.path("weeks").get(1).path("sessions").get(1).path("makeup").asBoolean()).isTrue();
		assertThat(mine.path("weeks").get(0).path("sessions").get(0).path("startTime").asText()).isEqualTo("14:00:00");
		assertThat(ApiClient.json(this.student.get(path("/attendance?week=2"))).path("weeks").size()).isEqualTo(1);

		// 다른 학생은 자기 학교 session으로 자기 출석만 받는다. 첫 학생의 값은 어디에도 남지 않는다.
		JsonNode theirs = ApiClient.json(this.other.get(path("/attendance")).andExpect(status().isOk()));
		assertThat(theirs.path("summary").path("late").asInt()).isEqualTo(1);
		assertThat(theirs.path("summary").path("absent").asInt()).isZero();
		assertThat(theirs.toString()).doesNotContain("결석 (0%)");
		assertThat(this.otherSchool.screens.get()).isEqualTo(1);
		this.outsider.get(path("/attendance")).andExpect(status().isNotFound());
	}

	@Test
	void gradesAreEachStudentsOwnAndUnreleasedScoresHaveNoProperty() throws Exception {
		JsonNode mine = ApiClient.json(this.student.get(path("/grades/me")).andExpect(status().isOk()));
		assertThat(mine.path("origin").asText()).isEqualTo("school");
		assertThat(mine.path("activitiesVisible").asBoolean()).isTrue();
		JsonNode report = mine.path("items").get(0);
		assertThat(report.path("itemId").asText()).isEqualTo("school-51");
		assertThat(report.path("type").asText()).isEqualTo("assignment");
		assertThat(report.path("activityId").asText()).startsWith("school-");
		assertThat(report.path("score").decimalValue()).isEqualByComparingTo("8.5");
		JsonNode exam = mine.path("items").get(1);
		assertThat(exam.path("type").asText()).isEqualTo("quiz");
		assertThat(exam.path("released").asBoolean()).isFalse();
		assertThat(exam.has("score")).isFalse();
		assertThat(exam.has("activityId")).isFalse();
		assertThat(exam.path("offline").asBoolean()).isTrue();
		assertThat(mine.path("total").path("score").decimalValue()).isEqualByComparingTo("8.5");
		assertThat(mine.path("total").path("maxScore").decimalValue()).isEqualByComparingTo("10");
		JsonNode quizzes = ApiClient.json(this.student.get(path("/grades/me?type=quiz")));
		assertThat(quizzes.path("items").size()).isEqualTo(1);

		JsonNode theirs = ApiClient.json(this.other.get(path("/grades/me")).andExpect(status().isOk()));
		assertThat(theirs.path("items").size()).isEqualTo(1);
		assertThat(theirs.path("items").get(0).path("score").decimalValue()).isEqualByComparingTo("3");
		assertThat(theirs.toString()).doesNotContain("8.5");
		this.outsider.get(path("/grades/me")).andExpect(status().isNotFound());
	}

	@Test
	void endedSchoolSessionAsksToSignInAgainOnEveryScreen() throws Exception {
		this.school.expired = true;
		for (String screen : List.of("/attendance", "/grades/me", "/syllabus", "/activities/school-" + this.video)) {
			this.school.expired = true;
			this.student.get(path(screen))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("school_signin_required"));
		}
		// 학교가 끝낸 session은 버린다. 다음 요청도 같은 답이고 학교에 다시 묻지 않는다.
		int asked = this.school.screens.get();
		this.student.get(path("/attendance"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("school_signin_required"));
		assertThat(this.school.screens.get()).isEqualTo(asked);
	}

	@Test
	void coursesWithStackInstructorsKeepTheNativeScreens() throws Exception {
		this.data.instructor(this.course, this.data.user("PROFESSOR"));

		this.student.get(path("/attendance")).andExpect(status().isOk()).andExpect(jsonPath("$.origin").doesNotExist());
		this.student.get(path("/grades/me")).andExpect(status().isOk()).andExpect(jsonPath("$.origin").doesNotExist());
		this.student.get(path("/syllabus")).andExpect(status().isOk()).andExpect(jsonPath("$.origin").doesNotExist());
		assertThat(this.school.screens.get()).isZero();
	}

	private String path(String rest) {
		return "/api/courses/" + this.course + rest;
	}

	private long sessionOf(long userId) {
		return this.jdbc.sql("SELECT MAX(session_id) FROM user_sessions WHERE user_id = :user")
			.param("user", userId)
			.query(Long.class)
			.single();
	}

}
