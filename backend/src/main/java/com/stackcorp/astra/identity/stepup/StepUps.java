package com.stackcorp.astra.identity.stepup;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * session_step_ups. 로그인 session과 목적마다 한 행이다. 다시 통과하면 같은 행을 새 시각으로 덮는다. 시각은 모두 DB 시계다.
 * session이 폐기되면 그 session의 행은 쓰이지 않는다(읽을 때 user_sessions.revoked_at을 함께 본다).
 */
@Repository
public class StepUps {

	/** 민감한 연산 전의 재인증. */
	public static final String RECENT_AUTH = "RECENT_AUTH";

	/** 학습 인증 grant(SC-06-A). */
	public static final String LEARNING_AUTH = "LEARNING_AUTH";

	/** 학교 IdP에서 다시 로그인했다. */
	public static final String METHOD_SSO = "SSO";

	/** break-glass 계정이 TOTP를 다시 입력했다. */
	public static final String METHOD_TOTP = "TOTP";

	/** 2차 인증 challenge를 통과했다. */
	public static final String METHOD_SECONDARY_AUTH = "SECONDARY_AUTH";

	/** 로컬 실행의 개발용 재인증(테스트 소스에만 있다). */
	public static final String METHOD_DEV = "DEV";

	private final JdbcClient jdbc;

	StepUps(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 지금부터 {@code lifetime} 동안 유효한 통과 기록을 남긴다. */
	public void grant(long sessionId, String purpose, String method, Duration lifetime) {
		this.jdbc.sql("""
				INSERT INTO session_step_ups (session_id, step_up_purpose, granted_method, granted_at, expires_at)
				VALUES (:session, :purpose, :method, CURRENT_TIMESTAMP(3),
				        TIMESTAMPADD(SECOND, :seconds, CURRENT_TIMESTAMP(3))) AS n
				ON DUPLICATE KEY UPDATE granted_method = n.granted_method, granted_at = n.granted_at, expires_at = n.expires_at
				""")
			.param("session", sessionId)
			.param("purpose", purpose)
			.param("method", method)
			.param("seconds", lifetime.toSeconds())
			.update();
	}

	/** 폐기하지 않은 session에 {@code maxAge} 안에 받은, 아직 만료되지 않은 통과 기록이 있는지. */
	boolean recent(long sessionId, String purpose, Duration maxAge) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM session_step_ups g JOIN user_sessions s ON s.session_id = g.session_id
				 WHERE g.session_id = :session AND g.step_up_purpose = :purpose AND s.revoked_at IS NULL
				   AND g.expires_at > CURRENT_TIMESTAMP(3)
				   AND g.granted_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :seconds SECOND)
				""")
			.param("session", sessionId)
			.param("purpose", purpose)
			.param("seconds", maxAge.toSeconds())
			.query(Integer.class)
			.single() > 0;
	}

	/** {@code since} 뒤에 아래 수단 중 하나로 받은, 아직 만료되지 않은 재인증이 있는지. 학습 인증 challenge의 근거다. */
	boolean reauthenticatedSince(long sessionId, LocalDateTime since, String... methods) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM session_step_ups g JOIN user_sessions s ON s.session_id = g.session_id
				 WHERE g.session_id = :session AND g.step_up_purpose = 'RECENT_AUTH' AND s.revoked_at IS NULL
				   AND g.granted_method IN (:methods) AND g.granted_at >= :since AND g.expires_at > CURRENT_TIMESTAMP(3)
				""")
			.param("session", sessionId)
			.param("since", since)
			.param("methods", List.of(methods))
			.query(Integer.class)
			.single() > 0;
	}

	/** 이 사용자의 어느 session이든 {@code window} 안에 TOTP로 재인증했는지. 같은 TOTP 코드를 다시 쓰지 못하게 한다. */
	public boolean totpUsedWithin(long userId, Duration window) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM session_step_ups g JOIN user_sessions s ON s.session_id = g.session_id
				 WHERE s.user_id = :user AND g.granted_method = 'TOTP'
				   AND g.granted_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :seconds SECOND)
				""").param("user", userId).param("seconds", window.toSeconds()).query(Integer.class).single() > 0;
	}

	/**
	 * 학습 인증 grant의 유효기한. grant 만료와 로그인 session의 절대 만료 중 이른 쪽이다.
	 * @param sessionLifetime 로그인 session의 절대 만료 시간(astra.session.absolute-timeout)
	 */
	Optional<LocalDateTime> learningGrant(long sessionId, Duration sessionLifetime) {
		return this.jdbc.sql("""
				SELECT LEAST(g.expires_at, TIMESTAMPADD(SECOND, :lifetime, s.authenticated_at))
				  FROM session_step_ups g JOIN user_sessions s ON s.session_id = g.session_id
				 WHERE g.session_id = :session AND g.step_up_purpose = 'LEARNING_AUTH' AND s.revoked_at IS NULL
				""")
			.param("session", sessionId)
			.param("lifetime", sessionLifetime.toSeconds())
			.query(LocalDateTime.class)
			.optional();
	}

	/** grant의 만료를 지금으로 당긴다. 적립은 이 시각에서 끊긴다. 이미 지났으면 그대로 둔다. */
	int revokeLearningGrant(long sessionId) {
		return this.jdbc.sql("""
				UPDATE session_step_ups SET expires_at = CURRENT_TIMESTAMP(3)
				 WHERE session_id = :session AND step_up_purpose = 'LEARNING_AUTH' AND expires_at > CURRENT_TIMESTAMP(3)
				""").param("session", sessionId).update();
	}

}
