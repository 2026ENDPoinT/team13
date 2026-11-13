package com.stackcorp.astra.local;

import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 로컬 실행용 강의실 공지. 표본 과목 셋에 교수 박은주가 쓴 공지를 넣는다. 고정 공지 하나, 지난 공지, 예약 공지(학생에게 안 보임)가 있다.
 * 과목에 공지 게시판이 이미 있으면 넣지 않는다.
 */
final class LocalNoticeSeed {

	private static final List<String> COURSES = List.of("bi", "ai", "oss");

	private LocalNoticeSeed() {
	}

	static void seed(JdbcClient jdbc, long professor) {
		for (String key : COURSES) {
			Long course = jdbc.sql("SELECT course_id FROM courses WHERE legacy_kjkey = :key")
				.param("key", key)
				.query(Long.class)
				.optional()
				.orElse(null);
			if (course == null || jdbc.sql("SELECT COUNT(*) FROM boards WHERE course_id = :c AND board_type = 'K001'")
				.param("c", course)
				.query(Long.class)
				.single() > 0) {
				continue;
			}
			long board = insert(jdbc, """
					INSERT INTO boards (course_id, board_type, board_name, requires_notice_confirm)
					VALUES (:c, 'K001', '공지사항', 1)
					""", Map.of("c", course));
			notice(jdbc, board, professor, "중간고사 안내",
					"중간고사는 8주차 수업 시간에 강의실에서 봅니다.\n범위는 1주차부터 7주차까지입니다.", true, "INTERVAL 10 DAY");
			notice(jdbc, board, professor, "휴강 안내", "다음 주 목요일 수업은 휴강합니다. 보강 일정은 따로 알립니다.", false,
					"INTERVAL 2 DAY");
			notice(jdbc, board, professor, "과제 제출 방법", "과제는 학습활동의 과제 제출 화면에서 PDF로 올려 주세요.", false,
					"INTERVAL 3 HOUR");
			notice(jdbc, board, professor, "기말 프로젝트 공지(예약)", "기말 프로젝트 주제는 12주차에 공개합니다.", false,
					"INTERVAL -7 DAY");
		}
	}

	/**
	 * @param age 게시 일시를 지금보다 얼마나 앞으로 둘지(SQL INTERVAL). 음수면 예약 공지
	 */
	private static void notice(JdbcClient jdbc, long board, long author, String title, String body, boolean pinned,
			String age) {
		String excerpt = body.replace('\n', ' ');
		jdbc.sql("""
				INSERT INTO posts (board_id, author_id, post_kind, title, body_text, excerpt, is_pinned, published_at)
				VALUES (:board, :author, 'NOTICE', :title, :body, :excerpt, :pinned, CURRENT_TIMESTAMP(3) - %s)
				""".formatted(age))
			.param("board", board)
			.param("author", author)
			.param("title", title)
			.param("body", body)
			.param("excerpt", excerpt.length() > 150 ? excerpt.substring(0, 150) : excerpt)
			.param("pinned", pinned)
			.update();
	}

	private static long insert(JdbcClient jdbc, String sql, Map<String, ?> params) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.sql(sql).params(params).update(key);
		return key.getKey().longValue();
	}

}
