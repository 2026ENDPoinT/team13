package com.stackcorp.astra.files;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param documentCookieTtl 쪽 이미지 cookie 유효 시간
 * @param videoCookieTtl 영상 cookie 유효 시간. 긴 강의는 하트비트 응답에서 다시 발급한다(architecture.md 7.1절)
 */
@ConfigurationProperties("astra.delivery")
record DeliveryProperties(@DefaultValue("10m") Duration documentCookieTtl, @DefaultValue("10m") Duration videoCookieTtl) {
}
