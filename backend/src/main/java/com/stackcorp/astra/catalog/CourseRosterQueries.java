package com.stackcorp.astra.catalog;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.catalog.api.CourseRosters;

/**
 * 수강이 확정된 학생과 청강생. 담당자로도 등록된 사람은 roleOf와 같이 담당자로 보고 빼낸다.
 */
@Service
class CourseRosterQueries implements CourseRosters {

	private final JdbcClient jdbc;

	CourseRosterQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<Long> students(long courseId) {
		return this.jdbc.sql("""
				SELECT DISTINCT e.user_id FROM course_enrollments e
				 WHERE e.course_id = :course AND e.enrollment_status = 'ENROLLED' AND e.enrollment_role IN ('STUDENT', 'AUDITOR')
				   AND NOT EXISTS (SELECT 1 FROM course_instructors i WHERE i.course_id = e.course_id AND i.user_id = e.user_id)
				   AND NOT EXISTS (SELECT 1 FROM course_enrollments x
				                    WHERE x.course_id = e.course_id AND x.user_id = e.user_id
				                      AND x.enrollment_status = 'ENROLLED' AND x.enrollment_role IN ('PROFESSOR', 'TA'))
				 ORDER BY e.user_id
				""").param("course", courseId).query(Long.class).list();
	}

}
