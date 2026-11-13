package com.stackcorp.astra.integration.delivery;

import java.util.Base64;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * astra.delivery.origin.enabled=true일 때만 켠다. CloudFront 서명과 함께 켜면 어느 쪽 cookie를 낼지 모호하므로 기동을 막는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OriginDeliveryProperties.class)
@ConditionalOnProperty(name = "astra.delivery.origin.enabled", havingValue = "true")
class OriginDeliveryConfiguration {

	@Bean
	OriginDeliverySigner originDeliverySigner(OriginDeliveryProperties properties, Environment environment) {
		if (environment.containsProperty("astra.cloudfront.key-pair-id")) {
			throw new IllegalStateException("astra.delivery.origin.enabled와 astra.cloudfront.key-pair-id를 함께 설정할 수 없습니다.");
		}
		if (properties.signingKey() == null || properties.signingKey().isBlank()) {
			throw new IllegalStateException(
					"astra.delivery.origin.signing-key(ASTRA_DELIVERY_ORIGIN_SIGNING_KEY)가 필요합니다. 예: openssl rand -base64 32");
		}
		byte[] key;
		try {
			key = Base64.getDecoder().decode(properties.signingKey().strip());
		}
		catch (IllegalArgumentException ex) {
			throw new IllegalStateException(
					"astra.delivery.origin.signing-key(ASTRA_DELIVERY_ORIGIN_SIGNING_KEY)가 Base64가 아닙니다. 예: openssl rand -base64 32");
		}
		return new OriginDeliverySigner(key);
	}

}
