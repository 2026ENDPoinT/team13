package com.stackcorp.astra.communication;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 쪽지(messages)와 수신자 행(message_recipients). 받은함은 수신자 행, 보낸함은 쪽지 행이 정한다. 한쪽 보관함의 삭제는 그 행의 삭제
 * 일시만 채우고 다른 보관함에는 손대지 않는다(ADR-0025). 모든 조회와 변경은 요청한 사용자의 행으로 좁힌다(SEC-08).
 */
@Repository
class MessageRepository {

	/** 받은함에 보이는 수신자 행. 회수된 행과 지운 행은 보이지 않는다. */
	private static final String INBOX_VISIBLE = " r.recipient_id = :user AND r.recipient_deleted_at IS NULL AND r.canceled_at IS NULL";

	private static final String TITLE_MATCH = " AND m.title LIKE CONCAT('%', :q, '%')";

	private final JdbcClient jdbc;

	MessageRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	long insertMessage(long senderId, Long courseId, String title, String body, int recipientCount, Long originId,
			String originKind, LocalDateTime now) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO messages (sender_id, course_id, title, body_text, recipient_count, origin_message_id, origin_kind,
				                      created_at)
				VALUES (:sender, :course, :title, :body, :count, :origin, :kind, :now)
				""")
			.param("sender", senderId)
			.param("course", courseId)
			.param("title", title)
			.param("body", body)
			.param("count", recipientCount)
			.param("origin", originId)
			.param("kind", originKind)
			.param("now", now)
			.update(key);
		return key.getKey().longValue();
	}

	/** 수신자 행을 문장 하나로 넣는다. 행의 created_at은 쪽지의 발송 일시와 같다. */
	void insertRecipients(long messageId, List<Long> recipientIds, Long courseId, LocalDateTime now) {
		StringBuilder sql = new StringBuilder(
				"INSERT INTO message_recipients (message_id, recipient_id, course_id, created_at) VALUES ");
		Map<String, Object> params = new HashMap<>();
		params.put("message", messageId);
		params.put("course", courseId);
		params.put("now", now);
		for (int i = 0; i < recipientIds.size(); i++) {
			sql.append(i == 0 ? "" : ", ").append("(:message, :r").append(i).append(", :course, :now)");
			params.put("r" + i, recipientIds.get(i));
		}
		this.jdbc.sql(sql.toString()).params(params).update();
	}

	// ---- 받은함

	List<InboxRow> inboxPage(long userId, Boolean read, String query, Position after, int limit) {
		String sql = """
				SELECT m.message_id, m.title, m.sender_id, m.course_id, r.created_at, r.read_at
				  FROM message_recipients r JOIN messages m ON m.message_id = r.message_id
				 WHERE""" + INBOX_VISIBLE + readFilter(read) + (query == null ? "" : TITLE_MATCH)
				+ (after == null ? "" : " AND (r.created_at < :at OR (r.created_at = :at AND r.message_id < :afterId))")
				+ " ORDER BY r.created_at DESC, r.message_id DESC LIMIT :limit";
		var statement = this.jdbc.sql(sql).param("user", userId).param("limit", limit);
		statement = query == null ? statement : statement.param("q", likeLiteral(query));
		statement = after == null ? statement : statement.param("at", after.sentAt()).param("afterId", after.messageId());
		return statement.query((rs, n) -> new InboxRow(rs.getLong(1), rs.getString(2), rs.getLong(3),
				rs.getObject(4, Long.class), rs.getObject(5, LocalDateTime.class), rs.getObject(6, LocalDateTime.class)))
			.list();
	}

	long inboxCount(long userId, Boolean read, String query) {
		String sql = "SELECT COUNT(*) FROM message_recipients r JOIN messages m ON m.message_id = r.message_id WHERE"
				+ INBOX_VISIBLE + readFilter(read) + (query == null ? "" : TITLE_MATCH);
		var statement = this.jdbc.sql(sql).param("user", userId);
		statement = query == null ? statement : statement.param("q", likeLiteral(query));
		return statement.query(Long.class).single();
	}

	/** idx_message_recipients_inbox만 읽는다. */
	int unreadCount(long userId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM message_recipients
				 WHERE recipient_id = :user AND recipient_deleted_at IS NULL AND canceled_at IS NULL AND read_at IS NULL
				""").param("user", userId).query(Integer.class).single();
	}

	Optional<Received> received(long userId, long messageId) {
		return this.jdbc.sql("""
				SELECT m.message_id, m.sender_id, m.course_id, m.title, m.body_text, m.origin_kind, r.created_at, r.read_at
				  FROM message_recipients r JOIN messages m ON m.message_id = r.message_id
				 WHERE r.message_id = :message AND""" + INBOX_VISIBLE)
			.param("user", userId)
			.param("message", messageId)
			.query((rs, n) -> new Received(message(rs), rs.getObject("read_at", LocalDateTime.class)))
			.optional();
	}

	/** 읽음 전이. 회수·삭제되지 않았고 아직 읽지 않은 행만 바꾼다. 회수(recall)와 조건이 서로 배타적이라 둘 중 하나만 이긴다. */
	int markRead(long userId, long messageId, LocalDateTime now) {
		return this.jdbc.sql("""
				UPDATE message_recipients SET read_at = :now
				 WHERE message_id = :message AND recipient_id = :user
				   AND read_at IS NULL AND canceled_at IS NULL AND recipient_deleted_at IS NULL
				""").param("now", now).param("message", messageId).param("user", userId).update();
	}

	/** 상태와 관계없이 요청한 사용자가 받은 쪽지인 ID의 수. 회수되거나 이미 지운 행도 센다. */
	int ownedInInbox(long userId, Collection<Long> messageIds) {
		return this.jdbc.sql("SELECT COUNT(*) FROM message_recipients WHERE recipient_id = :user AND message_id IN (:ids)")
			.param("user", userId)
			.param("ids", messageIds)
			.query(Integer.class)
			.single();
	}

	int deleteFromInbox(long userId, Collection<Long> messageIds, LocalDateTime now) {
		return this.jdbc.sql("""
				UPDATE message_recipients SET recipient_deleted_at = :now
				 WHERE recipient_id = :user AND message_id IN (:ids) AND recipient_deleted_at IS NULL
				""").param("now", now).param("user", userId).param("ids", messageIds).update();
	}

	/** 목록과 같은 조건으로 until까지 받은 쪽지를 지운다. 목록에 보이지 않는 회수된 행은 건드리지 않는다. */
	int deleteInboxMatching(long userId, Boolean read, String query, LocalDateTime until, Collection<Long> except,
			LocalDateTime now) {
		String sql = "UPDATE message_recipients r SET r.recipient_deleted_at = :now WHERE" + INBOX_VISIBLE
				+ " AND r.created_at <= :until" + readFilter(read)
				+ (query == null ? ""
						: " AND EXISTS (SELECT 1 FROM messages m WHERE m.message_id = r.message_id" + TITLE_MATCH + ")")
				+ (except.isEmpty() ? "" : " AND r.message_id NOT IN (:except)");
		var statement = this.jdbc.sql(sql).param("now", now).param("user", userId).param("until", until);
		statement = query == null ? statement : statement.param("q", likeLiteral(query));
		statement = except.isEmpty() ? statement : statement.param("except", except);
		return statement.update();
	}

	// ---- 보낸함

	List<SentRow> sentPage(long userId, String query, Position after, int limit) {
		String sql = """
				SELECT m.message_id, m.title, m.course_id, m.created_at, m.recipient_count,
				       (SELECT COUNT(*) FROM message_recipients r WHERE r.message_id = m.message_id AND r.read_at IS NOT NULL) AS read_count,
				       (SELECT COUNT(*) FROM message_recipients r WHERE r.message_id = m.message_id AND r.canceled_at IS NOT NULL) AS recalled_count,
				       (SELECT r.recipient_id FROM message_recipients r WHERE r.message_id = m.message_id
				         ORDER BY r.message_recipient_id LIMIT 1) AS first_recipient
				  FROM messages m
				 WHERE m.sender_id = :user AND m.sender_deleted_at IS NULL""" + (query == null ? "" : TITLE_MATCH)
				+ (after == null ? "" : " AND (m.created_at < :at OR (m.created_at = :at AND m.message_id < :afterId))")
				+ " ORDER BY m.created_at DESC, m.message_id DESC LIMIT :limit";
		var statement = this.jdbc.sql(sql).param("user", userId).param("limit", limit);
		statement = query == null ? statement : statement.param("q", likeLiteral(query));
		statement = after == null ? statement : statement.param("at", after.sentAt()).param("afterId", after.messageId());
		return statement
			.query((rs, n) -> new SentRow(rs.getLong("message_id"), rs.getString("title"),
					rs.getObject("course_id", Long.class), rs.getObject("created_at", LocalDateTime.class),
					rs.getInt("recipient_count"), rs.getInt("read_count"), rs.getInt("recalled_count"),
					rs.getLong("first_recipient")))
			.list();
	}

	long sentCount(long userId, String query) {
		var statement = this.jdbc
			.sql("SELECT COUNT(*) FROM messages m WHERE m.sender_id = :user AND m.sender_deleted_at IS NULL"
					+ (query == null ? "" : TITLE_MATCH))
			.param("user", userId);
		statement = query == null ? statement : statement.param("q", likeLiteral(query));
		return statement.query(Long.class).single();
	}

	/** 보낸함에 보이는 쪽지. 발신자가 지웠으면 비어 있다. */
	Optional<MessageRow> sent(long userId, long messageId) {
		return this.jdbc.sql("""
				SELECT m.message_id, m.sender_id, m.course_id, m.title, m.body_text, m.origin_kind, m.created_at
				  FROM messages m
				 WHERE m.message_id = :message AND m.sender_id = :user AND m.sender_deleted_at IS NULL
				""")
			.param("user", userId)
			.param("message", messageId)
			.query((rs, n) -> message(rs))
			.optional();
	}

	/** 발신자 행을 잠근다. 회수가 수신자 행보다 쪽지 행을 먼저 잠그도록 순서를 고정한다. */
	Optional<Long> lockSent(long userId, long messageId) {
		return this.jdbc.sql("""
				SELECT message_id FROM messages
				 WHERE message_id = :message AND sender_id = :user AND sender_deleted_at IS NULL FOR UPDATE
				""").param("user", userId).param("message", messageId).query(Long.class).optional();
	}

	/** 수신자 행. 보낸 순서(행 ID)다. */
	List<RecipientRow> recipients(long messageId) {
		return this.jdbc.sql("""
				SELECT recipient_id, read_at, canceled_at FROM message_recipients
				 WHERE message_id = :message ORDER BY message_recipient_id
				""")
			.param("message", messageId)
			.query((rs, n) -> new RecipientRow(rs.getLong(1), rs.getObject(2, LocalDateTime.class),
					rs.getObject(3, LocalDateTime.class)))
			.list();
	}

	/** 회수 전이. 아직 읽지 않았고 회수되지 않은 수신자 행만 바꾼다. 읽음과 조건이 서로 배타적이다. */
	int recall(long messageId, LocalDateTime now) {
		return this.jdbc.sql("""
				UPDATE message_recipients SET canceled_at = :now
				 WHERE message_id = :message AND read_at IS NULL AND canceled_at IS NULL
				""").param("now", now).param("message", messageId).update();
	}

	int ownedInSent(long userId, Collection<Long> messageIds) {
		return this.jdbc.sql("SELECT COUNT(*) FROM messages WHERE sender_id = :user AND message_id IN (:ids)")
			.param("user", userId)
			.param("ids", messageIds)
			.query(Integer.class)
			.single();
	}

	int deleteFromSent(long userId, Collection<Long> messageIds, LocalDateTime now) {
		return this.jdbc.sql("""
				UPDATE messages SET sender_deleted_at = :now
				 WHERE sender_id = :user AND message_id IN (:ids) AND sender_deleted_at IS NULL
				""").param("now", now).param("user", userId).param("ids", messageIds).update();
	}

	int deleteSentMatching(long userId, String query, LocalDateTime until, Collection<Long> except, LocalDateTime now) {
		String sql = "UPDATE messages m SET m.sender_deleted_at = :now"
				+ " WHERE m.sender_id = :user AND m.sender_deleted_at IS NULL AND m.created_at <= :until"
				+ (query == null ? "" : TITLE_MATCH) + (except.isEmpty() ? "" : " AND m.message_id NOT IN (:except)");
		var statement = this.jdbc.sql(sql).param("now", now).param("user", userId).param("until", until);
		statement = query == null ? statement : statement.param("q", likeLiteral(query));
		statement = except.isEmpty() ? statement : statement.param("except", except);
		return statement.update();
	}

	private static String readFilter(Boolean read) {
		if (read == null) {
			return "";
		}
		return read ? " AND r.read_at IS NOT NULL" : " AND r.read_at IS NULL";
	}

	private static MessageRow message(ResultSet rs) throws SQLException {
		return new MessageRow(rs.getLong("message_id"), rs.getLong("sender_id"), rs.getObject("course_id", Long.class),
				rs.getString("title"), rs.getString("body_text"), rs.getString("origin_kind"),
				rs.getObject("created_at", LocalDateTime.class));
	}

	/** LIKE 안에서 사용자 입력의 %, _, \를 글자 그대로 비교하게 escape한다. */
	private static String likeLiteral(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	/** 목록 keyset의 위치. 발송 일시와 쪽지 ID의 역순이다. */
	record Position(LocalDateTime sentAt, long messageId) {
	}

	record InboxRow(long messageId, String title, long senderId, Long courseId, LocalDateTime sentAt,
			LocalDateTime readAt) {
	}

	record SentRow(long messageId, String title, Long courseId, LocalDateTime sentAt, int recipientCount, int readCount,
			int recalledCount, long firstRecipientId) {
	}

	/**
	 * @param originKind REPLY|FORWARD. 새로 쓴 쪽지면 null
	 */
	record MessageRow(long messageId, long senderId, Long courseId, String title, String body, String originKind,
			LocalDateTime sentAt) {
	}

	record Received(MessageRow message, LocalDateTime readAt) {
	}

	record RecipientRow(long recipientId, LocalDateTime readAt, LocalDateTime recalledAt) {
	}

}
