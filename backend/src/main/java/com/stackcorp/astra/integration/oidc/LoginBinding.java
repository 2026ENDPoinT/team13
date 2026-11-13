package com.stackcorp.astra.integration.oidc;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import com.stackcorp.astra.identity.api.ExternalLoginException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 로그인 시작과 callback 사이에 브라우저에 맡겨 두는 값. 서버는 아무것도 기억하지 않는다(4.3절 stateless).
 * AES-256-GCM으로 암호화해 위조와 열람을 함께 막는다. key는 client secret에서 HMAC-SHA256으로 만든다. 그래서 api
 * 인스턴스가 여럿이어도 같은 key를 쓰고, secret을 바꾸면 진행 중인 로그인만 무효가 된다.
 *
 * @param state CSRF 방지 값. callback의 state와 비교한다
 * @param nonce ID token의 nonce와 비교한다
 * @param verifier PKCE code verifier(RFC 7636)
 * @param returnTo 로그인 뒤 돌아갈 경로
 * @param expiresAt 이 시각(epoch 초)이 지나면 받지 않는다
 * @param reauthentication 로그인한 사용자의 재인증이다. callback에서 IdP의 재인증 시각을 확인한다(ADR-0029)
 */
record LoginBinding(String state, String nonce, String verifier, String returnTo, long expiresAt,
		boolean reauthentication) {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final SecureRandom RANDOM = new SecureRandom();

	private static final byte[] AAD = "astra-oidc-login-binding-v1".getBytes(StandardCharsets.US_ASCII);

	private static final int IV_BYTES = 12;

	private static final int MAX_SEALED_LENGTH = 4096;

	static SecretKeySpec key(String clientSecret) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
			return new SecretKeySpec(mac.doFinal(AAD), "AES");
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** URL에 안전한 32 byte 난수 문자열. state, nonce, verifier에 쓴다. */
	static String random() {
		byte[] value = new byte[32];
		RANDOM.nextBytes(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	String seal(SecretKeySpec key) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("s", this.state);
		body.put("n", this.nonce);
		body.put("v", this.verifier);
		body.put("r", this.returnTo);
		body.put("e", this.expiresAt);
		body.put("a", this.reauthentication);
		byte[] iv = new byte[IV_BYTES];
		RANDOM.nextBytes(iv);
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
			cipher.updateAAD(AAD);
			byte[] sealed = cipher.doFinal(MAPPER.writeValueAsBytes(body));
			return Base64.getUrlEncoder()
				.withoutPadding()
				.encodeToString(ByteBuffer.allocate(iv.length + sealed.length).put(iv).put(sealed).array());
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** @throws ExternalLoginException 위조했거나 다른 key로 만든 값일 때 */
	static LoginBinding open(String sealed, SecretKeySpec key) {
		if (sealed.length() > MAX_SEALED_LENGTH) {
			throw new ExternalLoginException("binding_invalid");
		}
		try {
			byte[] raw = Base64.getUrlDecoder().decode(sealed);
			if (raw.length <= IV_BYTES) {
				throw new ExternalLoginException("binding_invalid");
			}
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, raw, 0, IV_BYTES));
			cipher.updateAAD(AAD);
			JsonNode body = MAPPER.readTree(cipher.doFinal(raw, IV_BYTES, raw.length - IV_BYTES));
			if (!body.path("s").isString() || !body.path("n").isString() || !body.path("v").isString()
					|| !body.path("r").isString() || !body.path("e").isIntegralNumber() || !body.path("a").isBoolean()) {
				throw new ExternalLoginException("binding_invalid");
			}
			return new LoginBinding(body.path("s").stringValue(), body.path("n").stringValue(),
					body.path("v").stringValue(), body.path("r").stringValue(), body.path("e").longValue(),
					body.path("a").booleanValue());
		}
		catch (GeneralSecurityException | IllegalArgumentException | JacksonException ex) {
			throw new ExternalLoginException("binding_invalid");
		}
	}

	// record 기본 toString은 verifier와 nonce를 그대로 내보낸다.
	@Override
	public String toString() {
		return "LoginBinding[expiresAt=" + this.expiresAt + "]";
	}

}
