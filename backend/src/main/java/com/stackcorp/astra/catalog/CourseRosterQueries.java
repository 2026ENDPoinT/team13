package com.stackcorp.astra.catalog;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.catalog.api.CourseRosters;

/**
 * {@link MembershipRule}이 STUDENT로 판정하는 사람. 담당자로도 등록된 사람과 폐강한 과목의 수강생은 없다.
 */
@Service
class CourseRosterQueries implements CourseRosters {

	private final JdbcClient jdbc;

	CourseRosterQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<Long> students(long courseId) {
		return this.jdbc.sql("SELECT DISTINCT e.user_id FROM course_enrollments e WHERE e.course_id = :course AND "
				+ MembershipRule.studentOf("e.course_id", "e.user_id") + " ORDER BY e.user_id")
			.param("course", courseId)
			.query(Long.class)
			.list();
	}

}
