package com.stackcorp.astra.catalog;

import java.time.LocalDate;
import java.util.List;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 역할 판정은 {@link MembershipRule}이다. {@link CourseMembershipQueries#roleOf}가 역할을 주는 과목만, 같은 역할로 나온다.
 * 수업 기간은 비정규 과정 행이 있으면 그 학습기간, 없으면 학기 기간이다.
 */
@Service
class MemberCourseQueries implements MemberCourses {

	private static final String COURSES = """
			SELECT c.course_id, c.legacy_kjkey, c.course_name, c.course_code, c.section_no,
			       CASE WHEN o.offering_id IS NULL THEN t.start_date ELSE o.learning_start_date END AS start_date,
			       CASE WHEN o.offering_id IS NULL THEN t.end_date ELSE o.learning_end_date END AS end_date,
			""" + MembershipRule.instructorOf("c.course_id", ":user") + """
			 AS instructor
			  FROM courses c LEFT JOIN terms t ON t.term_id = c.term_id
			  LEFT JOIN non_regular_offerings o ON o.course_id = c.course_id
			 WHERE c.course_id IN (""" + MembershipRule.candidateCourses(":user") + ") AND "
			+ MembershipRule.memberOf("c.course_id", ":user") + """
			 ORDER BY t.is_current DESC, t.sort_order, c.course_name, c.course_id
			""";

	private final JdbcClient jdbc;

	MemberCourseQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<MemberCourse> of(long userId) {
		return this.jdbc.sql(COURSES)
			.param("user", userId)
			.query((rs, n) -> new MemberCourse(rs.getLong("course_id"), rs.getString("legacy_kjkey"),
					rs.getString("course_name"), rs.getString("course_code"), rs.getString("section_no"),
					rs.getBoolean("instructor") ? CourseRole.INSTRUCTOR : CourseRole.STUDENT,
					rs.getObject("start_date", LocalDate.class), rs.getObject("end_date", LocalDate.class)))
			.list();
	}

}
