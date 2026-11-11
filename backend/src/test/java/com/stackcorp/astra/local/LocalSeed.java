package com.stackcorp.astra.local;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.DynamicPropertyRegistrar;

/**
 * 로컬 실행용 표본 데이터와 설정. 프론트엔드 표본 앱의 인물(학생 박민준, 교수 박은주)과 이번 학기 과목 7개를 넣는다.
 * 과목 키(legacy_kjkey)는 프론트엔드가 kj로 쓰는 과목 ID와 같다. 이미 있으면 다시 넣지 않는다.
 */
@TestConfiguration(proxyBeanMethods = false)
public class LocalSeed {

	public static final String STUDENT_REF = "21101234";

	public static final String PROFESSOR_REF = "P-1042";

	private static final List<Course> COURSES = List.of(new Course("bi", "데이터베이스", "109834", "21001"),
			new Course("algo", "고급웹프로그래밍", "109481", "31001"), new Course("ai", "기계학습", "109078", "31001"),
			new Course("db", "블록체인", "109086", "41001"), new Course("oss", "빅데이터처리", "109840", "41001"),
			new Course("se", "사이버보안", "109285", "31001"), new Course("secu", "컴퓨터보안", "109512", "41001"));

	// 전달 cookie 서명 key는 실행할 때마다 새로 만든다. 저장하지 않는다.
	@Bean
	DynamicPropertyRegistrar localDeliveryKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		String encoded = Base64.getEncoder().encodeToString(key);
		return (registry) -> registry.add("astra.delivery.origin.signing-key", () -> encoded);
	}

	@Bean
	ApplicationRunner localSeedRunner(JdbcClient jdbc) {
		return (args) -> seed(jdbc);
	}

	public static void seed(JdbcClient jdbc) {
		long student = user(jdbc, STUDENT_REF, "박민준", "STUDENT");
		long professor = user(jdbc, PROFESSOR_REF, "박은주", "PROFESSOR");
		long term = jdbc.sql("SELECT term_id FROM terms WHERE institution_code = 'SEOULTECH' AND term_year = 2026 AND term_code = '3'")
			.query(Long.class)
			.optional()
			.orElseGet(() -> insert(jdbc, """
					INSERT INTO terms (term_year, term_code, term_name, is_current) VALUES (2026, '3', '2026년 2학기', 1)
					""", Map.of()));
		for (Course course : COURSES) {
			if (jdbc.sql("SELECT COUNT(*) FROM courses WHERE legacy_kjkey = :key").param("key", course.key()).query(Long.class).single() > 0) {
				continue;
			}
			long id = insert(jdbc, """
					INSERT INTO courses (term_id, course_type, course_name, course_code, section_no, org_sect, legacy_kjkey)
					VALUES (:term, '정규', :name, :code, :section, 'A', :key)
					""", Map.of("term", term, "name", course.name(), "code", course.code(), "section", course.section(), "key",
					course.key()));
			jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role, is_primary) VALUES (:c, :u, 'PROFESSOR', 1)")
				.param("c", id)
				.param("u", professor)
				.update();
			jdbc.sql("""
					INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status, source)
					VALUES (:c, :u, 'REGISTRAR', 'STUDENT', 'ENROLLED', 'REGISTRAR')
					""").param("c", id).param("u", student).update();
			for (int week = 1; week <= 14; week++) {
				jdbc.sql("INSERT INTO course_weeks (course_id, week_no) VALUES (:c, :n)").param("c", id).param("n", week).update();
			}
		}
		LocalNoticeSeed.seed(jdbc, professor);
		LocalMessageSeed.seed(jdbc, professor, student);
		LocalEnrollmentSeed.seed(jdbc, term, professor);
		LocalSurveySeed.seed(jdbc);
	}

	private static long user(JdbcClient jdbc, String ref, String name, String role) {
		return jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", ref)
			.query(Long.class)
			.optional()
			.orElseGet(() -> insert(jdbc, "INSERT INTO users (external_ref, user_name, user_role) VALUES (:ref, :name, :role)",
					Map.of("ref", ref, "name", name, "role", role)));
	}

	private static long insert(JdbcClient jdbc, String sql, Map<String, ?> params) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(key);
		return key.getKey().longValue();
	}

	private record Course(String key, String name, String code, String section) {
	}

}
