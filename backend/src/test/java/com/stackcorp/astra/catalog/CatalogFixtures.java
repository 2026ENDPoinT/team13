package com.stackcorp.astra.catalog;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stackcorp.astra.TestData;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 카탈로그 테스트 데이터. 테스트마다 자기 학기와 과정을 만들어 다른 테스트의 과목과 섞이지 않게 한다.
 */
final class CatalogFixtures {

	static final String OPEN_NOW = "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY)";

	static final String CLOSE_LATER = "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 7 DAY)";

	private final JdbcClient jdbc;

	private final TestData data;

	CatalogFixtures(JdbcClient jdbc, TestData data) {
		this.jdbc = jdbc;
		this.data = data;
	}

	LocalDateTime dbNow() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	/** 다른 테스트와 겹치지 않는 학기. */
	long term(String name) {
		return this.data.insert("""
				INSERT INTO terms (term_year, term_code, term_name, sort_order) VALUES (:year, '3', :name, 0)
				""", Map.of("year", TestData.termYear(), "name", name));
	}

	long regular(long term, String name, String code, String section, String department, String credit,
			boolean auditAllowed) {
		return this.data.insert("""
				INSERT INTO courses (term_id, course_type, course_name, course_code, section_no, org_sect, department_code,
				                     department_name, credit, completion_type, grade_year, audit_allowed)
				VALUES (:term, '정규', :name, :code, :section, 'A', :dept, '컴퓨터공학과', :credit, '전공선택', 3, :audit)
				""", Map.of("term", term, "name", name, "code", code, "section", section, "dept", department, "credit",
				new BigDecimal(credit), "audit", auditAllowed));
	}

	/**
	 * 비정규 과정. 과목, 과정 설정, 신청대상, 좌석 카운터를 만든다.
	 * @param openAt 접수 시작 SQL 식
	 * @param capacity 정원. null이면 제한 없음
	 */
	long offering(String name, String approval, Integer capacity, String openAt, String closeAt,
			List<String> audiences) {
		long course = this.data.insert("""
				INSERT INTO courses (course_type, course_name, course_code, section_no, lecture_type)
				VALUES ('비정규', :name, :code, '01', '이러닝수업')
				""", Map.of("name", name, "code", "N" + UUID.randomUUID().toString().substring(0, 8)));
		long offering = this.data.insert("""
				INSERT INTO non_regular_offerings (course_id, nonregular_category, category_code, approval_mode, round_year,
				                                   round_no, round_label, application_open_at, application_close_at,
				                                   learning_start_date, learning_end_date)
				VALUES (:course, 'B', '56', :approval, 2026, 1, '[2026년 1차수]', %s, %s, '2026-11-10', '2026-12-31')
				""".formatted(openAt, closeAt), Map.of("course", course, "approval", approval));
		for (String audience : audiences) {
			this.jdbc.sql("INSERT INTO non_regular_targets (offering_id, audience_code) VALUES (:o, :a)")
				.param("o", offering)
				.param("a", audience)
				.update();
		}
		this.jdbc.sql("INSERT INTO course_seat_counters (course_id, seat_capacity) VALUES (:c, :cap)")
			.param("c", course)
			.param("cap", capacity)
			.update();
		return course;
	}

	long openOffering(String name, String approval, Integer capacity) {
		return offering(name, approval, capacity, OPEN_NOW, CLOSE_LATER, List.of("3"));
	}

	void manager(long course, long user, String role) {
		this.jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role) VALUES (:c, :u, :r)")
			.param("c", course)
			.param("u", user)
			.param("r", role)
			.update();
	}

	long named(String name, String role) {
		return this.data.insert("INSERT INTO users (external_ref, user_name, user_role) VALUES (:ref, :name, :role)",
				Map.of("ref", "t-" + UUID.randomUUID(), "name", name, "role", role));
	}

	int held(long course) {
		return this.jdbc.sql("SELECT held_count FROM course_seat_counters WHERE course_id = :c")
			.param("c", course)
			.query(Integer.class)
			.single();
	}

	/** 좌석을 점유한 수강 행 수. 카운터와 같아야 한다. */
	int seatHolders(long course) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM course_enrollments WHERE course_id = :c AND holds_seat = 1 AND enrollment_status = 'ENROLLED'
				""").param("c", course).query(Integer.class).single();
	}

	int rows(long course) {
		return this.jdbc.sql("SELECT COUNT(*) FROM course_enrollments WHERE course_id = :c")
			.param("c", course)
			.query(Integer.class)
			.single();
	}

	String status(long enrollmentId) {
		return this.jdbc.sql("SELECT enrollment_status FROM course_enrollments WHERE enrollment_id = :id")
			.param("id", enrollmentId)
			.query(String.class)
			.single();
	}

	/** 상태 이벤트를 순번대로 "유형:이전>이후"로 돌려준다. */
	List<String> events(long enrollmentId) {
		return this.jdbc.sql("""
				SELECT CONCAT(event_type, ':', COALESCE(from_status, '-'), '>', to_status) FROM enrollment_events
				 WHERE enrollment_id = :id ORDER BY event_seq
				""").param("id", enrollmentId).query(String.class).list();
	}

	int audits(String operation, long enrollmentId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = :op AND object_id = :id")
			.param("op", operation)
			.param("id", enrollmentId)
			.query(Integer.class)
			.single();
	}

	void update(String sql, long course) {
		this.jdbc.sql(sql).param("c", course).update();
	}

}
