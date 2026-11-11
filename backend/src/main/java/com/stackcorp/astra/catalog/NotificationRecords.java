package com.stackcorp.astra.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.NotificationKind;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * notifications와 notification_events. 알림 행은 받는 사람마다 하나이고 (유형, 대상, 사용자)가 unique다(SC-05).
 * 알림 사건은 과목 구성원에게 보내는 알림 한 건의 원본이다. 보일 시각이 미래면 사건만 있고 알림 행은 그 시각 뒤에 만든다(ADR-0027).
 * 읽기는 모두 {@link #VISIBLE} 조건을 쓴다. 목록, 안 읽은 수, 읽음, 삭제가 같은 조건이라 본인이 지금 볼 수 없는 알림은 어느 경로로도
 * 드러나지 않는다.
 */
@Repository
class NotificationRecords {

	/** 알림 보존 기간(화면 04 "60일 지난 알림 내용은 자동 삭제됩니다."). */
	static final int RETENTION_DAYS = 60;

	/** 여러 행 INSERT 한 문장에 넣는 행 수. */
	private static final int INSERT_CHUNK = 200;

	/**
	 * 받는 사람이 지금 볼 수 있는 알림. 보존 기간 안이고, 과목 알림이면 지금도 그 과목의 구성원이어야 한다. 구성원 판정은
	 * {@link MembershipRule}이다(담당 관계, 수강 확정, 폐강 제외).
	 */
	private static final String VISIBLE = "n.user_id = :user AND n.expires_at > :now AND (n.course_id IS NULL OR "
			+ MembershipRule.memberOf("n.course_id", "n.user_id") + ")\n";

	private static final String SELECT = """
			SELECT n.notification_id, n.notification_type, n.course_id, c.course_name, c.course_code, c.section_no,
			       n.activity_id, n.target_kind, n.target_ref_id, n.message_text, n.read_at, n.created_at
			  FROM notifications n LEFT JOIN courses c ON c.course_id = n.course_id
			 WHERE
			""";

	private static final String EVENT = """
			SELECT notification_event_id, notification_type, target_ref_id, course_id, activity_id, audience_role,
			       message_text, delivered_at IS NOT NULL AS delivered
			  FROM notification_events
			""";

	private final JdbcClient jdbc;

	NotificationRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	// ---- 알림 사건 ----

	/**
	 * 사건을 만들거나 받는 사람, 문장, 보일 시각을 바꾸고 그 행을 잠근 채 돌려준다. 보낸 상태(delivered_at)는 바꾸지 않는다.
	 * 잠금 읽기라 다른 transaction이 방금 만든 행도 읽는다.
	 */
	Event upsertEvent(NotificationKind kind, long targetId, long courseId, Long activityId, CourseRole audience,
			String message, LocalDateTime visibleAt) {
		this.jdbc.sql("""
				INSERT INTO notification_events (notification_type, target_ref_id, course_id, activity_id, audience_role,
				                                 message_text, visible_at)
				VALUES (:type, :target, :course, :activity, :role, :message, :visibleAt) AS new
				ON DUPLICATE KEY UPDATE course_id = new.course_id, activity_id = new.activity_id,
				                        audience_role = new.audience_role, message_text = new.message_text,
				                        visible_at = new.visible_at
				""")
			.param("type", kind.name())
			.param("target", targetId)
			.param("course", courseId)
			.param("activity", activityId)
			.param("role", audience.name())
			.param("message", message)
			.param("visibleAt", visibleAt)
			.update();
		return this.jdbc.sql(EVENT + " WHERE notification_type = :type AND target_ref_id = :target FOR UPDATE")
			.param("type", kind.name())
			.param("target", targetId)
			.query(NotificationRecords::event)
			.single();
	}

	/** 보일 시각이 지났고 아직 보내지 않은 사건 ID. 오래된 것부터다. 잠그지 않는다. */
	List<Long> dueEvents(LocalDateTime now, int limit) {
		return this.jdbc.sql("""
				SELECT notification_event_id FROM notification_events
				 WHERE delivered_at IS NULL AND visible_at <= :now
				 ORDER BY visible_at, notification_event_id
				 LIMIT :limit
				""").param("now", now).param("limit", limit).query(Long.class).list();
	}

	/** 아직 보내지 않았고 보일 시각이 지난 사건을 잠근다. 다른 worker가 잡고 있거나 이미 보냈으면 비어 있다. */
	Optional<Event> claimDue(long eventId, LocalDateTime now) {
		return this.jdbc.sql(EVENT + """
				 WHERE notification_event_id = :id AND delivered_at IS NULL AND visible_at <= :now
				   FOR UPDATE SKIP LOCKED
				""").param("id", eventId).param("now", now).query(NotificationRecords::event).optional();
	}

	void markDelivered(long eventId) {
		this.jdbc.sql("UPDATE notification_events SET delivered_at = CURRENT_TIMESTAMP(3) WHERE notification_event_id = :id")
			.param("id", eventId)
			.update();
	}

	void markPending(long eventId) {
		this.jdbc.sql("UPDATE notification_events SET delivered_at = NULL WHERE notification_event_id = :id")
			.param("id", eventId)
			.update();
	}

	void deleteEvent(NotificationKind kind, long targetId) {
		this.jdbc.sql("DELETE FROM notification_events WHERE notification_type = :type AND target_ref_id = :target")
			.param("type", kind.name())
			.param("target", targetId)
			.update();
	}

	// ---- 알림 행 만들기 ----

	/**
	 * 그 과목의 담당자. {@link MembershipRule}이 INSTRUCTOR로 판정하는 사람이다. 오름차순.
	 */
	List<Long> instructors(long courseId) {
		return this.jdbc.sql("""
				SELECT p.user_id FROM (SELECT user_id FROM course_instructors WHERE course_id = :course
				                       UNION SELECT user_id FROM course_enrollments WHERE course_id = :course) p
				 WHERE """ + MembershipRule.instructorOf(":course", "p.user_id") + " ORDER BY p.user_id")
			.param("course", courseId)
			.query(Long.class)
			.list();
	}

	/** 주어진 사용자 가운데 {@link MembershipRule}이 그 과목의 구성원으로 판정하는 사람. 오름차순. */
	List<Long> members(long courseId, Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("""
				SELECT p.user_id FROM (SELECT user_id FROM course_instructors WHERE course_id = :course AND user_id IN (:users)
				                       UNION SELECT user_id FROM course_enrollments
				                              WHERE course_id = :course AND user_id IN (:users)) p
				 WHERE """ + MembershipRule.memberOf(":course", "p.user_id") + " ORDER BY p.user_id")
			.param("course", courseId)
			.param("users", userIds)
			.query(Long.class)
			.list();
	}

	/**
	 * 받는 사람마다 알림 행을 만든다. 이미 있는 (유형, 대상, 사용자)는 그대로 둔다. 여러 행 INSERT를 {@value #INSERT_CHUNK}행씩 보낸다.
	 * 받는 사람은 오름차순으로 넘겨 잠금 순서를 고정한다.
	 * @return 새로 만든 행 수
	 */
	int insert(List<Long> recipients, NotificationKind kind, Long courseId, Long activityId, long targetId,
			String message) {
		int created = 0;
		for (int from = 0; from < recipients.size(); from += INSERT_CHUNK) {
			List<Long> chunk = recipients.subList(from, Math.min(recipients.size(), from + INSERT_CHUNK));
			StringBuilder sql = new StringBuilder("""
					INSERT INTO notifications (user_id, notification_type, course_id, activity_id, target_kind, target_ref_id,
					                           message_text, expires_at)
					VALUES
					""");
			Map<String, Object> params = new HashMap<>();
			for (int i = 0; i < chunk.size(); i++) {
				sql.append(i == 0 ? "" : ",\n")
					.append("(:u").append(i)
					.append(", :type, :course, :activity, :targetKind, :target, :message,")
					.append(" DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL ").append(RETENTION_DAYS).append(" DAY))");
				params.put("u" + i, chunk.get(i));
			}
			sql.append("\nON DUPLICATE KEY UPDATE notification_id = notification_id");
			created += this.jdbc.sql(sql.toString())
				.params(params)
				.param("type", kind.name())
				.param("course", courseId)
				.param("activity", activityId)
				.param("targetKind", kind.target().name())
				.param("target", targetId)
				.param("message", message)
				.update();
		}
		return created;
	}

	/** 이미 보낸 알림의 문장을 바꾼다(대상 제목을 고친 경우). 읽음 상태는 그대로다. */
	void retitle(NotificationKind kind, long targetId, String message) {
		this.jdbc.sql("""
				UPDATE notifications SET message_text = :message
				 WHERE notification_type = :type AND target_ref_id = :target AND message_text <> :message
				""").param("type", kind.name()).param("target", targetId).param("message", message).update();
	}

	int deleteAll(NotificationKind kind, long targetId) {
		return this.jdbc.sql("DELETE FROM notifications WHERE notification_type = :type AND target_ref_id = :target")
			.param("type", kind.name())
			.param("target", targetId)
			.update();
	}

	// ---- 받는 사람의 읽기와 명령 ----

	/**
	 * 최신순({@code created_at}, {@code notification_id} 역순) 한 쪽.
	 * @param courseId 이 과목의 알림만. 없으면 null
	 * @param kind 이 유형만. 없으면 null
	 * @param after 이 위치 다음부터. 처음이면 null
	 */
	List<NotificationRow> page(long userId, LocalDateTime now, Long courseId, NotificationKind kind, boolean unreadOnly,
			Position after, int limit) {
		StringBuilder where = new StringBuilder(VISIBLE);
		if (courseId != null) {
			where.append(" AND n.course_id = :course");
		}
		if (kind != null) {
			where.append(" AND n.notification_type = :type");
		}
		if (unreadOnly) {
			where.append(" AND n.read_at IS NULL");
		}
		if (after != null) {
			where.append(" AND (n.created_at < :afterAt OR (n.created_at = :afterAt AND n.notification_id < :afterId))");
		}
		return this.jdbc.sql(SELECT + where + " ORDER BY n.created_at DESC, n.notification_id DESC LIMIT :limit")
			.param("user", userId)
			.param("now", now)
			.param("course", courseId)
			.param("type", kind == null ? null : kind.name())
			.param("afterAt", after == null ? null : after.createdAt())
			.param("afterId", after == null ? null : after.notificationId())
			.param("limit", limit)
			.query(NotificationRecords::row)
			.list();
	}

	Optional<NotificationRow> find(long userId, long notificationId, LocalDateTime now) {
		return this.jdbc.sql(SELECT + VISIBLE + " AND n.notification_id = :id")
			.param("user", userId)
			.param("now", now)
			.param("id", notificationId)
			.query(NotificationRecords::row)
			.optional();
	}

	int unread(long userId, LocalDateTime now) {
		return this.jdbc.sql("SELECT COUNT(*) FROM notifications n WHERE " + VISIBLE + " AND n.read_at IS NULL")
			.param("user", userId)
			.param("now", now)
			.query(Integer.class)
			.single();
	}

	/** @return 읽음으로 바뀐 행 수. 이미 읽었거나 볼 수 없으면 0 */
	int markRead(long userId, long notificationId, LocalDateTime now) {
		return this.jdbc.sql("UPDATE notifications n SET n.read_at = :now WHERE " + VISIBLE
				+ " AND n.notification_id = :id AND n.read_at IS NULL")
			.param("user", userId)
			.param("now", now)
			.param("id", notificationId)
			.update();
	}

	/** 지금 볼 수 있고 지금까지 만든 안 읽은 알림 모두. @return 바뀐 행 수 */
	int markAllRead(long userId, LocalDateTime now) {
		return this.jdbc.sql("UPDATE notifications n SET n.read_at = :now WHERE " + VISIBLE
				+ " AND n.read_at IS NULL AND n.created_at <= :now")
			.param("user", userId)
			.param("now", now)
			.update();
	}

	int delete(long userId, long notificationId, LocalDateTime now) {
		return this.jdbc.sql("DELETE n FROM notifications n WHERE " + VISIBLE + " AND n.notification_id = :id")
			.param("user", userId)
			.param("now", now)
			.param("id", notificationId)
			.update();
	}

	/** 보존 기간이 지난 알림을 잠그고 지운다. 다른 worker가 잡은 행은 건너뛴다. @return 지운 수 */
	int purgeExpired(LocalDateTime now, int limit) {
		List<Long> expired = this.jdbc.sql("""
				SELECT notification_id FROM notifications WHERE expires_at <= :now
				 ORDER BY notification_id LIMIT :limit
				   FOR UPDATE SKIP LOCKED
				""").param("now", now).param("limit", limit).query(Long.class).list();
		if (expired.isEmpty()) {
			return 0;
		}
		return this.jdbc.sql("DELETE FROM notifications WHERE notification_id IN (:ids)").param("ids", expired).update();
	}

	private static Event event(ResultSet rs, int n) throws SQLException {
		return new Event(rs.getLong("notification_event_id"), NotificationKind.valueOf(rs.getString("notification_type")),
				rs.getLong("target_ref_id"), rs.getLong("course_id"), rs.getObject("activity_id", Long.class),
				CourseRole.valueOf(rs.getString("audience_role")), rs.getString("message_text"),
				rs.getBoolean("delivered"));
	}

	private static NotificationRow row(ResultSet rs, int n) throws SQLException {
		return new NotificationRow(rs.getLong("notification_id"), rs.getString("notification_type"),
				rs.getObject("course_id", Long.class), rs.getString("course_name"), rs.getString("course_code"),
				rs.getString("section_no"), rs.getObject("activity_id", Long.class), rs.getString("target_kind"),
				rs.getLong("target_ref_id"), rs.getString("message_text"),
				rs.getObject("read_at", LocalDateTime.class), rs.getObject("created_at", LocalDateTime.class));
	}

	/** 목록 순서의 한 위치. keyset paging에 쓴다. */
	record Position(LocalDateTime createdAt, long notificationId) {
	}

	record Event(long eventId, NotificationKind kind, long targetId, long courseId, Long activityId,
			CourseRole audience, String message, boolean delivered) {
	}

	record NotificationRow(long notificationId, String kind, Long courseId, String courseName, String courseCode,
			String section, Long activityId, String targetKind, long targetId, String message, LocalDateTime readAt,
			LocalDateTime createdAt) {

		Position position() {
			return new Position(this.createdAt, this.notificationId);
		}

	}

}
