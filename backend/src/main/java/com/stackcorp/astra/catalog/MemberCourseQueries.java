package com.stackcorp.astra.catalog;

import java.util.List;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 역할 판정은 {@link MembershipRule}이다. {@link CourseMembershipQueries#roleOf}가 역할을 주는 과목만, 같은 역할로 나온다.
 */
@Service
class MemberCourseQueries implements MemberCourses {

	private static final String COURSES = """
			SELECT c.course_id, c.legacy_kjkey, c.course_name, c.course_code, c.section_no,
			""" + MembershipRule.instructorOf("c.course_id", ":user") + """
			 AS instructor
			  FROM courses c LEFT JOIN terms t ON t.term_id = c.term_id
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
					rs.getBoolean("instructor") ? CourseRole.INSTRUCTOR : CourseRole.STUDENT))
			.list();
	}

}
