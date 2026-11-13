package com.stackcorp.astra.local;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 로컬 표본 클래스들이 같이 쓰는 조회와 INSERT. 과목은 과목 키(legacy_kjkey), 사람은 학번·교번(external_ref)으로 찾는다.
 */
final class LocalSql {

	private LocalSql() {
	}

	static long course(JdbcClient jdbc, String key) {
		return jdbc.sql("SELECT course_id FROM courses WHERE legacy_kjkey = :key").param("key", key).query(Long.class).single();
	}

	static long user(JdbcClient jdbc, String ref) {
		return jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref").param("ref", ref).query(Long.class).single();
	}

	static long week(JdbcClient jdbc, long course, int weekNo) {
		return jdbc.sql("SELECT course_week_id FROM course_weeks WHERE course_id = :c AND week_no = :n")
			.param("c", course)
			.param("n", weekNo)
			.query(Long.class)
			.single();
	}

	static boolean exists(JdbcClient jdbc, String sql, Map<String, ?> params) {
		return jdbc.sql("SELECT EXISTS (" + sql + ")").params(params).query(Boolean.class).single();
	}

	static long insert(JdbcClient jdbc, String sql, Map<String, ?> params) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(key);
		return key.getKey().longValue();
	}

	/**
	 * 활동 만들기(ReportActivityService, QuizActivityService, LectureService)와 같은 행. 순서는 그 주차의 마지막 다음이고 공개 상태다.
	 * @param openAt 지금 기준 SQL 간격(예: {@code "-3 DAY"}). null이면 바로 열린 활동
	 * @param closeAt 지금 기준 SQL 간격
	 */
	static long activity(JdbcClient jdbc, long course, int weekNo, String type, String title, String description,
			String openAt, String closeAt, boolean offline, long createdBy) {
		long week = week(jdbc, course, weekNo);
		return insert(jdbc, """
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, description, open_at, close_at,
				                                 offline_yn, published_yn, sort_order, created_by, created_at)
				SELECT :course, :week, :type, :title, :description, %s, %s, :offline, 1, COALESCE(MAX(sort_order), 0) + 1, :user,
				       LEAST(CURRENT_TIMESTAMP(3), COALESCE(%1$s, CURRENT_TIMESTAMP(3))) - INTERVAL 1 DAY
				  FROM learning_activities WHERE course_week_id = :week
				""".formatted(at(openAt), at(closeAt)), params("course", course, "week", week, "type", type, "title", title,
				"description", description, "offline", offline, "user", createdBy));
	}

	/** 지금(DB 시계, 초 단위) 기준 시각 SQL. null이면 NULL. */
	static String at(String offset) {
		return offset == null ? "NULL" : "(CURRENT_TIMESTAMP + INTERVAL " + offset + ")";
	}

	/** null 값을 허용하는 이름 붙은 인자. */
	static Map<String, Object> params(Object... pairs) {
		Map<String, Object> map = new java.util.HashMap<>();
		for (int i = 0; i < pairs.length; i += 2) {
			map.put((String) pairs[i], pairs[i + 1]);
		}
		return map;
	}

}
