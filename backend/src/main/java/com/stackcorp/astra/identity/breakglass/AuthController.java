package com.stackcorp.astra.identity.breakglass;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.ExternalIdentityProvider;
import com.stackcorp.astra.identity.session.ClientAddresses;
import com.stackcorp.astra.identity.session.DeviceCookie;
import com.stackcorp.astra.identity.session.DevLoginMarker;
import com.stackcorp.astra.identity.session.SessionProperties;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 로그인, 로그아웃, 현재 사용자. 학교 IdP 로그인({@code identity.sso})도 같은 session을 만든다(ADR-0004 1번, ADR-0017).
 */
@RestController
class AuthController {

	/**
	 * break-glass 로그인의 브라우저 주소 단위 한도. 계정 잠금(15분에 5번 실패)은 그대로이고, 이 한도는 한 주소가 비밀번호 hash 비교를
	 * 끝없이 시키지 못하게 한다. 관리자가 학교 NAT 뒤에서 몇 번 틀려도 닿지 않을 만큼 넉넉하다.
	 */
	static final int ADDRESS_LIMIT = 30;

	static final Duration ADDRESS_WINDOW = Duration.ofMinutes(15);

	private final BreakGlassLogin breakGlass;

	private final SessionStore sessions;

	private final SessionProperties properties;

	private final AuditLog audit;

	private final ClientAddresses clientAddresses;

	private final RateLimiter rateLimiter;

	private final BreakGlassProperties breakGlassProperties;

	private final ObjectProvider<ExternalIdentityProvider> providers;

	private final ObjectProvider<DevLoginMarker> devLogin;

	AuthController(BreakGlassLogin breakGlass, SessionStore sessions, SessionProperties properties, AuditLog audit,
			ClientAddresses clientAddresses, RateLimiter rateLimiter, BreakGlassProperties breakGlassProperties,
			ObjectProvider<ExternalIdentityProvider> providers, ObjectProvider<DevLoginMarker> devLogin) {
		this.breakGlass = breakGlass;
		this.sessions = sessions;
		this.properties = properties;
		this.audit = audit;
		this.clientAddresses = clientAddresses;
		this.rateLimiter = rateLimiter;
		this.breakGlassProperties = breakGlassProperties;
		this.providers = providers;
		this.devLogin = devLogin;
	}

	/** CSRF 쿠키(XSRF-TOKEN)를 받는다. 상태를 바꾸는 요청은 이 값을 X-XSRF-TOKEN header로 보낸다. */
	@GetMapping("/api/auth/csrf")
	ResponseEntity<Void> csrf(CsrfToken token) {
		token.getToken();
		return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
	}

	/** 한도를 넘은 주소는 계정과 관계없이 429 {@code rate_limited}다. */
	@PostMapping("/api/auth/break-glass")
	ResponseEntity<Void> breakGlass(@RequestBody BreakGlassRequest body, HttpServletRequest request) {
		String address = this.clientAddresses.of(request);
		this.rateLimiter.checkAddress("break-glass-login", address, ADDRESS_LIMIT, ADDRESS_WINDOW);
		IssuedSession session = this.breakGlass.login(body.username(), body.password(), body.code(),
				DeviceCookie.read(request), address, request.getHeader(HttpHeaders.USER_AGENT));
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, cookie(session.token(), this.properties.absoluteTimeout()).toString(),
					DeviceCookie.issue(session.deviceUuid(), this.properties).toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.build();
	}

	@PostMapping("/api/auth/logout")
	ResponseEntity<Void> logout(@AuthenticationPrincipal Actor actor) {
		this.sessions.revoke(actor.sessionId(), "LOGOUT");
		this.audit.success(actor.userId(), actor.sessionId(), "auth.logout", "user_session", actor.sessionId(), null);
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO).toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.build();
	}

	/**
	 * 현재 사용자와 이 session이 쓸 수 있는 재인증 수단(ADR-0029). 수단은 session을 만든 계정의 종류와 이 배포에 있는 로그인 수단으로
	 * 정하고, {@code GET /api/auth/methods}가 이미 알리는 것 밖의 설정은 드러내지 않는다.
	 */
	@GetMapping("/api/me")
	ResponseEntity<Me> me(@AuthenticationPrincipal Actor actor) {
		return ResponseEntity.ok()
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(new Me(actor.userId(), actor.name(), actor.role().name(), actor.breakGlass(), stepUpMethod(actor)));
	}

	/**
	 * break-glass 계정은 TOTP뿐이다(IdP 재인증은 받지 않는다). 그 밖의 계정은 학교 IdP 재인증이고, IdP가 없는 로컬 실행에서는 개발용
	 * 재인증이 IdP를 대신한다(ADR-0029 7번). 쓸 수 있는 수단이 없으면 null이다.
	 */
	private String stepUpMethod(Actor actor) {
		if (actor.breakGlass()) {
			return this.breakGlassProperties.enabled() ? "totp" : null;
		}
		if (this.providers.getIfAvailable() != null) {
			return "idp";
		}
		return this.devLogin.getIfAvailable() != null ? "dev" : null;
	}

	// 실패는 계정을 특정하지 않고 남긴다. 계정별 실패 이유는 login_events에 있다.
	@ExceptionHandler(BreakGlassException.class)
	ProblemDetail rejected(BreakGlassException ex) {
		this.audit.record(null, null, BreakGlassLogin.AUDIT_OPERATION, null, null, null,
				ex.reason().name().toLowerCase());
		ProblemDetail problem = switch (ex.reason()) {
			case DISABLED -> ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "로컬 로그인을 쓸 수 없습니다.");
			case LOCKED -> ProblemDetail.forStatusAndDetail(HttpStatus.TOO_MANY_REQUESTS, "잠시 뒤 다시 시도해 주세요.");
			case REJECTED -> ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "로그인 정보를 확인해 주세요.");
		};
		problem.setProperty("code", ex.reason().name().toLowerCase());
		return problem;
	}

	private ResponseCookie cookie(String value, Duration maxAge) {
		return ResponseCookie.from(this.properties.cookieName(), value)
			.httpOnly(true)
			.secure(this.properties.secureCookie())
			.sameSite("Lax")
			.path("/")
			.maxAge(maxAge)
			.build();
	}

	record BreakGlassRequest(String username, String password, String code) {

		// 요청 본문을 로그에 찍는 설정에서도 비밀번호와 TOTP가 나가지 않게 한다(SC-07).
		@Override
		public String toString() {
			return "BreakGlassRequest[]";
		}

	}

	/**
	 * @param stepUpMethod 이 session의 재인증 수단. {@code idp}(GET /api/auth/reauth?returnTo=), {@code totp}(POST
	 * /api/me/step-up/totp), {@code dev}(로컬 실행의 POST /api/me/step-up/dev) 중 하나이고, 없으면 null
	 */
	record Me(long userId, String name, String role, boolean breakGlass, String stepUpMethod) {
	}

}
