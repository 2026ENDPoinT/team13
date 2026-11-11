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

	/**
	 * 잠금·재사용 판정 전에 계정의 users 행을 잠근다. 로그인과 TOTP 재인증이 같은 행을 같은 순서로 잠그므로 판정과 기록이 계정마다 차례로
	 * 일어난다. transaction 안에서 부른다.
	 */
	void lockAccount(long userId) {
		this.jdbc.sql("SELECT user_id FROM users WHERE user_id = :user FOR UPDATE")
			.param("user", userId)
			.query(Long.class)
			.single();
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
