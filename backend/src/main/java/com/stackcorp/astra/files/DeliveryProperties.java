package com.stackcorp.astra.files;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CloudFront가 직접 내보내는 경로는 앱을 거치지 않으므로, 자료를 지우거나 권한을 잃은 뒤에도 이미 발급한 cookie가 끝날 때까지 열린다.
 * cookie 유효 시간이 그 노출의 상한이라 설정으로 10분보다 길게 둘 수 없다(architecture.md 12절 "CloudFront 전달의 권한 회수").
 *
 * @param documentCookieTtl 쪽 이미지 cookie 유효 시간. 1분 이상 10분 이하
 * @param videoCookieTtl 영상 cookie 유효 시간. 1분 이상 10분 이하. 재생 중에는 player가 만료 전에 다시 받는다(architecture.md 7.1절)
 * @param secureCookie cookie에 Secure를 붙일지. https가 없는 로컬 실행에서만 끈다
 */
@ConfigurationProperties("astra.delivery")
record DeliveryProperties(@DefaultValue("10m") Duration documentCookieTtl, @DefaultValue("10m") Duration videoCookieTtl,
		@DefaultValue("true") boolean secureCookie) {

	static final Duration MIN_COOKIE_TTL = Duration.ofMinutes(1);

	static final Duration MAX_COOKIE_TTL = Duration.ofMinutes(10);

	DeliveryProperties {
		requireBounded("astra.delivery.document-cookie-ttl", documentCookieTtl);
		requireBounded("astra.delivery.video-cookie-ttl", videoCookieTtl);
	}

	private static void requireBounded(String name, Duration ttl) {
		if (ttl == null || ttl.compareTo(MIN_COOKIE_TTL) < 0 || ttl.compareTo(MAX_COOKIE_TTL) > 0) {
			throw new IllegalArgumentException(name + "은 1분 이상 10분 이하여야 합니다.");
		}
	}

}
