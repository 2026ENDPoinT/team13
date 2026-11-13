package com.stackcorp.astra.identity.session;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 다른 package의 테스트가 session 쿠키 값으로 user_sessions 행을 찾을 때 쓴다.
 */
public final class TestSessions {

	private TestSessions() {
	}

	public static long id(JdbcClient jdbc, String token) {
		return jdbc.sql("SELECT session_id FROM user_sessions WHERE session_token_hash = :hash")
			.param("hash", SessionStore.hash(token))
			.query(Long.class)
			.single();
	}

}
