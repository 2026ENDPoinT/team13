package com.stackcorp.astra.identity.breakglass;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.stepup.StepUpProperties;
import com.stackcorp.astra.identity.stepup.StepUps;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;

import jakarta.servlet.http.HttpServletRequest;

/**
 * break-glass session의 재인증(ADR-0029). 이 계정은 학교 IdP를 쓰지 못하므로 TOTP를 다시 입력한다.
 * <ul>
 * <li>로그인과 같은 잠금을 쓴다. 실패는 login_events에 남기고 15분 안에 5번이면 잠근다(IDENTITY-06).</li>
 * <li>같은 코드를 다시 쓰지 못하게 로그인이나 TOTP 재인증에 성공한 뒤 90초 동안은 받지 않는다.</li>
 * <li>break-glass가 아닌 session은 404다.</li>
 * </ul>
 */
@RestController
class BreakGlassStepUp {

	static final String AUDIT_OPERATION = "auth.step_up_totp";

	private final BreakGlassProperties properties;

	private final LoginEvents events;

	private final StepUps stepUps;

	private final StepUpProperties stepUpProperties;

	private final RateLimiter rateLimiter;

	private final AuditLog audit;

	BreakGlassStepUp(BreakGlassProperties properties, LoginEvents events, StepUps stepUps,
			StepUpProperties stepUpProperties, RateLimiter rateLimiter, AuditLog audit) {
		this.properties = properties;
		this.events = events;
		this.stepUps = stepUps;
		this.stepUpProperties = stepUpProperties;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
	}

	/** 204. 틀리면 400 {@code verification_failed}, 잠겼으면 429 {@code locked}. */
	@PostMapping("/api/me/step-up/totp")
	ResponseEntity<Void> stepUp(@AuthenticationPrincipal Actor actor, @RequestBody TotpStepUp body,
			HttpServletRequest request) {
		if (!actor.breakGlass() || !this.properties.enabled()) {
			throw ApiException.notFound();
		}
		this.rateLimiter.check("step-up-totp", actor.userId(), 20, Duration.ofMinutes(15));
		String ip = request.getRemoteAddr();
		if (this.events.failuresWithin(actor.userId(), BreakGlassLogin.LOCK_WINDOW) >= BreakGlassLogin.MAX_FAILURES) {
			reject(actor, ip, "STEP_UP_LOCKED", "locked");
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "locked", "잠시 뒤 다시 시도해 주세요.");
		}
		if (!Totp.verify(this.properties.totpSecret(), body.code(), Instant.now())) {
			reject(actor, ip, "STEP_UP_BAD_TOTP", "rejected");
			throw rejected();
		}
		if (this.events.succeededWithin(actor.userId(), BreakGlassLogin.CODE_REUSE_WINDOW)
				|| this.stepUps.totpUsedWithin(actor.userId(), BreakGlassLogin.CODE_REUSE_WINDOW)) {
			reject(actor, ip, "STEP_UP_TOTP_REUSE_WINDOW", "rejected");
			throw rejected();
		}
		this.stepUps.grant(actor.sessionId(), StepUps.RECENT_AUTH, StepUps.METHOD_TOTP,
				this.stepUpProperties.recentAuth());
		this.audit.success(actor.userId(), actor.sessionId(), AUDIT_OPERATION, "user_session", actor.sessionId(), null);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private void reject(Actor actor, String ip, String reason, String code) {
		this.events.record(actor.userId(), actor.sessionId(), ip, false, reason);
		this.audit.record(actor.userId(), actor.sessionId(), AUDIT_OPERATION, "user_session", actor.sessionId(), null,
				code);
	}

	private static ApiException rejected() {
		return new ApiException(HttpStatus.BAD_REQUEST, "verification_failed", "인증 코드를 확인해 주세요.");
	}

	/** @param code TOTP 6자리 */
	record TotpStepUp(String code) {

		@Override
		public String toString() {
			return "TotpStepUp[]";
		}

	}

}
