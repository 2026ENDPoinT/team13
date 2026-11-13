package com.stackcorp.astra.local;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 강의계획서. 데이터베이스 과목(bi)에 교수 박은주가 한 번 저장한 계획서(row_version 1)와 15주 주차 계획, 박은주의 담당자
 * 정보를 넣는다. 연락처는 예시 값이다. 과목의 계획서가 이미 있으면 넣지 않는다.
 */
final class LocalSyllabusSeed {

	private static final String[] WEEKS = { "데이터베이스 개요", "관계 모델", "ER 모델링", "SQL 기초", "정규화", "고급 SQL", "뷰와 권한",
			"중간고사", "인덱스", "트랜잭션", "동시성 제어", "회복", "질의 처리", "프로젝트 발표", "기말고사" };

	private LocalSyllabusSeed() {
	}

	static void seed(JdbcClient jdbc, long professor) {
		long bi = LocalSql.course(jdbc, "bi");
		if (LocalSql.exists(jdbc, "SELECT 1 FROM course_syllabi WHERE course_id = :c", LocalSql.params("c", bi))) {
			return;
		}
		long syllabus = LocalSql.insert(jdbc, """
				INSERT INTO course_syllabi (course_id, class_time_room, overview, objectives, outcome_relevance, textbooks,
				                            equipment, grading_policy, row_version, updated_by)
				VALUES (:c, '목 14:00-17:00 / 미래관 401', :overview, :objectives, 'L2', 'Database System Concepts, 7판', '노트북',
				        '중간고사 30%, 기말고사 30%, 과제 30%, 퀴즈 10%', 1, :u)
				""", LocalSql.params("c", bi, "overview", "관계 데이터베이스의 원리와 SQL, 설계, 트랜잭션을 배운다.", "objectives",
				"요구사항에서 ER 모델을 만들고 정규화된 스키마와 SQL 질의를 작성할 수 있다.", "u", professor));
		for (int i = 0; i < WEEKS.length; i++) {
			jdbc.sql("INSERT INTO course_syllabus_weeks (syllabus_id, week_no, content, note) VALUES (:s, :n, :content, :note)")
				.param("s", syllabus)
				.param("n", i + 1)
				.param("content", WEEKS[i])
				.param("note", i == 7 || i == 14 ? "강의실 시험" : null)
				.update();
		}
		jdbc.sql("""
				INSERT INTO syllabus_instructors (syllabus_id, user_id, department, email, office_hours, phone, office, homepage,
				                                  sort_order)
				VALUES (:s, :u, '컴퓨터공학과', 'professor@example.ac.kr', '화 15:00-17:00', '02-000-0000', '미래관 512',
				        'https://example.ac.kr/db-lab', 0)
				""").param("s", syllabus).param("u", professor).update();
	}

}
