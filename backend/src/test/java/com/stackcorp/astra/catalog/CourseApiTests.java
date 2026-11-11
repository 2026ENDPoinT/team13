package com.stackcorp.astra.catalog;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

}
