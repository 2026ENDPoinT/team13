package com.stackcorp.astra.identity.session;

import java.util.UUID;

import org.springframework.http.ResponseCookie;

import jakarta.servlet.http.HttpServletRequest;

/**
 * CSRF double-submit 쿠키({@code XSRF-TOKEN}, ADR-0004 4번). 값은 브라우저가 처음 받은 뒤 바뀌지 않고, 이 브라우저의 session이 바뀌는
 * 응답(로그인, 로그아웃, 지금 기기 폐기)만 새 값을 준다. 그 밖의 응답은 쿠키가 없을 때만 {@code csrf.spa()}의
 * {@code CookieCsrfTokenRepository}가 만든다. 속성은 그 repository가 쓰는 것과 같다: path {@code /}, JavaScript가 읽도록
 * {@code HttpOnly} 없음, https 요청이면 {@code Secure}, 브라우저를 닫으면 사라지는 session 쿠키.
 */
public final class CsrfCookie {

	public static final String NAME = "XSRF-TOKEN";

	private CsrfCookie() {
	}

	/** session이 바뀌는 응답에 붙이는 새 token. 로그인 전에 심겼을 수 있는 값을 로그인 뒤에 쓰지 않게 한다. */
	public static ResponseCookie renew(HttpServletRequest request) {
		return ResponseCookie.from(NAME, UUID.randomUUID().toString())
			.httpOnly(false)
			.secure(request.isSecure())
			.path("/")
			.build();
	}

}
