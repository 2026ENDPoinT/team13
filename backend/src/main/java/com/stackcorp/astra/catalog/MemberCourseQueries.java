package com.stackcorp.astra.catalog;

import java.util.List;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 역할 판정은 {@link CourseMembershipQueries}와 같다. 담당 관계가 있거나 교수·조교로 등록됐으면 INSTRUCTOR, 학생·청강생으로 수강이
 * 확정됐으면 STUDENT다.
 */
@Service
class MemberCourseQueries implements MemberCourses {

	private final JdbcClient jdbc;

	MemberCourseQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<MemberCourse> of(long userId) {
		return this.jdbc.sql("""
				SELECT c.course_id, c.legacy_kjkey, c.course_name, c.course_code, c.section_no,
				       (EXISTS (SELECT 1 FROM course_instructors i WHERE i.course_id = c.course_id AND i.user_id = :user)
				     OR EXISTS (SELECT 1 FROM course_enrollments e WHERE e.course_id = c.course_id AND e.user_id = :user
				                   AND e.enrollment_status = 'ENROLLED' AND e.enrollment_role IN ('PROFESSOR', 'TA'))) AS instructor
				  FROM courses c LEFT JOIN terms t ON t.term_id = c.term_id
				 WHERE c.course_status <> 'CANCELLED'
				   AND (c.course_id IN (SELECT course_id FROM course_instructors WHERE user_id = :user)
				     OR c.course_id IN (SELECT course_id FROM course_enrollments
				                         WHERE user_id = :user AND enrollment_status = 'ENROLLED'
				                           AND enrollment_role IN ('STUDENT', 'AUDITOR', 'PROFESSOR', 'TA')))
				 ORDER BY t.is_current DESC, t.sort_order, c.course_name, c.course_id
				""")
			.param("user", userId)
			.query((rs, n) -> new MemberCourse(rs.getLong("course_id"), rs.getString("legacy_kjkey"),
					rs.getString("course_name"), rs.getString("course_code"), rs.getString("section_no"),
					rs.getBoolean("instructor") ? CourseRole.INSTRUCTOR : CourseRole.STUDENT))
			.list();
	}

}
