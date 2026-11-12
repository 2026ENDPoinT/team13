package com.stackcorp.astra.integration.delivery;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * CDN 없이 앱이 전달 경로(/doc, /vod)를 직접 응답하는 방식의 설정(ADR-0009).
 *
 * @param signingKey cookie 서명 key. Base64로 32 byte 이상. 비밀값이다
 */
@ConfigurationProperties("astra.delivery.origin")
record OriginDeliveryProperties(@DefaultValue("false") boolean enabled, String signingKey) {

	@Override
	public String toString() {
		return "OriginDeliveryProperties[enabled=" + this.enabled + "]";
	}

}
