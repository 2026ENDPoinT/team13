package com.stackcorp.astra.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

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
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.catalog.api.CourseMates;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.catalog.api.MemberCourses;

import tools.jackson.databind.JsonNode;

/**
 * 폐강한 과목에는 구성원이 없다(SC-03). 담당·수강 행이 남아 있어도 과목 범위의 조회와 명령은 module과 관계없이 모두 404이고, 역할,
 * 명단, 함께 듣는 사람, 내 과목이 같은 판정을 한다. 같은 준비를 한 폐강하지 않은 과목에서는 같은 요청이 성공해 경로가 실제로 열려 있음을
 * 보인다.
 */
class CancelledCourseAccessTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	CourseMemberships memberships;

	@Autowired
	CourseRosters rosters;

	@Autowired
	CourseMates mates;

	@Autowired
	MemberCourses memberCourses;

	@Test
	void formerMembersOfACancelledCourseGetNotFoundInEveryModule() throws Exception {
		Classroom live = classroom();
		Classroom cancelled = classroom();
		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :id")
			.param("id", cancelled.course)
			.update();

		live.requests().forEach((name, request) -> {
			try {
				int code = request.call().andReturn().getResponse().getStatus();
				assertThat(code).as(name).isLessThan(400).isNotEqualTo(404);
			}
			catch (Exception ex) {
				throw new AssertionError(name, ex);
			}
		});
		// 동의하지 않은 과목의 요약 요청은 권한을 통과한 뒤 동의에서 막힌다.
		live.summaryJob().andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("llm_consent_required"));

		cancelled.requests().forEach((name, request) -> {
			try {
				request.call().andExpect(status().isNotFound());
			}
			catch (Exception ex) {
				throw new AssertionError(name, ex);
			}
		});
		cancelled.summaryJob().andExpect(status().isNotFound());
		// 명령은 아무것도 남기지 않았다.
		assertThat(count("SELECT COUNT(*) FROM posts p JOIN boards b ON b.board_id = p.board_id WHERE b.course_id = :c",
				cancelled.course)).isZero();
		assertThat(count("SELECT COUNT(*) FROM assignment_submissions WHERE assignment_id = :c", cancelled.assignment))
			.isZero();
		assertThat(count("SELECT COUNT(*) FROM attendance_records WHERE attendance_session_id = :c",
				cancelled.attendanceSession)).isZero();
	}

	@Test
	void roleRosterMatesAndMyCoursesAgreeThatACancelledCourseHasNoMembers() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(course, prof);
		this.data.enroll(course, stu);
		assertThat(this.memberships.roleOf(stu, course)).contains(CourseMemberships.CourseRole.STUDENT);
		assertThat(this.rosters.students(course)).containsExactly(stu);
		assertThat(this.mates.of(prof, course, null)).extracting(CourseMates.CourseMate::userId).containsExactly(stu);

		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :id").param("id", course).update();

		assertThat(this.memberships.roleOf(stu, course)).isEmpty();
		assertThat(this.memberships.roleOf(prof, course)).isEmpty();
		assertThat(this.rosters.students(course)).isEmpty();
		assertThat(this.mates.of(prof, course, null)).isEmpty();
		assertThat(this.memberCourses.of(stu)).extracting(MemberCourses.MemberCourse::courseId).doesNotContain(course);
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		professor.get("/api/courses/" + course).andExpect(status().isNotFound());
		professor.get("/api/courses/" + course + "/enrollment-requests").andExpect(status().isNotFound());
	}

	/** 담당자, 학생, 자료와 파일, 과제, 열린 스마트출석 회차, 온라인 강의가 있는 과목. */
	private Classroom classroom() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(course, prof);
		this.data.enroll(course, stu);
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));

		long upload = professor.uploaded(course, "week1.pdf", TestPdfs.pdf(1));
		JsonNode material = ApiClient.json(professor
			.postJson(base(course) + "/materials", "{\"weekNo\":1,\"title\":\"1주차\",\"uploadIds\":[" + upload + "]}")
			.andExpect(status().isCreated()));
		long fileId = material.get("files").get(0).get("fileId").asLong();
		long assignment = ApiClient.json(professor
			.postJson(base(course) + "/assignments",
					"{\"weekNo\":2,\"title\":\"과제\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":10}")
			.andExpect(status().isCreated())).get("assignment").get("assignmentId").asLong();
		long session = ApiClient.json(professor
			.postJson(base(course) + "/attendance-sessions",
					"{\"weekNo\":1,\"sessionNo\":1,\"classDate\":\"2026-09-17\",\"startTime\":\"14:00\",\"endTime\":\"15:00\",\"method\":\"manual\"}")
			.andExpect(status().isCreated())).get("sessionId").asLong();
		JsonNode round = ApiClient.json(professor
			.postJson(base(course) + "/attendance-sessions/" + session + "/smart-rounds", "{\"method\":\"pin\"}")
			.andExpect(status().isCreated()));
		long lecture = this.data.activity(course, "lecture_weeks", true, null, null);
		long item = this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path, duration_sec, required_sec)
				VALUES (:a, 1, '1주차 강의', 'video', :path, 600, 120)
				""", Map.of("a", lecture, "path", "vod/lecture-" + UUID.randomUUID().toString().substring(0, 8) + "/main.m3u8"));
		return new Classroom(course, professor, student, fileId, assignment, session, round.get("roundId").asLong(),
				round.get("pin").asString(), item);
	}

	private long count(String sql, long id) {
		return this.jdbc.sql(sql).param("c", id).query(Long.class).single();
	}

	private static String base(long courseId) {
		return "/api/courses/" + courseId;
	}

	@FunctionalInterface
	private interface Request {

		ResultActions call() throws Exception;

	}

	private record Classroom(long course, ApiClient professor, ApiClient student, long fileId, long assignment,
			long attendanceSession, long round, String pin, long lectureItem) {

		/** 학생과 담당자의 요청. 이름은 실패 메시지에 쓴다. 요청마다 다른 객체를 쓰므로 순서와 관계없다. */
		Map<String, Request> requests() {
			String course = base(this.course);
			return Map.of("materials list", () -> this.student.get(course + "/materials"),
					"file content", () -> this.student.get(course + "/files/" + this.fileId + "/content"),
					"assignment submit",
					() -> this.student.send(MockMvcRequestBuilders.post(course + "/assignments/" + this.assignment + "/submission")
						.header("Idempotency-Key", "submit-" + UUID.randomUUID())
						.contentType(MediaType.APPLICATION_JSON)
						.content("{\"expectedVersion\":0,\"body\":\"제출\",\"uploadIds\":[]}")),
					"qna post", () -> this.student.postJson(course + "/qna", "{\"title\":\"질문\"}"),
					"notice post", () -> this.professor.postJson(course + "/notices", "{\"title\":\"공지\"}"),
					"smart attendance check",
					() -> this.student.postJson(course + "/smart-rounds/" + this.round + "/check", "{\"pin\":\"" + this.pin + "\"}"),
					"learning session start",
					() -> this.student.post(course + "/lecture-items/" + this.lectureItem + "/learning-sessions"));
		}

		ResultActions summaryJob() throws Exception {
			return this.professor.post(base(this.course) + "/files/" + this.fileId + "/summary-jobs");
		}

	}

}
