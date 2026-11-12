package com.stackcorp.astra.catalog;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.catalog.api.CourseMemberships;

@Service
class CourseMembershipQueries implements CourseMemberships {

	private final JdbcClient jdbc;

	CourseMembershipQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<CourseRole> roleOf(long userId, long courseId) {
		boolean instructor = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM course_instructors WHERE course_id = :course AND user_id = :user)
				    OR EXISTS (SELECT 1 FROM course_enrollments WHERE course_id = :course AND user_id = :user
				                  AND enrollment_status = 'ENROLLED' AND enrollment_role IN ('PROFESSOR', 'TA'))
				""").param("course", courseId).param("user", userId).query(Boolean.class).single();
		if (instructor) {
			return Optional.of(CourseRole.INSTRUCTOR);
		}
		boolean student = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM course_enrollments WHERE course_id = :course AND user_id = :user
				                  AND enrollment_status = 'ENROLLED' AND enrollment_role IN ('STUDENT', 'AUDITOR'))
				""").param("course", courseId).param("user", userId).query(Boolean.class).single();
		return student ? Optional.of(CourseRole.STUDENT) : Optional.empty();
	}

}
