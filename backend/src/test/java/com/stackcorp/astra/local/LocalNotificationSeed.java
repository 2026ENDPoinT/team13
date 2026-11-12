package com.stackcorp.astra.local;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 과제·답변·쪽지 알림. 서비스가 보내는 것과 같은 유형, 대상, 문장으로 넣는다(NotificationKind, NotificationService.message).
 * <ul>
 * <li>공개된 과제마다 ASSIGNMENT_OPENED 사건과 수강생 알림(대상 ACTIVITY). 보이는 시각은 과제 시작 시각이다.</li>
 * <li>점수를 공개한 과제는 ASSIGNMENT_GRADED. 공개를 켠 수정 시각에 보냈다.</li>
 * <li>질의응답 답변은 질문 작성자에게 QUESTION_ANSWERED(대상 ARTICLE, 사건 없음).</li>
 * <li>쪽지는 받는 사람마다 MESSAGE_RECEIVED(과목 없음, 대상 MESSAGE, 사건 없음).</li>
 * </ul>
 * 알림은 받은 시각에서 60일 동안 보존한다. 박민준은 지난 과제 알림 둘을, 박은주는 먼저 온 쪽지 알림을 읽었다. 공지 알림은
 * {@link LocalCatalogSeed}가 넣는다. 과제·게시판·쪽지 표본 다음에 부른다. 과목의 과제 알림 사건이 이미 있으면 넣지 않는다.
 */
final class LocalNotificationSeed {

	private LocalNotificationSeed() {
	}

	static void seed(JdbcClient jdbc, long student, long professor) {
		if (LocalSql.exists(jdbc, """
				SELECT 1 FROM notification_events e JOIN courses c ON c.course_id = e.course_id
				 WHERE c.legacy_kjkey = 'bi' AND e.notification_type = 'ASSIGNMENT_OPENED'
				""", LocalSql.params())) {
			return;
		}
		// 과제 공개(AssignmentNotifications.sync → NotificationService.publish). 시작 시각이 지난 과제는 그때 보냈다.
		jdbc.sql("""
				INSERT INTO notification_events (notification_type, target_ref_id, course_id, activity_id, audience_role,
				                                 message_text, visible_at, delivered_at, created_at)
				SELECT 'ASSIGNMENT_OPENED', a.activity_id, a.course_id, a.activity_id, 'STUDENT',
				       CONCAT('새로운 과제가 있습니다. "', a.title, '"'), a.open_at, a.open_at, a.created_at
				  FROM learning_activities a
				 WHERE a.activity_type = 'report' AND a.published_yn = 1 AND a.deleted_at IS NULL
				   AND a.open_at <= CURRENT_TIMESTAMP(3)
				ON DUPLICATE KEY UPDATE notification_event_id = notification_event_id
				""").update();
		// 점수 공개(수정 화면에서 공개를 켠 시각 = 성적 항목의 공개 일시). 보이는 시각은 과제 시작 시각이라 바로 보냈다.
		jdbc.sql("""
				INSERT INTO notification_events (notification_type, target_ref_id, course_id, activity_id, audience_role,
				                                 message_text, visible_at, delivered_at, created_at)
				SELECT 'ASSIGNMENT_GRADED', a.activity_id, a.course_id, a.activity_id, 'STUDENT',
				       CONCAT('과제 점수가 공개되었습니다. "', a.title, '"'), a.open_at, g.release_at, g.release_at
				  FROM learning_activities a JOIN grade_items g ON g.activity_id = a.activity_id
				 WHERE a.activity_type = 'report' AND a.published_yn = 1 AND a.deleted_at IS NULL
				   AND g.score_visibility = 'immediate' AND g.release_at <= CURRENT_TIMESTAMP(3)
				ON DUPLICATE KEY UPDATE notification_event_id = notification_event_id
				""").update();
		// 받는 사람은 CourseRosters.students와 같다: 확정된 학생·청강생이고 담당자가 아니다.
		jdbc.sql("""
				INSERT INTO notifications (user_id, notification_type, course_id, activity_id, target_kind, target_ref_id,
				                           message_text, expires_at, created_at)
				SELECT DISTINCT e.user_id, ev.notification_type, ev.course_id, ev.activity_id, 'ACTIVITY', ev.target_ref_id,
				       ev.message_text, ev.delivered_at + INTERVAL 60 DAY, ev.delivered_at
				  FROM notification_events ev
				  JOIN course_enrollments e ON e.course_id = ev.course_id AND e.enrollment_status = 'ENROLLED'
				                           AND e.enrollment_role IN ('STUDENT', 'AUDITOR')
				 WHERE ev.notification_type IN ('ASSIGNMENT_OPENED', 'ASSIGNMENT_GRADED') AND ev.delivered_at IS NOT NULL
				   AND NOT EXISTS (SELECT 1 FROM course_instructors i WHERE i.course_id = e.course_id AND i.user_id = e.user_id)
				ON DUPLICATE KEY UPDATE notification_id = notification_id
				""").update();

		// 답변 등록(QnaAnswerService): 질문 작성자가 답한 사람이 아니면 그 사람에게.
		jdbc.sql("""
				INSERT INTO notifications (user_id, notification_type, course_id, activity_id, target_kind, target_ref_id,
				                           message_text, expires_at, created_at)
				SELECT q.author_id, 'QUESTION_ANSWERED', b.course_id, NULL, 'ARTICLE', q.post_id,
				       CONCAT('질문에 답변이 등록되었습니다. "', q.title, '"'), a.created_at + INTERVAL 60 DAY, a.created_at
				  FROM posts a JOIN posts q ON q.post_id = a.parent_post_id JOIN boards b ON b.board_id = q.board_id
				 WHERE b.board_type = 'K002' AND a.post_kind = 'ANSWER' AND a.author_id <> q.author_id AND a.deleted_at IS NULL
				ON DUPLICATE KEY UPDATE notification_id = notification_id
				""").update();

		// 쪽지 도착(MessageService.deliver).
		jdbc.sql("""
				INSERT INTO notifications (user_id, notification_type, course_id, activity_id, target_kind, target_ref_id,
				                           message_text, expires_at, created_at)
				SELECT r.recipient_id, 'MESSAGE_RECEIVED', NULL, NULL, 'MESSAGE', m.message_id,
				       CONCAT('새 쪽지가 도착했습니다. "', m.title, '"'), r.created_at + INTERVAL 60 DAY, r.created_at
				  FROM message_recipients r JOIN messages m ON m.message_id = r.message_id
				 WHERE r.recipient_id IN (:student, :professor)
				ON DUPLICATE KEY UPDATE notification_id = notification_id
				""").param("student", student).param("professor", professor).update();

		// 읽은 알림(POST /api/me/notifications/{id}/read): 받은 지 두 시간 뒤에 열었다.
		jdbc.sql("""
				UPDATE notifications n JOIN learning_activities a ON a.activity_id = n.target_ref_id
				   SET n.read_at = n.created_at + INTERVAL 2 HOUR
				 WHERE n.user_id = :student AND n.notification_type = 'ASSIGNMENT_OPENED' AND n.read_at IS NULL
				   AND a.title IN (:titles)
				""")
			.param("student", student)
			.param("titles", List.of(LocalAssignmentSeed.RELEASED, LocalAssignmentSeed.UNRELEASED))
			.update();
		jdbc.sql("""
				UPDATE notifications SET read_at = created_at + INTERVAL 2 HOUR
				 WHERE user_id = :professor AND notification_type = 'MESSAGE_RECEIVED' AND read_at IS NULL
				 ORDER BY created_at LIMIT 1
				""").param("professor", professor).update();
	}

}
