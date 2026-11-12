package com.stackcorp.astra.identity.breakglass;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.identity.session.BreakGlassMarker;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;

/**
 * break-glass 계정의 로컬 로그인. 비밀번호와 TOTP를 모두 요구한다(ADR-0004 6번).
 * <ul>
 * <li>15분 안에 5번 실패하면 15분 동안 잠근다.</li>
 * <li>TOTP 코드 재사용을 막기 위해 성공한 로그인 뒤 90초 동안은 다시 로그인하지 않는다.</li>
 * <li>실패 이유는 응답으로 구분하지 않는다. login_events에만 남긴다.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(BreakGlassProperties.class)
class BreakGlassLogin implements BreakGlassMarker {

	static final int MAX_FAILURES = 5;

	static final Duration LOCK_WINDOW = Duration.ofMinutes(15);

	static final Duration CODE_REUSE_WINDOW = Duration.ofSeconds(Totp.STEP_SECONDS * 3);

	private final BreakGlassProperties properties;

	private final PasswordEncoder passwords;

	private final SessionStore sessions;

	private final LoginEvents events;

	private final JdbcClient jdbc;

	// 없는 계정에도 같은 비교 시간을 쓰도록 실제로 만든 hash
	private final String dummyHash;

	BreakGlassLogin(BreakGlassProperties properties, PasswordEncoder passwords, SessionStore sessions,
			LoginEvents events, JdbcClient jdbc) {
		this.properties = properties;
		this.passwords = passwords;
		this.sessions = sessions;
		this.events = events;
		this.jdbc = jdbc;
		this.dummyHash = passwords.encode(UUID.randomUUID().toString());
	}

	/**
	 * @throws BreakGlassException 로그인할 수 없을 때
	 */
	IssuedSession login(String username, String password, String code, String ip, String userAgent) {
		if (!this.properties.enabled()) {
			throw new BreakGlassException(BreakGlassException.Reason.DISABLED);
		}
		Optional<Long> userId = username != null && username.equals(this.properties.externalRef()) ? accountUserId()
				: Optional.empty();
		if (userId.isEmpty()) {
			this.passwords.matches(String.valueOf(password), this.dummyHash);
			throw new BreakGlassException(BreakGlassException.Reason.REJECTED);
		}
		long user = userId.get();
		if (this.events.failuresWithin(user, LOCK_WINDOW) >= MAX_FAILURES) {
			this.events.record(user, null, ip, false, "LOCKED");
			throw new BreakGlassException(BreakGlassException.Reason.LOCKED);
		}
		if (password == null || !this.passwords.matches(password, this.properties.passwordHash())) {
			this.events.record(user, null, ip, false, "BAD_PASSWORD");
			throw new BreakGlassException(BreakGlassException.Reason.REJECTED);
		}
		if (!Totp.verify(this.properties.totpSecret(), code, Instant.now())) {
			this.events.record(user, null, ip, false, "BAD_TOTP");
			throw new BreakGlassException(BreakGlassException.Reason.REJECTED);
		}
		if (this.events.succeededWithin(user, CODE_REUSE_WINDOW)) {
			this.events.record(user, null, ip, false, "TOTP_REUSE_WINDOW");
			throw new BreakGlassException(BreakGlassException.Reason.REJECTED);
		}
		IssuedSession session = this.sessions.create(user, ip, userAgent);
		this.events.record(user, session.sessionId(), ip, true, null);
		return session;
	}

	@Override
	public boolean isBreakGlass(long userId) {
		return this.properties.enabled() && accountUserId().filter((id) -> id == userId).isPresent();
	}

	private Optional<Long> accountUserId() {
		return this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref AND account_status = 'ACTIVE'")
			.param("ref", this.properties.externalRef())
			.query(Long.class)
			.optional();
	}

}
