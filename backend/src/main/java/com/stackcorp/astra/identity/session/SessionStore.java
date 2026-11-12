package com.stackcorp.astra.identity.session;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.stackcorp.astra.identity.api.UserRole;

/**
 * user_sessions. 브라우저에는 불투명 token만 주고 DB에는 그 SHA-256만 둔다(ADR-0004 2번).
 */
@Repository
public class SessionStore {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	SessionStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return 새 session token. 쿠키 값으로만 쓰고 저장하지 않는다 */
	public IssuedSession create(long userId, String ip, String userAgent) {
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random(32));
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO user_sessions (user_id, session_token_hash, session_epoch, ip_address, user_agent, last_seen_at)
				VALUES (:user, :hash, :epoch, :ip, :ua, CURRENT_TIMESTAMP(3))
				""")
			.param("user", userId)
			.param("hash", hash(token))
			.param("epoch", HexFormat.of().formatHex(random(16)))
			.param("ip", ip)
			.param("ua", userAgent == null ? null : userAgent.substring(0, Math.min(255, userAgent.length())))
			.update(key, "session_id");
		return new IssuedSession(key.getKey().longValue(), token);
	}

	Optional<ActiveSession> find(String token) {
		return this.jdbc.sql("""
				SELECT s.session_id, s.user_id, s.authenticated_at, s.last_seen_at, u.user_name, u.user_role, u.account_status
				  FROM user_sessions s JOIN users u ON u.user_id = s.user_id
				 WHERE s.session_token_hash = :hash AND s.revoked_at IS NULL
				""")
			.param("hash", hash(token))
			.query((rs, n) -> new ActiveSession(rs.getLong("session_id"), rs.getLong("user_id"),
					rs.getString("user_name"), UserRole.valueOf(rs.getString("user_role")),
					"ACTIVE".equals(rs.getString("account_status")), instant(rs.getTimestamp("authenticated_at")),
					instant(rs.getTimestamp("last_seen_at"))))
			.optional();
	}

	/** 마지막 활동 시각을 1분 단위로만 갱신한다. 요청마다 쓰지 않는다. */
	void touch(long sessionId) {
		this.jdbc.sql("""
				UPDATE user_sessions SET last_seen_at = CURRENT_TIMESTAMP(3)
				 WHERE session_id = :id AND (last_seen_at IS NULL OR last_seen_at < DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE))
				""").param("id", sessionId).update();
	}

	/** @param reason LOGOUT|EXPIRED|ADMIN 등 user_sessions.revoke_reason 값 */
	public void revoke(long sessionId, String reason) {
		this.jdbc.sql("""
				UPDATE user_sessions SET revoked_at = CURRENT_TIMESTAMP(3), revoke_reason = :reason
				 WHERE session_id = :id AND revoked_at IS NULL
				""").param("reason", reason).param("id", sessionId).update();
	}

	public record IssuedSession(long sessionId, String token) {
	}

	record ActiveSession(long sessionId, long userId, String name, UserRole role, boolean active,
			Instant authenticatedAt, Instant lastSeenAt) {
	}

	static String hash(String token) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] random(int bytes) {
		byte[] value = new byte[bytes];
		RANDOM.nextBytes(value);
		return value;
	}

	private static Instant instant(Timestamp t) {
		return t == null ? null : t.toInstant();
	}

}
