package com.stackcorp.astra.local;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 로컬 실행용 알림마당 설문. 설문을 만드는 API가 없으므로(알림마당 35, 36 화면용) 전체 대상 익명 설문 하나를 넣는다.
 * 같은 제목의 설문이 이미 있으면 넣지 않는다. 강의실 설문은 교수 계정으로 {@code POST /api/courses/{courseId}/surveys}로 만든다.
 */
final class LocalSurveySeed {

	private static final String TITLE = "2026학년도 생활관 식사제도 의견수렴 설문";

	private LocalSurveySeed() {
	}

	static void seed(JdbcClient jdbc) {
		if (jdbc.sql("SELECT COUNT(*) FROM surveys WHERE activity_id IS NULL AND title = :t")
			.param("t", TITLE)
			.query(Long.class)
			.single() > 0) {
			return;
		}
		long survey = insert(jdbc, """
				INSERT INTO surveys (title, description_text, response_mode, open_at, close_at, result_visible_yn)
				VALUES (:t, '생활관 식사제도 개선을 위한 설문입니다.\n응답은 익명으로 처리합니다.', 'anonymous',
				        CURRENT_TIMESTAMP - INTERVAL 1 DAY, CURRENT_TIMESTAMP + INTERVAL 24 DAY, 1)
				""", Map.of("t", TITLE));
		jdbc.sql("INSERT INTO survey_targets (survey_id, target_type) VALUES (:s, 'all')").param("s", survey).update();
		question(jdbc, survey, 1, "single", "2026학년도 생활관 거주기간은 어떻게 되십니까?", true,
				List.of("1학기", "2학기", "1년"));
		question(jdbc, survey, 2, "scale", "현재 식사제도에 얼마나 만족하십니까?", true,
				List.of("매우 불만족", "불만족", "보통", "만족", "매우 만족"));
		question(jdbc, survey, 3, "essay", "개선 의견을 적어 주세요.", false, List.of());
	}

	private static void question(JdbcClient jdbc, long survey, int no, String type, String text, boolean required,
			List<String> options) {
		long question = insert(jdbc, """
				INSERT INTO survey_questions (survey_id, question_no, question_type, question_text, is_mandatory_yn, sort_order)
				VALUES (:s, :no, :type, :text, :required, :no)
				""", Map.of("s", survey, "no", no, "type", type, "text", text, "required", required));
		for (int i = 0; i < options.size(); i++) {
			jdbc.sql("""
					INSERT INTO survey_question_options (survey_question_id, option_no, option_text, sort_order)
					VALUES (:q, :no, :text, :no)
					""").param("q", question).param("no", i + 1).param("text", options.get(i)).update();
		}
	}

	private static long insert(JdbcClient jdbc, String sql, Map<String, ?> params) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(key);
		return key.getKey().longValue();
	}

}
