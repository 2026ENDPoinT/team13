package com.stackcorp.astra.catalog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

	/**
	 * {@link #students}와 같은 사람과 그 수강 근거. 한 사람이 수강 행을 여럿 가지면 학사 원장, 비정규, 청강 순으로 하나를 고른다
	 * ({@link EnrollmentRecords#enrolledOf}와 같은 순서). 사용자 ID 오름차순이다.
	 */
	List<Entry> entries(long courseId) {
		Map<Long, Entry> byUser = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT e.user_id, e.enrollment_kind, e.enrollment_role FROM course_enrollments e
				 WHERE e.course_id = :course AND e.enrollment_status = 'ENROLLED' AND e.enrollment_role IN ('STUDENT', 'AUDITOR')
				   AND """ + MembershipRule.studentOf("e.course_id", "e.user_id") + """

				 ORDER BY e.user_id, FIELD(e.enrollment_kind, 'REGISTRAR', 'NONREGULAR', 'AUDIT'), e.enrollment_id
				""")
			.param("course", courseId)
			.query((rs, n) -> new Entry(rs.getLong(1), rs.getString(2), rs.getString(3)))
			.list()
			.forEach((entry) -> byUser.putIfAbsent(entry.userId(), entry));
		return new ArrayList<>(byUser.values());
	}

	/**
	 * @param kind REGISTRAR|NONREGULAR|AUDIT
	 * @param role STUDENT|AUDITOR
	 */
	record Entry(long userId, String kind, String role) {
	}

}
