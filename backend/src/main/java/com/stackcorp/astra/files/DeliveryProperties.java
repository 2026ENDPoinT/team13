package com.stackcorp.astra.files;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param documentCookieTtl 쪽 이미지 cookie 유효 시간
 * @param videoCookieTtl 영상 cookie 유효 시간. 재생 중에는 player가 만료 전에 다시 받는다(architecture.md 7.1절)
 * @param secureCookie cookie에 Secure를 붙일지. https가 없는 로컬 실행에서만 끈다
 */
@ConfigurationProperties("astra.delivery")
record DeliveryProperties(@DefaultValue("10m") Duration documentCookieTtl, @DefaultValue("10m") Duration videoCookieTtl,
		@DefaultValue("true") boolean secureCookie) {
}
