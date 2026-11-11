package com.stackcorp.astra.identity.breakglass;

import java.time.Duration;

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
import com.stackcorp.astra.identity.session.SessionProperties;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;
import com.stackcorp.astra.shared.audit.AuditLog;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 로그인, 로그아웃, 현재 사용자. 학교 IdP 로그인({@code identity.sso})도 같은 session을 만든다(ADR-0004 1번, ADR-0017).
 */
@RestController
class AuthController {

	private final BreakGlassLogin breakGlass;

	private final SessionStore sessions;

	private final SessionProperties properties;

	private final AuditLog audit;

	AuthController(BreakGlassLogin breakGlass, SessionStore sessions, SessionProperties properties, AuditLog audit) {
		this.breakGlass = breakGlass;
		this.sessions = sessions;
		this.properties = properties;
		this.audit = audit;
	}

	/** CSRF 쿠키(XSRF-TOKEN)를 받는다. 상태를 바꾸는 요청은 이 값을 X-XSRF-TOKEN header로 보낸다. */
	@GetMapping("/api/auth/csrf")
	ResponseEntity<Void> csrf(CsrfToken token) {
		token.getToken();
		return ResponseEntity.noContent().header(HttpHeaders.CACHE_CONTROL, "no-store").build();
	}

	@PostMapping("/api/auth/break-glass")
	ResponseEntity<Void> breakGlass(@RequestBody BreakGlassRequest body, HttpServletRequest request) {
		IssuedSession session = this.breakGlass.login(body.username(), body.password(), body.code(),
				request.getRemoteAddr(), request.getHeader(HttpHeaders.USER_AGENT));
		return ResponseEntity.noContent()
			.header(HttpHeaders.SET_COOKIE, cookie(session.token(), this.properties.absoluteTimeout()).toString())
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

	@GetMapping("/api/me")
	ResponseEntity<Me> me(@AuthenticationPrincipal Actor actor) {
		return ResponseEntity.ok()
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.body(new Me(actor.userId(), actor.name(), actor.role().name(), actor.breakGlass()));
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
	}

	record Me(long userId, String name, String role, boolean breakGlass) {
	}

}
