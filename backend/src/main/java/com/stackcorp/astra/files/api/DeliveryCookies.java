package com.stackcorp.astra.files.api;

import java.time.Duration;
import java.util.List;

import org.springframework.http.ResponseCookie;

/**
 * 전달 경로의 signed cookie 발급(ADR-0007 6번). 호출하는 쪽이 먼저 권한을 판정한다.
 * 전달 서명이 설정되지 않은 환경에서는 빈 목록을 돌려준다.
 */
public interface DeliveryCookies {

	List<DeliveryCookie> forDocument(String renditionKey);

	List<DeliveryCookie> forVideo(String assetKey);

	/** 영상 cookie의 유효 시간. player가 이 시간 안에 다시 받아야 한다. */
	Duration videoTtl();

	/**
	 * @param path 이 cookie가 유효한 경로. 브라우저는 이 경로 아래 요청에만 cookie를 보낸다
	 */
	record DeliveryCookie(String name, String value, String path, Duration maxAge, boolean secure) {

		/** Set-Cookie header 값. script가 읽지 못하게 HttpOnly로 낸다. */
		public String header() {
			return ResponseCookie.from(this.name, this.value)
				.path(this.path)
				.maxAge(this.maxAge)
				.secure(this.secure)
				.httpOnly(true)
				.sameSite("Lax")
				.build()
				.toString();
		}

	}

}
