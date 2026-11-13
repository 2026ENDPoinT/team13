package com.stackcorp.astra.identity.account;

import java.time.Duration;
import java.util.List;
import java.util.function.ToLongFunction;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.account.AccountSessions.DeviceRevocation;
import com.stackcorp.astra.identity.account.AccountSessions.DeviceView;
import com.stackcorp.astra.identity.account.AccountSessions.LoginView;
import com.stackcorp.astra.identity.account.AccountSessions.SessionView;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.RecentAuthentication;
import com.stackcorp.astra.identity.session.CsrfCookie;
import com.stackcorp.astra.identity.session.SessionProperties;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 접속이력(EP-0267, EP-0268, 화면 11의 등록 기기·로그인 기록)과 로그인 session 관리. 모든 경로는 요청한 사용자의 것만 다룬다.
 * 다른 사람의 session이나 기기 ID면 존재와 관계없이 404다(IDENTITY-07).
 * <ul>
 * <li>session 하나 폐기는 지금 session에는 쓰지 않는다(로그아웃을 쓴다). 나머지 전부 폐기는 최근 재인증을 요구하고 지금 session은
 * 남긴다(ADR-0029).</li>
 * <li>기기 폐기는 기기와 그 기기의 session을 함께 폐기한다. 지금 기기면 지금 session도 끝나고 session 쿠키를 지운다.</li>
 * <li>폐기한 session은 다음 요청부터 인증되지 않는다. session 조회가 폐기 시각을 보기 때문이다.</li>
 * </ul>
 */
@RestController
class SessionsController {

	static final Duration REVOKE_OTHERS_STEP_UP = Duration.ofMinutes(10);

	static final int DEFAULT_PAGE = 20;

	static final int MAX_PAGE = 100;

	private final AccountSessions sessions;

	private final RecentAuthentication recentAuthentication;

	private final SessionProperties properties;

	private final RateLimiter rateLimiter;

	private final AuditLog audit;

	SessionsController(AccountSessions sessions, RecentAuthentication recentAuthentication,
			SessionProperties properties, RateLimiter rateLimiter, AuditLog audit) {
		this.sessions = sessions;
		this.recentAuthentication = recentAuthentication;
		this.properties = properties;
		this.rateLimiter = rateLimiter;
		this.audit = audit;
	}

	/** 지금 쓸 수 있는 내 로그인 session. */
	@GetMapping("/api/me/sessions")
	ResponseEntity<Sessions> sessions(@AuthenticationPrincipal Actor actor) {
		return ok(new Sessions(this.sessions.activeSessions(actor.userId(), actor.sessionId())));
	}

	/** 다른 session 하나를 끝낸다. 204. 지금 session이면 409 {@code current_session}. */
	@PostMapping("/api/me/sessions/{sessionId}/revoke")
	ResponseEntity<Void> revoke(@AuthenticationPrincipal Actor actor, @PathVariable long sessionId) {
		if (sessionId == actor.sessionId()) {
			throw new ApiException(HttpStatus.CONFLICT, "current_session", "지금 쓰는 session은 로그아웃으로 끝내 주세요.");
		}
		this.rateLimiter.check("session-revoke", actor.userId(), 30, Duration.ofMinutes(10));
		if (!this.sessions.revoke(actor.userId(), sessionId)) {
			throw ApiException.notFound();
		}
		this.audit.success(actor.userId(), actor.sessionId(), "account.session_revoke", "user_session", sessionId, null);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** 지금 session 말고 모두 끝낸다. 최근 재인증이 없으면 403 {@code step_up_required}. */
	@PostMapping("/api/me/sessions/revoke-others")
	ResponseEntity<Revoked> revokeOthers(@AuthenticationPrincipal Actor actor) {
		this.recentAuthentication.requireRecentAuth(actor, REVOKE_OTHERS_STEP_UP);
		this.rateLimiter.check("session-revoke", actor.userId(), 30, Duration.ofMinutes(10));
		int revoked = this.sessions.revokeOthers(actor.userId(), actor.sessionId());
		this.audit.success(actor.userId(), actor.sessionId(), "account.sessions_revoke_others", "user_session",
				actor.sessionId(), null);
		return ok(new Revoked(revoked));
	}

	/** 등록된 기기(화면 11 "등록된 기기", DYN-03). {@code nextCursor}로 다음 쪽. */
	@GetMapping("/api/me/devices")
	ResponseEntity<Page<DeviceView>> devices(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		int limit = size(size);
		List<DeviceView> rows = this.sessions.devices(actor.userId(), actor.sessionId(), cursor(cursor), limit + 1);
		return ok(Page.of(rows, limit, DeviceView::cursorId));
	}

	/** 기기 폐기(EP-0267). 기기의 session도 끝난다. 지금 기기면 로그아웃처럼 session 쿠키를 지우고 CSRF token을 새로 준다. */
	@DeleteMapping("/api/me/devices/{deviceId}")
	ResponseEntity<DeviceRevoked> revokeDevice(@AuthenticationPrincipal Actor actor, @PathVariable String deviceId,
			HttpServletRequest request) {
		this.rateLimiter.check("device-revoke", actor.userId(), 30, Duration.ofMinutes(10));
		DeviceRevocation revocation = this.sessions.revokeDevice(actor.userId(), deviceId, actor.sessionId())
			.orElseThrow(ApiException::notFound);
		this.audit.success(actor.userId(), actor.sessionId(), "account.device_revoke", "user_device",
				revocation.deviceId(), null);
		ResponseEntity.BodyBuilder response = ResponseEntity.ok().cacheControl(CacheControl.noStore());
		if (revocation.currentSessionRevoked()) {
			response.header(HttpHeaders.SET_COOKIE, ResponseCookie.from(this.properties.cookieName(), "")
				.httpOnly(true)
				.secure(this.properties.secureCookie())
				.sameSite("Lax")
				.path("/")
				.maxAge(Duration.ZERO)
				.build()
				.toString(), CsrfCookie.renew(request).toString());
		}
		return response.body(new DeviceRevoked(revocation.revokedSessions(), revocation.currentSessionRevoked()));
	}

	/** 최근 7일 로그인 기록(화면 11 "로그인 기록", DYN-04). 최신순, {@code nextCursor}로 다음 쪽. */
	@GetMapping("/api/me/login-events")
	ResponseEntity<Page<LoginView>> loginEvents(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) String cursor, @RequestParam(required = false) Integer size) {
		int limit = size(size);
		List<LoginView> rows = this.sessions.loginEvents(actor.userId(), cursor(cursor), limit + 1);
		return ok(Page.of(rows, limit, LoginView::cursorId));
	}

	private static int size(Integer size) {
		if (size == null) {
			return DEFAULT_PAGE;
		}
		if (size < 1 || size > MAX_PAGE) {
			throw ApiException.invalid("size는 1 이상 100 이하여야 합니다.");
		}
		return size;
	}

	private static Long cursor(String cursor) {
		if (cursor == null) {
			return null;
		}
		if (!cursor.matches("[1-9][0-9]{0,17}")) {
			throw ApiException.invalid("cursor 형식이 올바르지 않습니다.");
		}
		return Long.valueOf(cursor);
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	record Sessions(List<SessionView> sessions) {
	}

	record Revoked(int revokedSessions) {
	}

	/**
	 * @param currentSessionRevoked 지금 session도 끝났다. 다음 요청은 로그인이 필요하다
	 */
	record DeviceRevoked(int revokedSessions, boolean currentSessionRevoked) {
	}

	/**
	 * @param nextCursor 다음 쪽을 받을 때 보낼 값. 마지막 쪽이면 null
	 */
	record Page<T>(List<T> items, String nextCursor) {

		static <T> Page<T> of(List<T> rows, int limit, ToLongFunction<T> cursor) {
			if (rows.size() <= limit) {
				return new Page<>(rows, null);
			}
			List<T> items = rows.subList(0, limit);
			return new Page<>(List.copyOf(items), Long.toString(cursor.applyAsLong(items.getLast())));
		}

	}

}
