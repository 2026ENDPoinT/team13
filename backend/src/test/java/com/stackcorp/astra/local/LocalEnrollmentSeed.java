package com.stackcorp.astra.local;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 로컬 실행용 수강신청 표본. 학생 대상 자동 승인 과정(정원 없음), 교수 박은주가 승인하는 과정(정원 30), 박은주의 청강 가능한 정규
 * 과목을 넣는다. 표본 학생은 어디에도 신청하지 않은 상태로 시작한다. 과목 키가 이미 있으면 넣지 않는다.
 */
final class LocalEnrollmentSeed {

	private LocalEnrollmentSeed() {
	}

	static void seed(JdbcClient jdbc, long term, long professor) {
		offering(jdbc, "nr-auto", "[2026년 1차수] 연구윤리 교육", "641", "AUTO", null, professor);
		offering(jdbc, "nr-manual", "[2026년 1차수] 창업 캠프", "702", "MANUAL", 30, professor);
		if (!exists(jdbc, "audit-os")) {
			long course = insert(jdbc, """
					INSERT INTO courses (term_id, course_type, course_name, course_code, section_no, org_sect, legacy_kjkey,
					                     department_code, department_name, credit, completion_type, grade_year, audit_allowed)
					VALUES (:term, '정규', '운영체제', '109100', '21001', 'A', 'audit-os', '20031106', '컴퓨터공학과', 3, '전공선택', 3, 1)
					""", Map.of("term", term));
			jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role, is_primary) VALUES (:c, :u, 'PROFESSOR', 1)")
				.param("c", course)
				.param("u", professor)
				.update();
			jdbc.sql("""
					INSERT INTO course_schedules (course_id, day_of_week, period_start, period_end, start_time, end_time, room_name)
					VALUES (:c, 2, 3, 4, '11:00', '13:00', '미래관-301')
					""").param("c", course).update();
		}
	}

	private static void offering(JdbcClient jdbc, String key, String name, String code, String approval,
			Integer capacity, long professor) {
		if (exists(jdbc, key)) {
			return;
		}
		long course = insert(jdbc, """
				INSERT INTO courses (course_type, course_name, course_code, section_no, legacy_kjkey, lecture_type)
				VALUES ('비정규', :name, :code, '01', :key, '이러닝수업')
				""", Map.of("name", name, "code", code, "key", key));
		long offering = insert(jdbc, """
				INSERT INTO non_regular_offerings (course_id, nonregular_category, category_code, category_name, approval_mode,
				                                   round_year, round_no, round_label, application_open_at, application_close_at,
				                                   cancel_close_at, learning_start_date, learning_end_date)
				VALUES (:course, 'B', '1', '미분류', :approval, 2026, 1, '[2026년 1차수]', DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY),
				        DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 DAY), DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 30 DAY),
				        CURRENT_DATE, DATE_ADD(CURRENT_DATE, INTERVAL 60 DAY))
				""", Map.of("course", course, "approval", approval));
		for (String audience : new String[] { "3", "5", "6" }) {
			jdbc.sql("INSERT INTO non_regular_targets (offering_id, audience_code) VALUES (:o, :a)")
				.param("o", offering)
				.param("a", audience)
				.update();
		}
		jdbc.sql("INSERT INTO course_seat_counters (course_id, seat_capacity) VALUES (:c, :cap)")
			.param("c", course)
			.param("cap", capacity)
			.update();
		jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role, is_primary) VALUES (:c, :u, 'MANAGER', 1)")
			.param("c", course)
			.param("u", professor)
			.update();
	}

	private static boolean exists(JdbcClient jdbc, String key) {
		return jdbc.sql("SELECT COUNT(*) FROM courses WHERE legacy_kjkey = :key").param("key", key).query(Long.class).single() > 0;
	}

	private static long insert(JdbcClient jdbc, String sql, Map<String, ?> params) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(key);
		return key.getKey().longValue();
	}

}
