package com.stackcorp.astra.catalog;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.catalog.api.CourseProfiles;

@Service
class CourseProfileQueries implements CourseProfiles {

	private final JdbcClient jdbc;

	CourseProfileQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<CourseProfile> find(long courseId) {
		return this.jdbc.sql("""
				SELECT c.course_id, c.course_type, c.course_name, c.course_code, c.section_no, t.term_name,
				       c.completion_type, c.credit
				  FROM courses c LEFT JOIN terms t ON t.term_id = c.term_id
				 WHERE c.course_id = :course AND c.course_status <> 'CANCELLED'
				""").param("course", courseId).query((rs, n) -> new Row(rs.getLong(1), "정규".equals(rs.getString(2)),
				rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getString(7),
				rs.getBigDecimal(8))).optional().map((row) -> new CourseProfile(row.courseId(), row.regular(), row.name(),
						row.code(), row.section(), row.termName(), row.completionType(), row.credit(),
						instructors(courseId)));
	}

	private List<Long> instructors(long courseId) {
		return this.jdbc.sql("""
				SELECT user_id FROM course_instructors WHERE course_id = :course
				 GROUP BY user_id ORDER BY MAX(is_primary) DESC, MIN(sort_order), MIN(course_instructor_id)
				""").param("course", courseId).query(Long.class).list();
	}

	private record Row(long courseId, boolean regular, String name, String code, String section, String termName,
			String completionType, BigDecimal credit) {
	}

}
