package com.stackcorp.astra.identity.account;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.identity.session.SessionProperties;

/**
 * 내 로그인 session, 등록 기기, 로그인 기록(user_sessions, user_devices, login_events). 모든 문장이 요청한 사용자의 행만 다룬다.
 * <p>
 * 여러 행을 폐기하는 문장은 user_id·device_id 같은 유일하지 않은 index를 지나므로, 다른 transaction과 gap lock으로 엉키지 않게
 * READ COMMITTED에서 실행하고 교착이나 잠금 대기 초과면 몇 번 다시 한다. 기기를 폐기할 때는 기기 행을 먼저, session을 나중에
 * 잠근다.
 */
@Repository
class AccountSessions {

	private static final int ATTEMPTS = 3;

	private final JdbcClient jdbc;

	private final TransactionTemplate readCommitted;

	private final SessionProperties properties;

	AccountSessions(JdbcClient jdbc, PlatformTransactionManager transactions, SessionProperties properties) {
		this.jdbc = jdbc;
		this.readCommitted = new TransactionTemplate(transactions);
		this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.properties = properties;
	}

	/** 폐기하지 않았고 유휴·절대 만료 전인 session. 최근 로그인 순, 100개까지. */
	List<SessionView> activeSessions(long userId, long currentSessionId) {
		return this.jdbc.sql("""
				SELECT s.session_id, s.authenticated_at, s.last_seen_at, s.ip_address, s.user_agent, d.device_uuid
				  FROM user_sessions s LEFT JOIN user_devices d ON d.device_id = s.device_id
				 WHERE s.user_id = :user AND s.revoked_at IS NULL
				   AND s.authenticated_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :absolute SECOND)
				   AND COALESCE(s.last_seen_at, s.authenticated_at) > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :idle SECOND)
				 ORDER BY s.authenticated_at DESC, s.session_id DESC
				 LIMIT 100
				""")
			.param("user", userId)
			.param("absolute", this.properties.absoluteTimeout().toSeconds())
			.param("idle", this.properties.idleTimeout().toSeconds())
			.query((rs, n) -> new SessionView(rs.getLong(1), rs.getLong(1) == currentSessionId,
					rs.getObject(2, LocalDateTime.class), rs.getObject(3, LocalDateTime.class), rs.getString(4),
					rs.getString(5), rs.getString(6)))
			.list();
	}

	/** @return 이 사용자의 폐기하지 않은 session이었으면 true */
	boolean revoke(long userId, long sessionId) {
		return this.jdbc.sql("""
				UPDATE user_sessions SET revoked_at = CURRENT_TIMESTAMP(3), revoke_reason = 'USER_REVOKED'
				 WHERE session_id = :session AND user_id = :user AND revoked_at IS NULL
				""").param("session", sessionId).param("user", userId).update() == 1;
	}

	/** 지금 session 말고 이 사용자의 모든 session을 폐기한다. @return 폐기한 수 */
	int revokeOthers(long userId, long currentSessionId) {
		return retrying(() -> this.readCommitted.execute((status) -> this.jdbc.sql("""
				UPDATE user_sessions SET revoked_at = CURRENT_TIMESTAMP(3), revoke_reason = 'USER_REVOKED'
				 WHERE user_id = :user AND session_id <> :current AND revoked_at IS NULL
				""").param("user", userId).param("current", currentSessionId).update()));
	}

	/** 폐기하지 않은 기기. 등록 순(device_id) 역순 keyset이다. */
	List<DeviceView> devices(long userId, long currentSessionId, Long before, int limit) {
		return this.jdbc.sql("""
				SELECT d.device_id, d.device_uuid, d.device_name, d.client_type, d.registered_at, d.last_access_at,
				       d.is_certified,
				       EXISTS (SELECT 1 FROM user_sessions s WHERE s.session_id = :current AND s.device_id = d.device_id)
				  FROM user_devices d
				 WHERE d.user_id = :user AND d.revoked_at IS NULL AND (:before IS NULL OR d.device_id < :before)
				 ORDER BY d.device_id DESC
				 LIMIT :limit
				""")
			.param("user", userId)
			.param("current", currentSessionId)
			.param("before", before)
			.param("limit", limit)
			.query((rs, n) -> new DeviceView(rs.getLong(1), rs.getString(2), rs.getString(3),
					rs.getString(4).toLowerCase(), rs.getObject(5, LocalDateTime.class),
					rs.getObject(6, LocalDateTime.class), rs.getBoolean(7), rs.getBoolean(8)))
			.list();
	}

	/**
	 * 기기를 폐기하고 그 기기에 묶인 session을 모두 폐기한다(IDENTITY-07). 기록은 지우지 않는다.
	 * @return 이 사용자의 폐기하지 않은 기기가 아니면 비어 있다
	 */
	Optional<DeviceRevocation> revokeDevice(long userId, String deviceUuid, long currentSessionId) {
		return retrying(() -> this.readCommitted.execute((status) -> {
			Optional<Long> deviceId = this.jdbc.sql("""
					SELECT device_id FROM user_devices
					 WHERE device_uuid = :uuid AND user_id = :user AND revoked_at IS NULL
					   FOR UPDATE
					""").param("uuid", deviceUuid).param("user", userId).query(Long.class).optional();
			if (deviceId.isEmpty()) {
				return Optional.<DeviceRevocation>empty();
			}
			this.jdbc.sql("UPDATE user_devices SET revoked_at = CURRENT_TIMESTAMP(3) WHERE device_id = :id")
				.param("id", deviceId.get())
				.update();
			boolean current = this.jdbc.sql("""
					SELECT COUNT(*) FROM user_sessions WHERE session_id = :current AND device_id = :device
					""").param("current", currentSessionId).param("device", deviceId.get()).query(Integer.class).single() > 0;
			int sessions = this.jdbc.sql("""
					UPDATE user_sessions SET revoked_at = CURRENT_TIMESTAMP(3), revoke_reason = 'DEVICE_REVOKED'
					 WHERE device_id = :device AND user_id = :user AND revoked_at IS NULL
					""").param("device", deviceId.get()).param("user", userId).update();
			return Optional.of(new DeviceRevocation(deviceId.get(), sessions, current));
		}));
	}

	/** 최근 7일 로그인 기록(화면 11). 최신순 keyset이다. */
	List<LoginView> loginEvents(long userId, Long before, int limit) {
		return this.jdbc.sql("""
				SELECT login_event_id, logged_in_at, ip_address, client_environment, login_result
				  FROM login_events
				 WHERE user_id = :user AND logged_in_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 7 DAY)
				   AND (:before IS NULL OR login_event_id < :before)
				 ORDER BY login_event_id DESC
				 LIMIT :limit
				""")
			.param("user", userId)
			.param("before", before)
			.param("limit", limit)
			.query((rs, n) -> new LoginView(rs.getLong(1), rs.getObject(2, LocalDateTime.class), rs.getString(3),
					rs.getString(4), "SUCCESS".equals(rs.getString(5))))
			.list();
	}

	private static <T> T retrying(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return work.get();
			}
			catch (TransientDataAccessException ex) {
				if (attempt == ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	/**
	 * @param current 이 요청의 session이다
	 * @param userAgent 로그인할 때 브라우저가 보낸 값. 일반 텍스트로만 표시한다
	 * @param deviceId 묶인 기기의 외부 식별자. 없으면 null
	 */
	record SessionView(long sessionId, boolean current, LocalDateTime authenticatedAt, LocalDateTime lastSeenAt,
			String ipAddress, String userAgent, String deviceId) {
	}

	/**
	 * @param cursorId 목록 순서의 내부 순번. 다음 쪽 cursor가 이 값이다
	 * @param deviceId 외부 식별자(UUID). 폐기할 때 쓴다
	 * @param clientType browser | app
	 * @param current 이 요청의 session이 묶인 기기다
	 */
	record DeviceView(long cursorId, String deviceId, String name, String clientType, LocalDateTime registeredAt,
			LocalDateTime lastAccessAt, boolean certified, boolean current) {
	}

	record DeviceRevocation(long deviceId, int revokedSessions, boolean currentSessionRevoked) {
	}

	/**
	 * @param clientEnvironment PC | MOBILE
	 * @param success 로그인에 성공했는지. 실패 이유는 알리지 않는다
	 */
	record LoginView(long cursorId, LocalDateTime loggedInAt, String ipAddress, String clientEnvironment,
			boolean success) {
	}

}
