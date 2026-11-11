package com.stackcorp.astra.integration.delivery;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * CloudFront signed cookie 설정. key pair ID와 개인 key는 Secrets Manager에서 받는다.
 *
 * @param baseUrl 서비스 도메인. 예: https://lms.example.ac.kr
 * @param privateKey PKCS#8 PEM RSA 개인 key
 */
@ConfigurationProperties("astra.cloudfront")
record CloudFrontProperties(URI baseUrl, String keyPairId, String privateKey) {

	@Override
	public String toString() {
		return "CloudFrontProperties[baseUrl=" + this.baseUrl + ", keyPairId=" + this.keyPairId + "]";
	}

}
