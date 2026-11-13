package com.stackcorp.astra.summary.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * study_chats, study_chat_exchanges. 본문은 없고 상태와 사용량만 있다. 상태 전이는 현재 상태를 조건으로 거는 UPDATE이고, 실행 중
 * 질문의 갱신은 lease 소유자일 때만 반영된다. 대화 행을 먼저 잠그고 질문 행을 바꾼다.
 */
@Repository
class StudyChatRepository {

	private static final String CHAT_COLUMNS = """
			study_chat_id, owner_user_id, course_id, file_id, exchange_count, created_at, last_activity_at, deleted_at""";

	private static final String EXCHANGE_COLUMNS = """
			study_chat_exchange_id, study_chat_id, exchange_state, error_code, call_count, tool_call_count, input_tokens,
			output_tokens, created_at, started_at, finished_at""";

	private final JdbcClient jdbc;

	StudyChatRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	long insertChat(long ownerUserId, long courseId, long fileId) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO study_chats (owner_user_id, course_id, file_id) VALUES (:owner, :course, :file)
				""").param("owner", ownerUserId).param("course", courseId).param("file", fileId).update(key,
				"study_chat_id");
		return key.getKey().longValue();
	}

	/** 지운 대화도 돌려준다. 부르는 쪽이 {@link StudyChat#deleted()}를 본다. */
	Optional<StudyChat> findChat(long chatId) {
		return this.jdbc.sql("SELECT " + CHAT_COLUMNS + " FROM study_chats WHERE study_chat_id = :id")
			.param("id", chatId)
			.query(StudyChatRepository::chat)
			.optional();
	}

	/** 소유자의 그 파일 대화. 최신이 먼저이고 before보다 작은 ID만 돌려준다. */
	List<StudyChat> listChats(long ownerUserId, long fileId, Long before, int limit) {
		return this.jdbc.sql("SELECT " + CHAT_COLUMNS + """
				 FROM study_chats
				 WHERE owner_user_id = :owner AND file_id = :file AND deleted_at IS NULL
				   AND (:before IS NULL OR study_chat_id < :before)
				 ORDER BY study_chat_id DESC LIMIT :limit
				""")
			.param("owner", ownerUserId)
			.param("file", fileId)
			.param("before", before)
			.param("limit", limit)
			.query(StudyChatRepository::chat)
			.list();
	}

	/** 질문을 받기 전에 대화 행을 잠그고 수를 늘린다. @return 지운 대화면 false */
	boolean touchChat(long chatId) {
		return this.jdbc.sql("""
				UPDATE study_chats SET exchange_count = exchange_count + 1, last_activity_at = CURRENT_TIMESTAMP(3)
				 WHERE study_chat_id = :id AND deleted_at IS NULL
				""").param("id", chatId).update() == 1;
	}

	/**
	 * @throws org.springframework.dao.DuplicateKeyException 그 대화에 답을 기다리는 질문이 있을 때
	 */
	long insertExchange(long chatId) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("INSERT INTO study_chat_exchanges (study_chat_id) VALUES (:chat)")
			.param("chat", chatId)
			.update(key, "study_chat_exchange_id");
		return key.getKey().longValue();
	}

	Optional<Exchange> findExchange(long exchangeId) {
		return this.jdbc.sql("SELECT " + EXCHANGE_COLUMNS + " FROM study_chat_exchanges WHERE study_chat_exchange_id = :id")
			.param("id", exchangeId)
			.query(StudyChatRepository::exchange)
			.optional();
	}

	/** 대화의 질문. 최신이 먼저이고 before보다 작은 ID만 돌려준다. */
	List<Exchange> listExchanges(long chatId, Long before, int limit) {
		return this.jdbc.sql("SELECT " + EXCHANGE_COLUMNS + """
				 FROM study_chat_exchanges
				 WHERE study_chat_id = :chat AND (:before IS NULL OR study_chat_exchange_id < :before)
				 ORDER BY study_chat_exchange_id DESC LIMIT :limit
				""")
			.param("chat", chatId)
			.param("before", before)
			.param("limit", limit)
			.query(StudyChatRepository::exchange)
			.list();
	}

	/** 지금 질문 앞의 답한 질문. 오래된 것이 먼저다. */
	List<Exchange> answeredBefore(long chatId, long exchangeId, int limit) {
		List<Exchange> recent = this.jdbc.sql("SELECT " + EXCHANGE_COLUMNS + """
				 FROM study_chat_exchanges
				 WHERE study_chat_id = :chat AND study_chat_exchange_id < :id AND exchange_state = 'answered'
				 ORDER BY study_chat_exchange_id DESC LIMIT :limit
				""")
			.param("chat", chatId)
			.param("id", exchangeId)
			.param("limit", limit)
			.query(StudyChatRepository::exchange)
			.list();
		return recent.reversed();
	}

	/**
	 * 가장 오래된 대기 질문 하나를 running으로 바꾸고 lease를 건다. 부르는 쪽의 transaction 안에서 부른다. (상태, ID) 색인 순서로
	 * 읽어 첫 행에서 멈추므로 다른 worker는 잠긴 행을 건너뛰고 다음 질문을 잡는다. 정렬을 따로 하면 읽은 행을 모두 잠근다.
	 */
	Optional<Exchange> claimNext(String owner, Duration lease) {
		Optional<Long> id = this.jdbc.sql("""
				SELECT study_chat_exchange_id FROM study_chat_exchanges WHERE exchange_state = 'queued'
				 ORDER BY study_chat_exchange_id LIMIT 1 FOR UPDATE SKIP LOCKED
				""").query(Long.class).optional();
		if (id.isEmpty()) {
			return Optional.empty();
		}
		this.jdbc.sql("""
				UPDATE study_chat_exchanges
				   SET exchange_state = 'running', lease_owner = :owner, started_at = CURRENT_TIMESTAMP(3),
				       lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND)
				 WHERE study_chat_exchange_id = :id AND exchange_state = 'queued'
				""").param("owner", owner).param("lease", lease.toSeconds()).param("id", id.get()).update();
		return findExchange(id.get());
	}

	/** @return lease를 아직 갖고 있으면 true */
	boolean renewLease(long exchangeId, String owner, Duration lease) {
		return this.jdbc.sql("""
				UPDATE study_chat_exchanges SET lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND)
				 WHERE study_chat_exchange_id = :id AND exchange_state = 'running' AND lease_owner = :owner
				""").param("lease", lease.toSeconds()).param("id", exchangeId).param("owner", owner).update() == 1;
	}

	/** 실행 중인 질문을 끝내고 lease를 푼다. @return lease를 잃었거나 대화가 지워져 반영하지 못했으면 false */
	boolean finish(long exchangeId, String owner, ExchangeState state, String errorCode, int calls, int toolCalls,
			long inputTokens, long outputTokens) {
		return this.jdbc.sql("""
				UPDATE study_chat_exchanges
				   SET exchange_state = :state, error_code = :code, call_count = :calls, tool_call_count = :tools,
				       input_tokens = :input, output_tokens = :output, finished_at = CURRENT_TIMESTAMP(3),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE study_chat_exchange_id = :id AND exchange_state = 'running' AND lease_owner = :owner
				""")
			.param("state", state.value())
			.param("code", errorCode)
			.param("calls", calls)
			.param("tools", toolCalls)
			.param("input", inputTokens)
			.param("output", outputTokens)
			.param("id", exchangeId)
			.param("owner", owner)
			.update() == 1;
	}

	/** lease가 끝난 실행 중 질문을 interrupted로 닫는다. 자동으로 다시 실행하지 않는다. */
	int failExpired() {
		return this.jdbc.sql("""
				UPDATE study_chat_exchanges
				   SET exchange_state = 'failed', error_code = 'interrupted', finished_at = CURRENT_TIMESTAMP(3),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE exchange_state = 'running' AND lease_expires_at < CURRENT_TIMESTAMP(3)
				""").update();
	}

	/** 그 교수자가 그 과목에 게시한 파일의 대화에서 대기 중인 질문. 잠그지 않고 읽는다. */
	List<Long> queuedForPublisher(long courseId, long publisherUserId) {
		return this.jdbc.sql("""
				SELECT e.study_chat_exchange_id
				  FROM study_chat_exchanges e
				  JOIN study_chats c ON c.study_chat_id = e.study_chat_id
				  JOIN summary_publications p ON p.file_id = c.file_id
				 WHERE c.course_id = :course AND p.published_by_user_id = :user AND e.exchange_state = 'queued'
				 ORDER BY e.study_chat_exchange_id
				""").param("course", courseId).param("user", publisherUserId).query(Long.class).list();
	}

	/** 대기 중인 질문을 취소한다. 이미 실행에 들어간 질문은 worker가 모델 호출 전에 같은 조건을 보고 멈춘다. */
	int cancelQueued(List<Long> exchangeIds, String errorCode) {
		if (exchangeIds.isEmpty()) {
			return 0;
		}
		return this.jdbc.sql("""
				UPDATE study_chat_exchanges
				   SET exchange_state = 'cancelled', error_code = :code, finished_at = CURRENT_TIMESTAMP(3)
				 WHERE study_chat_exchange_id IN (:ids) AND exchange_state = 'queued'
				 ORDER BY study_chat_exchange_id
				""").param("code", errorCode).param("ids", exchangeIds).update();
	}

	/**
	 * 대화를 지운 것으로 표시하고 답을 기다리던 질문을 취소한다. 실행 중인 질문도 취소해 worker가 답을 쓰지 못하게 한다.
	 * @return 지우지 않은 대화였으면 true
	 */
	boolean markDeleted(long chatId) {
		if (this.jdbc.sql("""
				UPDATE study_chats SET deleted_at = CURRENT_TIMESTAMP(3) WHERE study_chat_id = :id AND deleted_at IS NULL
				""").param("id", chatId).update() != 1) {
			return false;
		}
		this.jdbc.sql("""
				UPDATE study_chat_exchanges
				   SET exchange_state = 'cancelled', error_code = 'deleted', finished_at = CURRENT_TIMESTAMP(3),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE study_chat_id = :id AND exchange_state IN ('queued', 'running')
				""").param("id", chatId).update();
		return true;
	}

	/**
	 * 정리할 대화: 지운 것으로 표시한 대화와, retention이 있으면 마지막 질문이 그보다 오래된 대화.
	 * @param retention null이면 기간으로 고르지 않는다
	 */
	List<StudyChat> purgeCandidates(Duration retention, int limit) {
		return this.jdbc.sql("SELECT " + CHAT_COLUMNS + """
				 FROM study_chats
				 WHERE deleted_at IS NOT NULL
				    OR (:retention IS NOT NULL
				        AND last_activity_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :retention SECOND))
				 ORDER BY study_chat_id LIMIT :limit
				""")
			.param("retention", retention == null ? null : retention.toSeconds())
			.param("limit", limit)
			.query(StudyChatRepository::chat)
			.list();
	}

	/** 본문 객체를 지운 뒤 행을 지운다. 지운 것으로 표시한 대화만 지운다. */
	void deleteRows(long chatId) {
		this.jdbc.sql("""
				DELETE e FROM study_chat_exchanges e JOIN study_chats c ON c.study_chat_id = e.study_chat_id
				 WHERE c.study_chat_id = :id AND c.deleted_at IS NOT NULL
				""").param("id", chatId).update();
		this.jdbc.sql("DELETE FROM study_chats WHERE study_chat_id = :id AND deleted_at IS NOT NULL")
			.param("id", chatId)
			.update();
	}

	private static StudyChat chat(ResultSet rs, int n) throws SQLException {
		return new StudyChat(rs.getLong("study_chat_id"), rs.getLong("owner_user_id"), rs.getLong("course_id"),
				rs.getLong("file_id"), rs.getInt("exchange_count"), instant(rs, "created_at"),
				instant(rs, "last_activity_at"), rs.getTimestamp("deleted_at") != null);
	}

	private static Exchange exchange(ResultSet rs, int n) throws SQLException {
		return new Exchange(rs.getLong("study_chat_exchange_id"), rs.getLong("study_chat_id"),
				ExchangeState.of(rs.getString("exchange_state")), rs.getString("error_code"), rs.getInt("call_count"),
				rs.getInt("tool_call_count"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
				instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

}
