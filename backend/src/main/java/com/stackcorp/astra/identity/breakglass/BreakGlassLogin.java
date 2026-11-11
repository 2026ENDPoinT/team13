package com.stackcorp.astra.identity.breakglass;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.identity.session.BreakGlassMarker;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;
import com.stackcorp.astra.identity.stepup.StepUps;
import com.stackcorp.astra.shared.audit.AuditLog;

/**
 * break-glass 계정의 로컬 로그인. 비밀번호와 TOTP를 모두 요구한다(ADR-0004 6번).
 * <ul>
 * <li>15분 안에 5번 실패하면 15분 동안 잠근다.</li>
 * <li>TOTP 코드 재사용을 막기 위해 성공한 로그인이나 TOTP 재인증(ADR-0029) 뒤 90초 동안은 다시 로그인하지 않는다.</li>
 * <li>잠금과 재사용 판정, 실패 기록, session 발급은 계정의 users 행을 잠근 READ COMMITTED transaction 하나에서 한다. TOTP
 * 재인증({@link BreakGlassStepUp})도 같은 행을 먼저 잠그므로 동시에 보낸 시도가 모두 앞선 실패 수를 보고 한도를 넘거나 같은 코드로
 * 둘 다 성공하지 못한다. 비밀번호 hash 비교와 TOTP 계산은 잠그기 전에 끝내 잠금을 짧게 쥔다.</li>
 * <li>실패 이유는 응답으로 구분하지 않는다. login_events에만 남긴다. 감사 기록(SC-09)에는 성공과 응답 코드만 남긴다.</li>
 * </ul>
 */
@Service
@EnableConfigurationProperties(BreakGlassProperties.class)
class BreakGlassLogin implements BreakGlassMarker {

	static final int MAX_FAILURES = 5;

	static final Duration LOCK_WINDOW = Duration.ofMinutes(15);

	static final Duration CODE_REUSE_WINDOW = Duration.ofSeconds(Totp.STEP_SECONDS * 3);

	static final String AUDIT_OPERATION = "auth.break_glass_login";

	private final BreakGlassProperties properties;

	private final PasswordEncoder passwords;

	private final SessionStore sessions;

	private final LoginEvents events;

	private final JdbcClient jdbc;

	private final AuditLog audit;

	private final StepUps stepUps;

	private final TransactionTemplate transactions;

	// 없는 계정에도 같은 비교 시간을 쓰도록 실제로 만든 hash
	private final String dummyHash;

	BreakGlassLogin(BreakGlassProperties properties, PasswordEncoder passwords, SessionStore sessions,
			LoginEvents events, JdbcClient jdbc, AuditLog audit, StepUps stepUps,
			PlatformTransactionManager transactionManager) {
		this.properties = properties;
		this.passwords = passwords;
		this.sessions = sessions;
		this.events = events;
		this.jdbc = jdbc;
		this.audit = audit;
		this.stepUps = stepUps;
		this.transactions = new TransactionTemplate(transactionManager);
		this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.dummyHash = passwords.encode(UUID.randomUUID().toString());
	}

	/**
	 * @param device 브라우저의 기기 쿠키 값. 없으면 null
	 * @throws BreakGlassException 로그인할 수 없을 때
	 */
	IssuedSession login(String username, String password, String code, String device, String ip, String userAgent) {
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
		String failure = check(password, code);
		// 실패도 기록해야 하므로 transaction 안에서는 예외를 던지지 않고 결과를 돌려준다.
		Attempt attempt = this.transactions.execute((status) -> {
			this.events.lockAccount(user);
			if (this.events.failuresWithin(user, LOCK_WINDOW) >= MAX_FAILURES) {
				this.events.record(user, null, ip, false, "LOCKED");
				return Attempt.refused(BreakGlassException.Reason.LOCKED);
			}
			if (failure != null) {
				this.events.record(user, null, ip, false, failure);
				return Attempt.refused(BreakGlassException.Reason.REJECTED);
			}
			if (this.events.succeededWithin(user, CODE_REUSE_WINDOW)
					|| this.stepUps.totpUsedWithin(user, CODE_REUSE_WINDOW)) {
				this.events.record(user, null, ip, false, "TOTP_REUSE_WINDOW");
				return Attempt.refused(BreakGlassException.Reason.REJECTED);
			}
			IssuedSession session = this.sessions.create(user, device, ip, userAgent);
			this.events.record(user, session.sessionId(), ip, true, null);
			this.audit.success(user, session.sessionId(), AUDIT_OPERATION, "user_session", session.sessionId(), null);
			return new Attempt(session, null);
		});
		if (attempt.session() == null) {
			throw new BreakGlassException(attempt.refusal());
		}
		return attempt.session();
	}

	@Override
	public boolean isBreakGlass(long userId) {
		return this.properties.enabled() && accountUserId().filter((id) -> id == userId).isPresent();
	}

	/** @return 실패 이유. 비밀번호와 TOTP가 맞으면 null */
	private String check(String password, String code) {
		if (password == null || !this.passwords.matches(password, this.properties.passwordHash())) {
			return "BAD_PASSWORD";
		}
		return Totp.verify(this.properties.totpSecret(), code, Instant.now()) ? null : "BAD_TOTP";
	}

	private Optional<Long> accountUserId() {
		return this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref AND account_status = 'ACTIVE'")
			.param("ref", this.properties.externalRef())
			.query(Long.class)
			.optional();
	}

	private record Attempt(IssuedSession session, BreakGlassException.Reason refusal) {

		static Attempt refused(BreakGlassException.Reason reason) {
			return new Attempt(null, reason);
		}

	}

}
