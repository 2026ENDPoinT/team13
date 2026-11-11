package com.stackcorp.astra.identity.sso;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stackcorp.astra.identity.api.ExternalLoginException;
import com.stackcorp.astra.identity.breakglass.BreakGlassProperties;
import com.stackcorp.astra.identity.breakglass.LoginEvents;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;

/**
 * IdP가 확인한 식별자로 내부 사용자를 찾아 session을 만든다(ADR-0004 3번, ADR-0017).
 * <ul>
 * <li>{@code users.external_ref}가 같은 ACTIVE 사용자만 로그인한다. 없는 사용자를 만들지 않는다.</li>
 * <li>break-glass 계정은 IdP로 로그인하지 못한다. 그 계정은 비밀번호와 TOTP로만 들어온다(ADR-0004 6번).</li>
 * <li>로그인마다 새 opaque token을 만든다. 브라우저가 가진 값을 session으로 받아 주는 길이 없어 session 고정이 생기지 않는다.</li>
 * </ul>
 */
@Service
class SsoLogin {

	private final JdbcClient jdbc;

	private final SessionStore sessions;

	private final LoginEvents events;

	private final BreakGlassProperties breakGlass;

	SsoLogin(JdbcClient jdbc, SessionStore sessions, LoginEvents events, BreakGlassProperties breakGlass) {
		this.jdbc = jdbc;
		this.sessions = sessions;
		this.events = events;
		this.breakGlass = breakGlass;
	}

	/**
	 * 실패를 login_events에 남긴 채로 예외를 던지므로 그 예외로는 rollback하지 않는다.
	 * @throws ExternalLoginException 로그인할 수 없는 사용자일 때
	 */
	@Transactional(noRollbackFor = ExternalLoginException.class)
	IssuedSession signIn(String externalRef, String ip, String userAgent) {
		Account account = this.jdbc.sql("SELECT user_id, account_status FROM users WHERE external_ref = :ref")
			.param("ref", externalRef)
			.query((rs, n) -> new Account(rs.getLong("user_id"), rs.getString("account_status")))
			.optional()
			.orElseThrow(() -> new ExternalLoginException("unknown_user"));
		if (externalRef.equals(this.breakGlass.externalRef())) {
			this.events.record(account.userId(), null, ip, false, "SSO_BREAK_GLASS");
			throw new ExternalLoginException("break_glass_account");
		}
		if (!"ACTIVE".equals(account.status())) {
			this.events.record(account.userId(), null, ip, false, "SSO_INACTIVE");
			throw new ExternalLoginException("inactive_account");
		}
		IssuedSession session = this.sessions.create(account.userId(), ip, userAgent);
		this.events.record(account.userId(), session.sessionId(), ip, true, null);
		return session;
	}

	private record Account(long userId, String status) {
	}

}
