package com.stackcorp.astra.identity.session;

import java.time.Duration;

import org.springframework.http.ResponseCookie;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 브라우저를 등록 기기({@code user_devices})로 알아보는 쿠키(EP-0267, EP-0268, IDENTITY-07). 값은 그 기기의 외부 식별자
 * (UUID)이고 인증에 쓰지 않는다. 로그인할 때만 읽으므로 로그인 경로({@code /api/auth})에만 보낸다.
 * 다른 사용자의 기기이거나 폐기한 기기의 값이면 로그인이 새 기기를 만든다.
 */
public final class DeviceCookie {

	public static final String NAME = "ASTRA_DEVICE";

	/** 브라우저가 허용하는 쿠키 수명 상한(400일)에 맞춘다. */
	static final Duration MAX_AGE = Duration.ofDays(400);

	private DeviceCookie() {
	}

	/** @return UUID 형식의 값이 하나만 있으면 그 값, 아니면 null */
	public static String read(HttpServletRequest request) {
		Cookie[] cookies = request.getCookies();
		String value = null;
		for (Cookie cookie : cookies == null ? new Cookie[0] : cookies) {
			if (NAME.equals(cookie.getName())) {
				if (value != null) {
					return null;
				}
				value = cookie.getValue();
			}
		}
		return value != null && Devices.isUuid(value) ? value : null;
	}

	public static ResponseCookie issue(String deviceUuid, SessionProperties properties) {
		return ResponseCookie.from(NAME, deviceUuid)
			.httpOnly(true)
			.secure(properties.secureCookie())
			.sameSite("Lax")
			.path("/api/auth")
			.maxAge(MAX_AGE)
			.build();
	}

}
