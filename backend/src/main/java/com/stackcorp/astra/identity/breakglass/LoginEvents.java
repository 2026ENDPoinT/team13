package com.stackcorp.astra.identity.breakglass;

import java.time.Duration;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * login_events. 잠금과 TOTP 재사용 판정에도 쓴다. 학교 IdP 로그인(identity.sso)도 여기에 기록한다.
 */
@Repository
public class LoginEvents {

	/** 잠겨 있어 비밀번호나 코드를 보지 않고 거절한 로그인의 실패 이유 */
	static final String LOCKED = "LOCKED";

	/** 잠겨 있어 코드를 보지 않고 거절한 TOTP 재인증의 실패 이유 */
	static final String STEP_UP_LOCKED = "STEP_UP_LOCKED";

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

	/**
	 * 잠금 판정에 쓰는 실패 수. 잠긴 동안 거절한 시도({@link #LOCKED}, {@link #STEP_UP_LOCKED})는 세지 않는다. 세면 계정 이름을 아는
	 * 사람이 잠긴 동안 계속 시도해 잠금을 끝없이 늘릴 수 있다.
	 */
	int failuresWithin(long userId, Duration window) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM login_events
				 WHERE user_id = :user AND login_result = 'FAILURE'
				   AND (failure_reason IS NULL OR failure_reason NOT IN (:locked))
				   AND logged_in_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :seconds SECOND)
				""")
			.param("user", userId)
			.param("locked", List.of(LOCKED, STEP_UP_LOCKED))
			.param("seconds", window.toSeconds())
			.query(Integer.class)
			.single();
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
