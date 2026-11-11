package com.stackcorp.astra.local;

import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;

/**
 * 로컬 실행용 쪽지. 교수 박은주가 학생 박민준에게 보낸 쪽지 둘(하나는 읽음)과 학생이 보낸 답장 하나를 넣는다. 교수가 보낸 쪽지가 이미
 * 있으면 넣지 않는다.
 */
final class LocalMessageSeed {

	private LocalMessageSeed() {
	}

	static void seed(JdbcClient jdbc, long professor, long student) {
		if (jdbc.sql("SELECT COUNT(*) FROM messages WHERE sender_id = :u").param("u", professor).query(Long.class).single() > 0) {
			return;
		}
		Long course = jdbc.sql("SELECT course_id FROM courses WHERE legacy_kjkey = 'bi'").query(Long.class).optional().orElse(null);
		long first = message(jdbc, professor, student, course, "중간고사 범위 안내",
				"중간고사 범위는 1주차부터 7주차까지입니다.\n질문은 쪽지로 보내 주세요.", "INTERVAL 2 DAY", true, null);
		message(jdbc, student, professor, course, "Re: 중간고사 범위 안내", "7주차 실습도 범위에 들어가나요?", "INTERVAL 1 DAY", false,
				first);
		message(jdbc, professor, student, null, "면담 일정", "다음 주 화요일 오후 3시에 연구실에서 면담합시다.", "INTERVAL 2 HOUR", false,
				null);
	}

	/**
	 * @param age 발송 일시를 지금보다 얼마나 앞으로 둘지(SQL INTERVAL)
	 * @param replyTo 답장이면 원래 쪽지
	 */
	private static long message(JdbcClient jdbc, long sender, long recipient, Long course, String title, String body,
			String age, boolean read, Long replyTo) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		jdbc.sql("""
				INSERT INTO messages (sender_id, course_id, title, body_text, recipient_count, origin_message_id, origin_kind, created_at)
				VALUES (:sender, :course, :title, :body, 1, :origin, :kind, CURRENT_TIMESTAMP(3) - %s)
				""".formatted(age))
			.params(Map.of("sender", sender, "title", title, "body", body))
			.param("course", course)
			.param("origin", replyTo)
			.param("kind", replyTo == null ? null : "REPLY")
			.update(key);
		long id = key.getKey().longValue();
		jdbc.sql("""
				INSERT INTO message_recipients (message_id, recipient_id, course_id, read_at, created_at)
				SELECT message_id, :recipient, course_id, IF(:read, created_at, NULL), created_at FROM messages WHERE message_id = :m
				""").param("recipient", recipient).param("read", read).param("m", id).update();
		return id;
	}

}
