package com.stackcorp.astra.integration.delivery;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.stackcorp.astra.files.api.DeliverySigner;

/**
 * 앱이 직접 검증하는 전달 cookie(ADR-0009). 값은 "만료 시각(epoch 초).HMAC-SHA256(경로 prefix, 만료 시각)"이다.
 * cookie는 그 prefix 아래에서만 유효하고, 다른 경로의 cookie로는 서명이 맞지 않는다.
 */
final class OriginDeliverySigner implements DeliverySigner {

	static final String COOKIE = "ASTRA-Delivery";

	private final byte[] key;

	OriginDeliverySigner(byte[] key) {
		if (key.length < 32) {
			throw new IllegalArgumentException("astra.delivery.origin.signing-key는 32 byte 이상이어야 합니다.");
		}
		this.key = key.clone();
	}

	@Override
	public List<SignedCookie> sign(String pathPrefix, Instant expiresAt) {
		long expires = expiresAt.getEpochSecond();
		return List.of(new SignedCookie(COOKIE, expires + "." + mac(pathPrefix, expires)));
	}

	/** @param value 브라우저가 보낸 cookie 값 */
	boolean valid(String pathPrefix, String value, Instant now) {
		int dot = value == null ? -1 : value.indexOf('.');
		if (dot < 1) {
			return false;
		}
		long expires;
		try {
			expires = Long.parseLong(value.substring(0, dot));
		}
		catch (NumberFormatException ex) {
			return false;
		}
		byte[] expected = mac(pathPrefix, expires).getBytes(StandardCharsets.US_ASCII);
		byte[] actual = value.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
		return MessageDigest.isEqual(expected, actual) && expires > now.getEpochSecond();
	}

	private String mac(String pathPrefix, long expires) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(this.key, "HmacSHA256"));
			byte[] digest = mac.doFinal((pathPrefix + "\n" + expires).getBytes(StandardCharsets.UTF_8));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
