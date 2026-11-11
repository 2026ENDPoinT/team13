package com.stackcorp.astra.catalog;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.catalog.api.CourseMates;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;

/**
 * 역할 판정은 {@link CourseMembershipQueries}와 같다. 담당 관계가 있거나 교수·조교로 등록됐으면 INSTRUCTOR, 학생·청강생으로 수강이
 * 확정됐으면 STUDENT다. 한 과목에 두 관계가 다 있으면 INSTRUCTOR로 본다.
 */
@Service
class CourseMateQueries implements CourseMates {

	// 기준 사용자의 과목으로 먼저 좁힌 뒤 그 과목들의 구성원만 읽는다. r은 0이면 담당자, 1이면 학생이다.
	private static final String MATES = """
			WITH scope AS (
			  SELECT course_id FROM course_instructors WHERE user_id = :user
			  UNION
			  SELECT course_id FROM course_enrollments
			   WHERE user_id = :user AND enrollment_status = 'ENROLLED' AND enrollment_role IN ('STUDENT', 'AUDITOR', 'PROFESSOR', 'TA')
			), members AS (
			  SELECT course_id, user_id, MIN(r) AS r FROM (
			    SELECT course_id, user_id, 0 AS r FROM course_instructors WHERE course_id IN (SELECT course_id FROM scope)
			    UNION ALL
			    SELECT course_id, user_id, CASE WHEN enrollment_role IN ('PROFESSOR', 'TA') THEN 0 ELSE 1 END
			      FROM course_enrollments
			     WHERE course_id IN (SELECT course_id FROM scope) AND enrollment_status = 'ENROLLED'
			       AND enrollment_role IN ('STUDENT', 'AUDITOR', 'PROFESSOR', 'TA')
			  ) x GROUP BY course_id, user_id
			)
			SELECT o.course_id, o.user_id, me.r AS viewer_r, o.r
			  FROM members me
			  JOIN members o ON o.course_id = me.course_id AND o.user_id <> me.user_id
			  JOIN courses c ON c.course_id = me.course_id AND c.course_status <> 'CANCELLED'
			 WHERE me.user_id = :user
			""";

	private final JdbcClient jdbc;

	CourseMateQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<CourseMate> of(long userId, Long courseId, Collection<Long> only) {
		if (only != null && only.isEmpty()) {
			return List.of();
		}
		String sql = MATES + (courseId == null ? "" : " AND me.course_id = :course")
				+ (only == null ? "" : " AND o.user_id IN (:only)") + " ORDER BY o.course_id, o.user_id";
		var statement = this.jdbc.sql(sql).param("user", userId);
		if (courseId != null) {
			statement = statement.param("course", courseId);
		}
		if (only != null) {
			statement = statement.param("only", only);
		}
		return statement
			.query((rs, n) -> new CourseMate(rs.getLong("course_id"), rs.getLong("user_id"), role(rs.getInt("viewer_r")),
					role(rs.getInt("r"))))
			.list();
	}

	private static CourseRole role(int rank) {
		return rank == 0 ? CourseRole.INSTRUCTOR : CourseRole.STUDENT;
	}

}
