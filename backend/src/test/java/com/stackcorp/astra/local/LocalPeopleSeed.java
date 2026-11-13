package com.stackcorp.astra.local;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 사람. 두 번째 교수 이준호(사이버보안, 컴퓨터보안 공동 담당)와 학생 8명을 넣고 과목에 정규수강으로 등록한다. 수강생 명단,
 * 성적부, 출석부, 그룹, 게시판 작성자, 쪽지 받는사람 화면에 쓴다. 이름은 모두 지어낸 것이다. 이미 있으면 넣지 않는다.
 */
final class LocalPeopleSeed {

	static final String SECOND_PROFESSOR_REF = "P-2071";

	/** 표본 학생 박민준 다음 학번들. 과목 키 목록은 그 학생이 수강하는 과목이다. */
	static final List<Student> STUDENTS = List.of(new Student("21101235", "김서연", 3, List.of("bi", "ai", "algo", "se")),
			new Student("21101236", "이도윤", 3, List.of("bi", "ai", "db", "secu")),
			new Student("21101237", "최하은", 3, List.of("bi", "ai", "oss", "se")),
			new Student("21101238", "정우진", 3, List.of("bi", "ai", "oss", "algo")),
			new Student("22101239", "강지민", 2, List.of("bi", "ai", "oss", "db")),
			new Student("22101240", "윤예준", 2, List.of("bi", "oss", "secu", "algo")),
			new Student("20101241", "한수아", 4, List.of("bi", "oss", "se", "secu")),
			new Student("20101242", "오시우", 4, List.of("bi", "oss", "db", "algo")));

	private LocalPeopleSeed() {
	}

	static void seed(JdbcClient jdbc) {
		long second = user(jdbc, SECOND_PROFESSOR_REF, "이준호", "PROFESSOR", null);
		for (String key : List.of("se", "secu")) {
			jdbc.sql("""
					INSERT INTO course_instructors (course_id, user_id, instructor_role, is_primary, sort_order)
					SELECT c.course_id, :u, 'PROFESSOR', 0, 1 FROM courses c
					 WHERE c.legacy_kjkey = :key
					   AND NOT EXISTS (SELECT 1 FROM course_instructors i WHERE i.course_id = c.course_id AND i.user_id = :u)
					""").param("u", second).param("key", key).update();
		}
		for (Student student : STUDENTS) {
			long id = user(jdbc, student.ref(), student.name(), "STUDENT", student.grade());
			for (String key : student.courses()) {
				jdbc.sql("""
						INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status, source)
						SELECT c.course_id, :u, 'REGISTRAR', 'STUDENT', 'ENROLLED', 'REGISTRAR' FROM courses c
						 WHERE c.legacy_kjkey = :key
						   AND NOT EXISTS (SELECT 1 FROM course_enrollments e WHERE e.course_id = c.course_id AND e.user_id = :u)
						""").param("u", id).param("key", key).update();
			}
		}
	}

	private static long user(JdbcClient jdbc, String ref, String name, String role, Integer grade) {
		return jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", ref)
			.query(Long.class)
			.optional()
			.orElseGet(() -> LocalSql.insert(jdbc, """
					INSERT INTO users (external_ref, user_name, user_role, department_code, department_name, grade)
					VALUES (:ref, :name, :role, '20031106', '컴퓨터공학과', :grade)
					""", LocalSql.params("ref", ref, "name", name, "role", role, "grade", grade)));
	}

	/** 학생 학번 목록(표본 학생 박민준 제외). */
	static List<String> refs() {
		return STUDENTS.stream().map(Student::ref).toList();
	}

	record Student(String ref, String name, int grade, List<String> courses) {
	}

}
