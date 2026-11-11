package com.stackcorp.astra.local;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 수업시간표, 학사일정, 알림. 시간표는 화면 14 캡처의 주간 수업(강의실 미등록)이고, 학사일정은 이번 주와 다음 주에 걸친
 * 두 건이다. 알림은 표본 학생이 받았을 게시된 공지 알림이다(예약 공지는 worker가 게시 시각에 보낸다). 이미 있으면 넣지 않는다.
 */
final class LocalCatalogSeed {

	private static final List<Slot> SLOTS = List.of(new Slot("secu", 1, "10:00", "13:00"),
			new Slot("se", 1, "13:00", "16:00"), new Slot("db", 2, "10:00", "13:00"), new Slot("ai", 3, "14:00", "17:00"),
			new Slot("oss", 4, "10:00", "13:00"), new Slot("bi", 4, "14:00", "17:00"),
			new Slot("algo", 5, "14:00", "18:00"));

	private static final String EVENT_KEY = "local-seed";

	private LocalCatalogSeed() {
	}

	static void seed(JdbcClient jdbc, long student) {
		for (Slot slot : SLOTS) {
			jdbc.sql("""
					INSERT INTO course_schedules (course_id, day_of_week, start_time, end_time)
					SELECT c.course_id, :day, :start, :end FROM courses c
					 WHERE c.legacy_kjkey = :key
					   AND NOT EXISTS (SELECT 1 FROM course_schedules s WHERE s.course_id = c.course_id)
					""").param("key", slot.key()).param("day", slot.day()).param("start", slot.start()).param("end", slot.end()).update();
		}
		if (jdbc.sql("SELECT COUNT(*) FROM academic_calendar_events WHERE legacy_schedule_key = :k")
			.param("k", EVENT_KEY)
			.query(Long.class)
			.single() == 0) {
			jdbc.sql("""
					INSERT INTO academic_calendar_events (event_title, event_type, start_date, end_date, legacy_schedule_key)
					VALUES ('2026학년도 2학기 수강철회기간', 'REGISTRATION', CURRENT_DATE, CURRENT_DATE + INTERVAL 1 DAY, :k),
					       ('개교기념일', 'HOLIDAY', CURRENT_DATE + INTERVAL 8 DAY, CURRENT_DATE + INTERVAL 8 DAY, :k)
					""").param("k", EVENT_KEY).update();
		}
		// 게시된 표본 공지마다 알림 사건 하나와 표본 학생의 알림 하나. 유일 키가 다시 넣지 않게 한다.
		jdbc.sql("""
				INSERT INTO notification_events (notification_type, target_ref_id, course_id, audience_role, message_text,
				                                 visible_at, delivered_at)
				SELECT 'COURSE_NOTICE', p.post_id, b.course_id, 'STUDENT',
				       CONCAT('새로운 공지사항이 있습니다. "', p.title, '"'), p.published_at, p.published_at
				  FROM posts p JOIN boards b ON b.board_id = p.board_id
				 WHERE b.board_type = 'K001' AND p.post_kind = 'NOTICE' AND p.deleted_at IS NULL
				   AND p.published_at <= CURRENT_TIMESTAMP(3)
				ON DUPLICATE KEY UPDATE notification_event_id = notification_event_id
				""").update();
		jdbc.sql("""
				INSERT INTO notifications (user_id, notification_type, course_id, target_kind, target_ref_id, message_text,
				                           expires_at, created_at)
				SELECT :student, e.notification_type, e.course_id, 'ARTICLE', e.target_ref_id, e.message_text,
				       e.delivered_at + INTERVAL 60 DAY, e.delivered_at
				  FROM notification_events e
				 WHERE e.notification_type = 'COURSE_NOTICE' AND e.delivered_at IS NOT NULL
				   AND EXISTS (SELECT 1 FROM course_enrollments x WHERE x.course_id = e.course_id AND x.user_id = :student)
				ON DUPLICATE KEY UPDATE notification_id = notification_id
				""").param("student", student).update();
	}

	/**
	 * @param day 0 일요일 ~ 6 토요일
	 */
	private record Slot(String key, int day, String start, String end) {
	}

}
