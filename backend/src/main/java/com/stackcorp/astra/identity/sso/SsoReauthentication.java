package com.stackcorp.astra.identity.sso;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.ExternalLoginException;
import com.stackcorp.astra.identity.stepup.StepUpProperties;
import com.stackcorp.astra.identity.stepup.StepUps;
import com.stackcorp.astra.shared.audit.AuditLog;

/**
 * IdP 재인증 결과를 로그인 session에 기록한다(ADR-0029). 새 session을 만들지 않는다.
 * <ul>
 * <li>callback을 받은 브라우저에 유효한 session이 있어야 한다. 그 session 사용자의 {@code external_ref}와 IdP가 확인한 사용자가
 * 같아야 한다. 다른 계정으로 다시 로그인하면 재인증으로 치지 않는다.</li>
 * <li>break-glass session은 IdP로 재인증하지 못한다. TOTP로만 한다.</li>
 * </ul>
 */
@Service
class SsoReauthentication {

	static final String AUDIT_OPERATION = "auth.step_up";

	private final JdbcClient jdbc;

	private final StepUps stepUps;

	private final StepUpProperties properties;

	private final AuditLog audit;

	SsoReauthentication(JdbcClient jdbc, StepUps stepUps, StepUpProperties properties, AuditLog audit) {
		this.jdbc = jdbc;
		this.stepUps = stepUps;
		this.properties = properties;
		this.audit = audit;
	}

	/**
	 * @param actor callback 요청의 session. 없으면 null
	 * @throws ExternalLoginException 재인증으로 받을 수 없을 때
	 */
	void record(Actor actor, String externalRef) {
		if (actor == null) {
			throw new ExternalLoginException("reauth_no_session");
		}
		if (actor.breakGlass()) {
			reject(actor, "break_glass");
		}
		String sessionRef = this.jdbc.sql("SELECT external_ref FROM users WHERE user_id = :id")
			.param("id", actor.userId())
			.query(String.class)
			.single();
		if (!sessionRef.equals(externalRef)) {
			reject(actor, "user_mismatch");
		}
		this.stepUps.grant(actor.sessionId(), StepUps.RECENT_AUTH, StepUps.METHOD_SSO, this.properties.recentAuth());
		this.audit.success(actor.userId(), actor.sessionId(), AUDIT_OPERATION, "user_session", actor.sessionId(), null);
	}

	private void reject(Actor actor, String reason) {
		this.audit.record(actor.userId(), actor.sessionId(), AUDIT_OPERATION, "user_session", actor.sessionId(), null,
				reason);
		throw new ExternalLoginException("reauth_" + reason);
	}

}
