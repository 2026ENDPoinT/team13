package com.stackcorp.astra.identity.sso;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.ExternalIdentityProvider;
import com.stackcorp.astra.identity.api.ExternalIdentityProvider.ExternalLogin;
import com.stackcorp.astra.identity.api.ExternalIdentityProvider.LoginRedirect;
import com.stackcorp.astra.identity.api.ExternalLoginException;
import com.stackcorp.astra.identity.session.ClientAddresses;
import com.stackcorp.astra.identity.session.CsrfCookie;
import com.stackcorp.astra.identity.session.DeviceCookie;
import com.stackcorp.astra.identity.session.SessionProperties;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimitedException;
import com.stackcorp.astra.shared.RateLimiter;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 학교 IdP 로그인(ADR-0004, ADR-0017). 원본의 로그인 화면 진입(EP-0195)을 대체한다. IdP가 설정되지 않았으면 두 경로 모두
 * 404다.
 * <ul>
 * <li>로그인 시작은 adapter가 만든 binding을 로그인 전용 쿠키(HttpOnly, SameSite=Lax, callback 경로 한정, 10분)에 넣고 IdP로
 * 보낸다. 서버와 HttpSession에는 아무것도 두지 않는다.</li>
 * <li>callback은 성공과 실패 모두 로그인 전용 쿠키를 지운다. 실패는 이유와 관계없이 같은 주소로 보낸다. 이유는 로그에 code로만
 * 남긴다.</li>
 * <li>IdP token, code, state는 응답과 로그에 넣지 않는다(SC-07).</li>
 * <li>재인증(ADR-0029)은 로그인한 사용자가 시작하고 같은 callback으로 돌아온다. 새 session을 만들지 않고 지금 session에 재인증을
 * 기록한 뒤 시작할 때의 경로로 보낸다. 실패하면 같은 경로에 {@code reauth=failed}를 붙여 보낸다. session이 없거나, IdP나
 * 재인증 수단이 없거나, 요청 제한에 걸려 시작하지 못할 때도 같다. 경로는 로그인과 같은 {@link ReturnPath} 검사를 거친다. ID
 * token 검증 단계의 실패는 재인증인지 알기 전이라 {@code /?login=failed}다.</li>
 * </ul>
 */
@RestController
class SsoController {

	static final String LOGIN_COOKIE = "ASTRA_LOGIN";

	static final String CALLBACK_PATH = "/api/auth/callback";

	/** 실패하면 보내는 주소. 화면은 이 값으로 일반적인 실패 안내만 보여 준다. */
	static final String FAILURE_LOCATION = "/?login=failed";

	/** 재인증에 실패하면 돌아갈 경로에 붙이는 query 이름과 값. */
	static final String REAUTH_MARKER = "reauth";

	static final String REAUTH_FAILED = "failed";

	static final Duration LOGIN_COOKIE_AGE = Duration.ofMinutes(10);

	private static final Logger log = LoggerFactory.getLogger(SsoController.class);

	private final ObjectProvider<ExternalIdentityProvider> providers;

	private final SsoLogin login;

	private final SsoReauthentication reauthentication;

	private final SessionProperties properties;

	private final RateLimiter rateLimiter;

	private final ClientAddresses clientAddresses;

	SsoController(ObjectProvider<ExternalIdentityProvider> providers, SsoLogin login,
			SsoReauthentication reauthentication, SessionProperties properties, RateLimiter rateLimiter,
			ClientAddresses clientAddresses) {
		this.providers = providers;
		this.login = login;
		this.reauthentication = reauthentication;
		this.properties = properties;
		this.rateLimiter = rateLimiter;
		this.clientAddresses = clientAddresses;
	}

	/** 학교 IdP 로그인을 시작한다. {@code returnTo}가 같은 origin의 상대 경로가 아니면 {@code /}로 돌아온다. */
	@GetMapping("/api/auth/login")
	ResponseEntity<Void> login(HttpServletRequest request) {
		ExternalIdentityProvider provider = provider();
		String[] returnTo = request.getParameterValues("returnTo");
		String target = ReturnPath.orRoot(returnTo != null && returnTo.length == 1 ? returnTo[0] : null);
		try {
			LoginRedirect redirect = provider.begin(target);
			return ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, redirect.location().toString())
				.header(HttpHeaders.SET_COOKIE, loginCookie(redirect.binding(), LOGIN_COOKIE_AGE).toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.build();
		}
		catch (ExternalLoginException ex) {
			log.warn("SSO login start failed: reason={}", ex.reason());
			return failure(FAILURE_LOCATION);
		}
	}

	/**
	 * 로그인한 사용자의 재인증을 시작한다(ADR-0029). IdP에서 다시 로그인한 뒤 {@code returnTo}로 돌아온다.
	 * <p>
	 * 브라우저의 top-level 이동이므로 시작할 수 없을 때도 problem JSON 대신 검사한 {@code returnTo}에 {@code reauth=failed}를
	 * 붙여 302로 보낸다. session이 없거나 만료됐을 때, IdP 설정이 없거나 break-glass session일 때, 요청 제한에 걸렸을 때다.
	 * 이때는 session과 로그인 전용 쿠키를 새로 만들거나 지우지 않는다. 요청 제한은 로그인한 사용자의 시도를 그대로 센다.
	 */
	@GetMapping("/api/auth/reauth")
	ResponseEntity<Void> reauth(@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
		String[] returnTo = request.getParameterValues("returnTo");
		String target = ReturnPath.orRoot(returnTo != null && returnTo.length == 1 ? returnTo[0] : null);
		String failed = ReturnPath.marked(target, REAUTH_MARKER, REAUTH_FAILED);
		if (actor == null) {
			log.info("SSO reauthentication not started: reason=no_session");
			return redirect(failed);
		}
		ExternalIdentityProvider provider = this.providers.getIfAvailable();
		if (provider == null || actor.breakGlass()) {
			log.info("SSO reauthentication not started: reason={}", provider == null ? "no_idp" : "break_glass");
			return redirect(failed);
		}
		try {
			this.rateLimiter.check("step-up-reauth", actor.userId(), 10, Duration.ofMinutes(10));
		}
		catch (RateLimitedException ex) {
			log.info("SSO reauthentication not started: reason=rate_limited");
			return redirect(failed);
		}
		try {
			LoginRedirect redirect = provider.beginReauthentication(target);
			return ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, redirect.location().toString())
				.header(HttpHeaders.SET_COOKIE, loginCookie(redirect.binding(), LOGIN_COOKIE_AGE).toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.build();
		}
		catch (ExternalLoginException ex) {
			log.warn("SSO reauthentication start failed: reason={}", ex.reason());
			return failure(failed);
		}
	}

	/**
	 * IdP가 브라우저를 돌려보내는 주소. 로그인이면 검증 뒤 session 쿠키를 주고, 재인증이면 지금 session에 기록한 뒤 시작할 때의 경로로
	 * 보낸다.
	 */
	@GetMapping(CALLBACK_PATH)
	ResponseEntity<Void> callback(@AuthenticationPrincipal Actor actor, HttpServletRequest request) {
		ExternalIdentityProvider provider = provider();
		ExternalLogin external;
		try {
			external = provider.complete(binding(request), parameters(request));
		}
		catch (ExternalLoginException ex) {
			log.warn("SSO login rejected: reason={}", ex.reason());
			return failure(FAILURE_LOCATION);
		}
		if (external.reauthentication()) {
			try {
				this.reauthentication.record(actor, external.externalRef());
				return redirect(ReturnPath.orRoot(external.returnTo()), loginCookie("", Duration.ZERO).toString());
			}
			catch (ExternalLoginException ex) {
				log.warn("SSO reauthentication rejected: reason={}", ex.reason());
				return failure(ReturnPath.marked(external.returnTo(), REAUTH_MARKER, REAUTH_FAILED));
			}
		}
		try {
			IssuedSession session = this.login.signIn(external.externalRef(), DeviceCookie.read(request),
					this.clientAddresses.of(request), request.getHeader(HttpHeaders.USER_AGENT));
			return redirect(ReturnPath.orRoot(external.returnTo()), sessionCookie(session.token()).toString(),
					DeviceCookie.issue(session.deviceUuid(), this.properties).toString(),
					loginCookie("", Duration.ZERO).toString(), CsrfCookie.renew(request).toString());
		}
		catch (ExternalLoginException ex) {
			log.warn("SSO login rejected: reason={}", ex.reason());
			return failure(FAILURE_LOCATION);
		}
	}

	private static ResponseEntity<Void> redirect(String location, String... cookies) {
		return ResponseEntity.status(HttpStatus.FOUND)
			.header(HttpHeaders.LOCATION, location)
			.header(HttpHeaders.SET_COOKIE, cookies)
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.header("Referrer-Policy", "no-referrer")
			.build();
	}

	private ExternalIdentityProvider provider() {
		ExternalIdentityProvider provider = this.providers.getIfAvailable();
		if (provider == null) {
			throw ApiException.notFound();
		}
		return provider;
	}

	private ResponseEntity<Void> failure(String location) {
		return ResponseEntity.status(HttpStatus.FOUND)
			.header(HttpHeaders.LOCATION, location)
			.header(HttpHeaders.SET_COOKIE, loginCookie("", Duration.ZERO).toString())
			.header(HttpHeaders.CACHE_CONTROL, "no-store")
			.header("Referrer-Policy", "no-referrer")
			.build();
	}

	// 같은 이름의 쿠키가 둘 이상이면(다른 subdomain이 심은 값 등) 어느 것도 믿지 않는다.
	private static String binding(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		String value = null;
		for (Cookie cookie : cookies == null ? new Cookie[0] : cookies) {
			if (LOGIN_COOKIE.equals(cookie.getName())) {
				if (value != null) {
					throw new ExternalLoginException("binding_duplicate");
				}
				value = cookie.getValue();
			}
		}
		return value == null || value.isEmpty() ? null : value;
	}

	private static Map<String, String> parameters(HttpServletRequest request) {
		Map<String, String> parameters = new HashMap<>();
		request.getParameterMap().forEach((name, values) -> {
			if (values.length != 1) {
				throw new ExternalLoginException("duplicate_parameter");
			}
			parameters.put(name, values[0]);
		});
		return parameters;
	}

	private ResponseCookie loginCookie(String value, Duration maxAge) {
		return ResponseCookie.from(LOGIN_COOKIE, value)
			.httpOnly(true)
			.secure(this.properties.secureCookie())
			.sameSite("Lax")
			.path(CALLBACK_PATH)
			.maxAge(maxAge)
			.build();
	}

	private ResponseCookie sessionCookie(String token) {
		return ResponseCookie.from(this.properties.cookieName(), token)
			.httpOnly(true)
			.secure(this.properties.secureCookie())
			.sameSite("Lax")
			.path("/")
			.maxAge(this.properties.absoluteTimeout())
			.build();
	}

}
