package com.stackcorp.astra.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 과목 목록은 담당하거나 수강이 확정된 과목만 돌려주고, 구성원이 아닌 과목은 있는지도 알려주지 않는다.
 */
class CourseApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Test
	void listsOnlyCoursesTheCallerTeachesOrIsEnrolledInWithTheRole() throws Exception {
		long user = this.data.user("PROFESSOR");
		long teaching = this.data.course();
		long attending = this.data.course();
		long dropped = this.data.course();
		long unrelated = this.data.course();
		this.data.instructor(teaching, user);
		this.data.enroll(attending, user);
		this.data.enroll(dropped, user);
		this.jdbc.sql("UPDATE course_enrollments SET enrollment_status = 'CANCELLED' WHERE course_id = :c AND user_id = :u")
			.param("c", dropped)
			.param("u", user)
			.update();
		Cookie session = new Cookie("ASTRA_SESSION", this.data.session(user));

		this.mvc.perform(get("/api/me/courses").cookie(session))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[?(@.courseId == %d)].role", teaching).value("INSTRUCTOR"))
			.andExpect(jsonPath("$[?(@.courseId == %d)].role", attending).value("STUDENT"))
			.andExpect(jsonPath("$[?(@.courseId == %d)]", dropped).isEmpty())
			.andExpect(jsonPath("$[?(@.courseId == %d)]", unrelated).isEmpty());

		this.mvc.perform(get("/api/courses/" + teaching).cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.role").value("INSTRUCTOR"))
			.andExpect(jsonPath("$.instructors[0]").value("테스트 PROFESSOR"));
	}

	@Test
	void hidesCoursesFromNonMembersAndAnonymousCallers() throws Exception {
		long course = this.data.course();
		Cookie outsider = new Cookie("ASTRA_SESSION", this.data.session(this.data.user("STUDENT")));
		this.mvc.perform(get("/api/courses/" + course).cookie(outsider))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("not_found"));
		this.mvc.perform(get("/api/courses/999999999").cookie(outsider)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/courses/not-a-number").cookie(outsider)).andExpect(status().isBadRequest());
		this.mvc.perform(get("/api/me/courses")).andExpect(status().isUnauthorized());
	}

	@Test
	void instructorSeesTheRosterTotalAsLearnersAndStudentsDoNot() throws Exception {
		long prof = this.data.user("PROFESSOR");
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		long taught = this.data.course();
		long empty = this.data.course();
		long attended = this.data.course();
		this.data.instructor(taught, prof);
		this.data.instructor(empty, prof);
		this.data.enroll(attended, prof);
		long alice = this.data.user("STUDENT");
		long bob = this.data.user("STUDENT");
		long carol = this.data.user("STUDENT");
		long auditor = this.data.user("STUDENT");
		long ta = this.data.user("STUDENT");
		this.data.enroll(taught, alice);
		// 한 사람의 수강 행이 둘이어도 한 명이다.
		enroll(taught, alice, "AUDIT", "AUDITOR", "ENROLLED");
		this.data.enroll(taught, bob);
		enroll(taught, auditor, "AUDIT", "AUDITOR", "ENROLLED");
		// 취소한 학생, 신청 중인 사람, 담당자로도 등록된 사람은 명단에 없다.
		this.data.enroll(taught, carol);
		cancel(taught, carol);
		enroll(taught, this.data.user("STUDENT"), "AUDIT", "AUDITOR", "REQUESTED");
		this.data.enroll(taught, ta);
		this.jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role) VALUES (:c, :u, 'TA')")
			.param("c", taught)
			.param("u", ta)
			.update();
		this.data.enroll(attended, alice);
		this.data.enroll(empty, carol);
		cancel(empty, carol);

		professor.get("/api/courses/" + taught + "/students").andExpect(jsonPath("$.total").value(3));
		professor.get("/api/courses/" + empty + "/students").andExpect(jsonPath("$.total").value(0));
		professor.get("/api/me/courses")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3))
			.andExpect(jsonPath("$[?(@.courseId == %d)].learners", taught).value(3))
			.andExpect(jsonPath("$[?(@.courseId == %d)].learners", empty).value(0))
			.andExpect(jsonPath("$[?(@.courseId == %d)].role", attended).value("STUDENT"))
			.andExpect(jsonPath("$[?(@.courseId == %d)].learners", attended).value(contains(nullValue())));
		professor.get("/api/courses/" + taught).andExpect(jsonPath("$.learners").value(3));
		professor.get("/api/courses/" + attended).andExpect(jsonPath("$.learners").value(nullValue()));

		// 학생은 명단을 볼 수 없고, 이 필드로도 반의 인원을 알 수 없다.
		ApiClient student = new ApiClient(this.mvc, this.data.session(alice));
		student.get("/api/courses/" + taught + "/students").andExpect(status().isNotFound());
		student.get("/api/me/courses")
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[*].role").value(contains("STUDENT", "STUDENT")))
			.andExpect(jsonPath("$[*].learners").value(contains(nullValue(), nullValue())));
		student.get("/api/courses/" + taught).andExpect(jsonPath("$.learners").value(nullValue()));
	}

	@Test
	void statementCountDoesNotGrowWithTheNumberOfCourses() throws Exception {
		ApiClient few = professorWithCourses(2);
		ApiClient many = professorWithCourses(8);
		few.get("/api/me/courses").andExpect(jsonPath("$.length()").value(4));
		many.get("/api/me/courses")
			.andExpect(jsonPath("$.length()").value(16))
			.andExpect(jsonPath("$[?(@.role == 'INSTRUCTOR')].learners").value(contains(2, 2, 2, 2, 2, 2, 2, 2)));

		long fewStatements = statements(few);
		long manyStatements = statements(many);
		// 과목(역할 포함), 담당자 이름, 시간표, 수강 근거, 시계, 수강생 수와 session 확인, transaction 문장뿐이다.
		assertThat(manyStatements).isEqualTo(fewStatements);
	}

	/** 담당 과목과 수강 과목을 `count`개씩 둔다. 담당 과목마다 수강생이 둘이다. */
	private ApiClient professorWithCourses(int count) {
		long prof = this.data.user("PROFESSOR");
		for (int i = 0; i < count; i++) {
			long taught = this.data.course();
			this.data.instructor(taught, prof);
			this.data.enroll(taught, this.data.user("STUDENT"));
			this.data.enroll(taught, this.data.user("STUDENT"));
			long attended = this.data.course();
			this.data.instructor(attended, this.data.user("PROFESSOR"));
			this.data.enroll(attended, prof);
		}
		return new ApiClient(this.mvc, this.data.session(prof));
	}

	/** GET /api/me/courses 한 번 동안 DB가 받은 문장 수. 서버 전체 counter라 세 번 재어 가장 작은 값을 쓴다. */
	private long statements(ApiClient client) throws Exception {
		long min = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			long before = questions();
			client.get("/api/me/courses").andExpect(status().isOk());
			min = Math.min(min, questions() - before);
		}
		return min;
	}

	private long questions() {
		return this.jdbc.sql("SHOW GLOBAL STATUS LIKE 'Questions'").query((rs, n) -> rs.getLong(2)).single();
	}

	private void enroll(long courseId, long userId, String kind, String role, String status) {
		this.jdbc.sql("""
				INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status)
				VALUES (:c, :u, :kind, :role, :status)
				""").param("c", courseId).param("u", userId).param("kind", kind).param("role", role)
			.param("status", status)
			.update();
	}

	private void cancel(long courseId, long userId) {
		this.jdbc.sql("UPDATE course_enrollments SET enrollment_status = 'CANCELLED' WHERE course_id = :c AND user_id = :u")
			.param("c", courseId)
			.param("u", userId)
			.update();
	}

}
