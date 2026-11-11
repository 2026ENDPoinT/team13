package com.stackcorp.astra.catalog;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.catalog.api.CourseMates;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;

/**
 * 역할 판정은 {@link MembershipRule}이다. 한 과목에 두 관계가 다 있으면 INSTRUCTOR이고, 폐강한 과목에는 구성원이 없다.
 */
@Service
class CourseMateQueries implements CourseMates {

	// 기준 사용자가 구성원인 과목으로 먼저 좁힌 뒤 그 과목들의 담당·수강 행에서 구성원만 남긴다. r은 0이면 담당자, 1이면 학생이다.
	private static final String MATES = """
			WITH scope AS (
			  SELECT k.course_id FROM (""" + MembershipRule.candidateCourses(":user") + ") k WHERE "
			+ MembershipRule.memberOf("k.course_id", ":user") + """

			), people AS (
			  SELECT course_id, user_id FROM course_instructors WHERE course_id IN (SELECT course_id FROM scope)
			  UNION
			  SELECT course_id, user_id FROM course_enrollments WHERE course_id IN (SELECT course_id FROM scope)
			), members AS (
			  SELECT p.course_id, p.user_id, CASE WHEN """ + MembershipRule.instructorOf("p.course_id", "p.user_id")
			+ " THEN 0 ELSE 1 END AS r FROM people p WHERE " + MembershipRule.memberOf("p.course_id", "p.user_id") + """

			)
			SELECT o.course_id, o.user_id, me.r AS viewer_r, o.r
			  FROM members me
			  JOIN members o ON o.course_id = me.course_id AND o.user_id <> me.user_id
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
