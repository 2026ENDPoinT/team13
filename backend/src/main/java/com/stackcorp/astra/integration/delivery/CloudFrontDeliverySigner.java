package com.stackcorp.astra.integration.delivery;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import com.stackcorp.astra.files.api.DeliverySigner;

import software.amazon.awssdk.services.cloudfront.CloudFrontUtilities;
import software.amazon.awssdk.services.cloudfront.cookie.CookiesForCustomPolicy;

/**
 * CloudFront custom policy signed cookie. 정책의 resource는 서비스 도메인의 경로 prefix 하나다.
 */
final class CloudFrontDeliverySigner implements DeliverySigner {

	private final CloudFrontUtilities utilities = CloudFrontUtilities.create();

	private final String base;

	private final String keyPairId;

	private final PrivateKey privateKey;

	CloudFrontDeliverySigner(CloudFrontProperties properties) {
		if (properties.baseUrl() == null || !"https".equals(properties.baseUrl().getScheme())) {
			throw new IllegalArgumentException("astra.cloudfront.base-url은 https 주소여야 합니다.");
		}
		String b = properties.baseUrl().toString();
		this.base = b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
		this.keyPairId = properties.keyPairId();
		this.privateKey = parse(properties.privateKey());
	}

	@Override
	public List<SignedCookie> sign(String pathPrefix, Instant expiresAt) {
		if (!pathPrefix.startsWith("/") || !pathPrefix.endsWith("/") || pathPrefix.contains("*")) {
			throw new IllegalArgumentException("서명할 경로는 /로 시작하고 끝나는 prefix여야 합니다.");
		}
		CookiesForCustomPolicy cookies = this.utilities.getCookiesForCustomPolicy((r) -> r
			.resourceUrl(this.base + pathPrefix)
			.resourceUrlPattern(this.base + pathPrefix + "*")
			.keyPairId(this.keyPairId)
			.privateKey(this.privateKey)
			.expirationDate(expiresAt));
		return List.of(cookie(cookies.policyHeaderValue()), cookie(cookies.signatureHeaderValue()),
				cookie(cookies.keyPairIdHeaderValue()));
	}

	// "CloudFront-Policy=..." 형식의 header 값을 이름과 값으로 나눈다.
	private static SignedCookie cookie(String header) {
		int eq = header.indexOf('=');
		return new SignedCookie(header.substring(0, eq), header.substring(eq + 1));
	}

	private static PrivateKey parse(String pem) {
		if (pem == null || !pem.contains("BEGIN PRIVATE KEY")) {
			throw new IllegalArgumentException("astra.cloudfront.private-key는 PKCS#8 PEM이어야 합니다.");
		}
		String body = pem.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
		try {
			return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			throw new IllegalArgumentException("astra.cloudfront.private-key를 읽지 못했습니다.", ex);
		}
	}

}
