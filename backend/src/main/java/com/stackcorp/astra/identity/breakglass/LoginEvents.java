package com.stackcorp.astra.identity.breakglass;

import java.time.Duration;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * login_events. 잠금과 TOTP 재사용 판정에도 쓴다. 학교 IdP 로그인(identity.sso)도 여기에 기록한다.
 */
@Repository
public class LoginEvents {

	private final JdbcClient jdbc;

	LoginEvents(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void record(long userId, Long sessionId, String ip, boolean success, String failureReason) {
		this.jdbc.sql("""
				INSERT INTO login_events (user_id, session_id, ip_address, client_environment, login_result, failure_reason)
				VALUES (:user, :session, :ip, 'PC', :result, :reason)
				""")
			.param("user", userId)
			.param("session", sessionId)
			.param("ip", ip)
			.param("result", success ? "SUCCESS" : "FAILURE")
			.param("reason", failureReason)
			.update();
	}

	int failuresWithin(long userId, Duration window) {
		return count(userId, "FAILURE", window);
	}

	boolean succeededWithin(long userId, Duration window) {
		return count(userId, "SUCCESS", window) > 0;
	}

	private int count(long userId, String result, Duration window) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM login_events
				 WHERE user_id = :user AND login_result = :result
				   AND logged_in_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :seconds SECOND)
				""")
			.param("user", userId)
			.param("result", result)
			.param("seconds", window.toSeconds())
			.query(Integer.class)
			.single();
	}

}
