package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.identity.api.SchoolCourseContent;
import com.stackcorp.astra.identity.api.SchoolCourseContent.SchoolFile;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolActivity;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolWeek;
import com.stackcorp.astra.identity.session.SchoolSessionRegistry;
import com.stackcorp.astra.learning.SchoolContentTests.FakeSchool;

import tools.jackson.databind.JsonNode;

/**
 * 학교 과제: 상세와 본인 제출 정보는 학생이 열 때 그 학생의 학교 session으로, 본인이 낸 파일은 그 학생에게만, 제출은 학교에서, 할 일은
 * 가져온 행으로(ADR-0043).
 */
class SchoolAssignmentTests extends IntegrationTest {

	static final LocalDateTime OPEN = LocalDateTime.of(2026, 9, 1, 0, 0);

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	SchoolSessionRegistry registry;

	@Autowired
	SchoolActivities activities;

	long course;

	FakeSchool school;

	FakeSchool otherSchool;

	ApiClient student;

	ApiClient other;

	ApiClient professor;

	ApiClient outsider;

	long studentSession;

	long report;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.jdbc.sql("UPDATE courses SET legacy_kjkey = :key WHERE course_id = :id")
			.param("key", SchoolContentTests.KEY + this.course)
			.param("id", this.course)
			.update();
		long prof = this.data.user("PROFESSOR");
		long studentId = this.data.user("STUDENT");
		long otherId = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, studentId);
		this.data.enroll(this.course, otherId);
		this.student = new ApiClient(this.mvc, this.data.session(studentId));
		this.studentSession = session(studentId);
		this.other = new ApiClient(this.mvc, this.data.session(otherId));
		long otherSession = session(otherId);
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		// 이 학생은 과제(41)를 끝내지 않았고 설문(61)을 끝냈다. 다른 학생은 과제를 끝냈다.
		this.activities.save(this.course, studentId, room(false));
		this.activities.save(this.course, otherId, room(true));
		this.report = this.jdbc
			.sql("SELECT school_activity_id FROM school_activities WHERE course_id = :course AND school_item_id = '41'")
			.param("course", this.course)
			.query(Long.class)
			.single();
		this.school = new FakeSchool(SchoolContentTests.KEY + this.course);
		this.school.submittedAt = LocalDateTime.of(2026, 11, 10, 12, 1);
		this.school.submitted = new SchoolFile("SYNFILE000010", "SYNCONTENT0010", "내 보고서.pdf", 8192L,
				SchoolCourseContent.SUBMISSION, "41");
		this.otherSchool = new FakeSchool(SchoolContentTests.KEY + this.course);
		this.otherSchool.submittedAt = LocalDateTime.of(2026, 11, 11, 9, 0);
		this.otherSchool.submitted = new SchoolFile("SYNFILE000011", "SYNCONTENT0011", "다른 학생 보고서.pdf", 8192L,
				SchoolCourseContent.SUBMISSION, "41");
		assertThat(this.registry.keep(this.studentSession, this.school)).isTrue();
		assertThat(this.registry.keep(otherSession, this.otherSchool)).isTrue();
	}

	private static SchoolCourseRoom room(boolean reportDone) {
		LocalDateTime soon = LocalDateTime.of(2099, 11, 17, 23, 59);
		return new SchoolCourseRoom(List.of(new SchoolWeek(9, LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 16),
				List.of(activity("report", "41", "학교 과제", soon, false, false, reportDone),
						activity("test", "51", "학교 퀴즈", soon.plusDays(2), false, false, false),
						activity("survey", "61", "끝낸 설문", soon, false, false, true),
						activity("report", "42", "만료된 과제", soon, true, false, false),
						activity("test", "52", "오프라인 시험", soon, false, true, false),
						activity("report", "43", "마감 없는 과제", null, false, false, false)))));
	}

	private static SchoolActivity activity(String type, String id, String title, LocalDateTime closeAt,
			boolean expired, boolean offline, boolean done) {
		return new SchoolActivity(type, null, id, title, OPEN, closeAt, expired, null, null, offline, done,
				"https://eclass.example.ac.kr/connect?SEQ=" + id);
	}

	private long session(long userId) {
		return this.jdbc.sql("SELECT MAX(session_id) FROM user_sessions WHERE user_id = :user")
			.param("user", userId)
			.query(Long.class)
			.single();
	}

	@Test
	void theAssignmentListCarriesSchoolAssignmentsAfterNativeOnesWithoutAskingTheSchool() throws Exception {
		JsonNode items = ApiClient.json(this.student.get("/api/courses/" + this.course + "/assignments")
			.andExpect(status().isOk()));
		List<String> ids = new ArrayList<>();
		items.forEach((i) -> ids.add(i.path("assignmentId").asText()));
		assertThat(ids).hasSize(3).allMatch((id) -> id.startsWith("school-"));
		JsonNode first = items.get(0);
		assertThat(first.path("assignmentId").asText()).isEqualTo("school-" + this.report);
		assertThat(first.path("origin").asText()).isEqualTo("school");
		assertThat(first.path("status").asText()).isEqualTo("not_submitted");
		assertThat(first.path("phase").asText()).isEqualTo("open");
		assertThat(first.path("schoolUrl").asText()).endsWith("SEQ=41");
		assertThat(items.get(1).path("phase").asText()).isEqualTo("closed");
		JsonNode others = ApiClient.json(this.other.get("/api/courses/" + this.course + "/assignments")
			.andExpect(status().isOk()));
		assertThat(others.get(0).path("status").asText()).isEqualTo("submitted");
		JsonNode professors = ApiClient.json(this.professor.get("/api/courses/" + this.course + "/assignments")
			.andExpect(status().isOk()));
		assertThat(professors.size()).isZero();
		assertThat(this.school.assignmentDetails.get()).isZero();
	}

	@Test
	void openingShowsTheAssignmentAndOnlyTheStudentsOwnSubmittedFiles() throws Exception {
		String path = "/api/courses/" + this.course + "/assignments/school-" + this.report;
		JsonNode detail = ApiClient.json(this.student.get(path)
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store")));
		JsonNode info = detail.path("assignment");
		assertThat(info.path("assignmentId").asText()).isEqualTo("school-" + this.report);
		assertThat(info.path("origin").asText()).isEqualTo("school");
		assertThat(info.path("title").asText()).isEqualTo("학교 과제");
		assertThat(info.path("description").asText()).isEqualTo("과제 설명");
		assertThat(info.path("openAt").asText()).isEqualTo("2026-11-10T11:39:00");
		assertThat(info.path("closeAt").asText()).isEqualTo("2099-11-17T23:59:00");
		assertThat(info.path("files").get(0).path("name").asText()).isEqualTo("과제 안내.pdf");
		assertThat(detail.path("phase").asText()).isEqualTo("open");
		assertThat(detail.path("schoolUrl").asText()).endsWith("SEQ=41");
		JsonNode mine = detail.path("submission");
		assertThat(mine.path("status").asText()).isEqualTo("submitted");
		assertThat(mine.path("submittedAt").asText()).isEqualTo("2026-11-10T12:01:00");
		assertThat(mine.path("statusLabel").asText()).isEqualTo("정상제출");
		assertThat(mine.path("body").asText()).isEqualTo("낸 글");
		assertThat(mine.path("files")).hasSize(1);
		String myFile = mine.path("files").get(0).path("fileId").asText();
		assertThat(mine.path("files").get(0).path("name").asText()).isEqualTo("내 보고서.pdf");
		assertThat(this.school.assignmentDetails.get()).isEqualTo(1);

		// 본인 파일은 본인의 학교 session으로 받아 본인만 연다.
		String location = this.student.get("/api/courses/" + this.course + "/files/" + myFile + "/content")
			.andExpect(status().isSeeOther())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		this.student.get(location).andExpect(status().isOk());

		// 같은 과목의 다른 학생: 과제 첨부는 같은 파일, 낸 파일은 자기 것만. 앞 학생의 파일은 어떤 경로로도 열리지 않는다.
		JsonNode theirs = ApiClient.json(this.other.get(path).andExpect(status().isOk()));
		assertThat(theirs.path("assignment").path("files").get(0).path("fileId").asText())
			.isEqualTo(info.path("files").get(0).path("fileId").asText());
		assertThat(theirs.path("submission").path("files")).hasSize(1);
		assertThat(theirs.path("submission").path("files").get(0).path("name").asText()).isEqualTo("다른 학생 보고서.pdf");
		assertThat(theirs.path("submission").path("files").get(0).path("fileId").asText()).isNotEqualTo(myFile);
		this.other.get("/api/courses/" + this.course + "/files/" + myFile + "/content").andExpect(status().isNotFound());
		this.other.get(location).andExpect(status().isNotFound());
		this.professor.get(location).andExpect(status().isNotFound());
		// 과제 첨부는 과목 공용이다: 한 학생이 받아 둔 사본을 다른 학생도 연다.
		String shared = this.student
			.get("/api/courses/" + this.course + "/files/" + info.path("files").get(0).path("fileId").asText()
					+ "/content")
			.andExpect(status().isSeeOther())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		this.other.get(shared).andExpect(status().isOk());
		// 다시 읽어도 본인 파일이 남고(다른 학생이 읽어 지워지지 않는다) 학교 파일모음 목록으로도 지워지지 않는다.
		this.student.get("/api/courses/" + this.course + "/materials").andExpect(status().isOk());
		JsonNode again = ApiClient.json(this.student.get(path).andExpect(status().isOk()));
		assertThat(again.path("submission").path("files").get(0).path("name").asText()).isEqualTo("내 보고서.pdf");
		assertThat(this.school.downloads.get()).isEqualTo(2);
		assertThat(this.otherSchool.downloads.get()).isZero();
	}

	@Test
	void notSubmittedAssignmentsHaveNoSubmissionAndOthersCannotSeeIt() throws Exception {
		this.school.submittedAt = null;
		String path = "/api/courses/" + this.course + "/assignments/school-" + this.report;

		JsonNode detail = ApiClient.json(this.student.get(path).andExpect(status().isOk()));

		assertThat(detail.path("submission").path("status").asText()).isEqualTo("not_submitted");
		assertThat(detail.path("submission").path("files")).isEmpty();
		this.outsider.get(path).andExpect(status().isNotFound());
		this.professor.get(path).andExpect(status().isNotFound());
		this.student.get("/api/courses/" + this.course + "/assignments/school-999999999")
			.andExpect(status().isNotFound());
		this.school.expired = true;
		this.student.get(path)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("school_signin_required"));
		assertThat(this.registry.of(this.studentSession)).isEmpty();
	}

	@Test
	void submittingASchoolItemThroughStackIsRefused() throws Exception {
		String base = "/api/courses/" + this.course + "/assignments/school-" + this.report;
		this.student
			.send(MockMvcRequestBuilders.post(base + "/submission")
				.header("Idempotency-Key", "school-submit-1")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"expectedVersion\":0,\"body\":\"x\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("school_submit_at_school"));
		this.student
			.send(MockMvcRequestBuilders.put(base + "/submission/draft")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"body\":\"x\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("school_submit_at_school"));
		long quiz = this.jdbc
			.sql("SELECT school_activity_id FROM school_activities WHERE course_id = :course AND school_item_id = '51'")
			.param("course", this.course)
			.query(Long.class)
			.single();
		this.student.post("/api/courses/" + this.course + "/quizzes/school-" + quiz + "/attempts")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("school_submit_at_school"));
		this.outsider.post(base + "/submission").andExpect(status().isNotFound());
		assertThat(this.school.assignmentDetails.get()).isZero();
	}

	@Test
	void theTodoListHasOpenSchoolItemsWithADeadlineThatTheStudentHasNotFinished() throws Exception {
		this.professor.postJson("/api/courses/" + this.course + "/assignments",
				"{\"weekNo\":1,\"title\":\"서비스 과제\",\"closeAt\":\"2099-11-18T12:00:00\",\"maxScore\":10}")
			.andExpect(status().isCreated());

		JsonNode todo = ApiClient.json(this.student.get("/api/me/todo?courseId=" + this.course)
			.andExpect(status().isOk()));
		List<String> titles = new ArrayList<>();
		todo.path("items").forEach((i) -> titles.add(i.path("title").asText()));
		// 마감순: 학교 과제(17일), 서비스 과제(18일), 학교 퀴즈(19일). 끝낸 설문, 만료, 오프라인, 마감 없는 활동은 없다.
		assertThat(titles).containsExactly("학교 과제", "서비스 과제", "학교 퀴즈");
		assertThat(todo.path("total").asInt()).isEqualTo(3);
		JsonNode first = todo.path("items").get(0);
		assertThat(first.path("activityId").asText()).isEqualTo("school-" + this.report);
		assertThat(first.path("origin").asText()).isEqualTo("school");
		assertThat(first.path("type").asText()).isEqualTo("report");
		assertThat(first.path("schoolUrl").asText()).endsWith("SEQ=41");
		assertThat(first.has("assignmentId")).isFalse();
		JsonNode nativeItem = todo.path("items").get(1);
		assertThat(nativeItem.path("activityId").isNumber()).isTrue();
		assertThat(nativeItem.has("origin")).isFalse();
		assertThat(nativeItem.path("assignmentId").isNumber()).isTrue();
		// 쪽 나누기는 두 출처를 이은 순서로 겹치거나 빠지지 않는다.
		JsonNode page1 = ApiClient.json(this.student.get("/api/me/todo?courseId=" + this.course + "&size=1")
			.andExpect(status().isOk()));
		JsonNode page2 = ApiClient.json(this.student
			.get("/api/me/todo?courseId=" + this.course + "&size=2&cursor=" + page1.path("nextCursor").asText())
			.andExpect(status().isOk()));
		assertThat(page2.path("items").get(0).path("title").asText()).isEqualTo("서비스 과제");
		assertThat(page2.path("items").get(1).path("title").asText()).isEqualTo("학교 퀴즈");
		assertThat(page2.path("nextCursor").isNull()).isTrue();
		JsonNode count = ApiClient.json(this.student.get("/api/me/todo/count?courseId=" + this.course)
			.andExpect(status().isOk()));
		assertThat(count.path("total").asInt()).isEqualTo(3);
		// 다른 학생은 학교 과제를 끝냈다(가져온 완료 표시).
		JsonNode theirs = ApiClient.json(this.other.get("/api/me/todo?courseId=" + this.course)
			.andExpect(status().isOk()));
		assertThat(theirs.path("total").asInt()).isEqualTo(2);
		assertThat(this.school.assignmentDetails.get()).isZero();
		assertThat(this.school.lists.get()).isZero();
	}

}
