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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.ExternalIdentityProvider;
import com.stackcorp.astra.identity.api.ExternalIdentityProvider.ExternalLogin;
import com.stackcorp.astra.identity.api.ExternalIdentityProvider.LoginRedirect;
import com.stackcorp.astra.identity.api.ExternalLoginException;
import com.stackcorp.astra.identity.session.SessionProperties;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;
import com.stackcorp.astra.shared.ApiException;

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
 * </ul>
 */
@RestController
class SsoController {

	static final String LOGIN_COOKIE = "ASTRA_LOGIN";

	static final String CALLBACK_PATH = "/api/auth/callback";

	/** 실패하면 보내는 주소. 화면은 이 값으로 일반적인 실패 안내만 보여 준다. */
	static final String FAILURE_LOCATION = "/?login=failed";

	static final Duration LOGIN_COOKIE_AGE = Duration.ofMinutes(10);

	private static final Logger log = LoggerFactory.getLogger(SsoController.class);

	private final ObjectProvider<ExternalIdentityProvider> providers;

	private final SsoLogin login;

	private final SessionProperties properties;

	SsoController(ObjectProvider<ExternalIdentityProvider> providers, SsoLogin login, SessionProperties properties) {
		this.providers = providers;
		this.login = login;
		this.properties = properties;
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
			return failure();
		}
	}

	/** IdP가 브라우저를 돌려보내는 주소. 검증에 성공하면 session 쿠키를 주고 시작할 때의 경로로 보낸다. */
	@GetMapping(CALLBACK_PATH)
	ResponseEntity<Void> callback(HttpServletRequest request) {
		ExternalIdentityProvider provider = provider();
		try {
			ExternalLogin external = provider.complete(binding(request), parameters(request));
			IssuedSession session = this.login.signIn(external.externalRef(), request.getRemoteAddr(),
					request.getHeader(HttpHeaders.USER_AGENT));
			return ResponseEntity.status(HttpStatus.FOUND)
				.header(HttpHeaders.LOCATION, ReturnPath.orRoot(external.returnTo()))
				.header(HttpHeaders.SET_COOKIE, sessionCookie(session.token()).toString(),
						loginCookie("", Duration.ZERO).toString())
				.header(HttpHeaders.CACHE_CONTROL, "no-store")
				.header("Referrer-Policy", "no-referrer")
				.build();
		}
		catch (ExternalLoginException ex) {
			log.warn("SSO login rejected: reason={}", ex.reason());
			return failure();
		}
	}

	private ExternalIdentityProvider provider() {
		ExternalIdentityProvider provider = this.providers.getIfAvailable();
		if (provider == null) {
			throw ApiException.notFound();
		}
		return provider;
	}

	private ResponseEntity<Void> failure() {
		return ResponseEntity.status(HttpStatus.FOUND)
			.header(HttpHeaders.LOCATION, FAILURE_LOCATION)
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
