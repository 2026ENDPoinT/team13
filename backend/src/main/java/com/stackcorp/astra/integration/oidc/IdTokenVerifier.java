package com.stackcorp.astra.integration.oidc;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.function.Function;

import com.stackcorp.astra.identity.api.ExternalLoginException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * ID token 검증(OpenID Connect Core 3.1.3.7). 서명은 RS256만 받는다. {@code none}, HMAC, 그 밖의 알고리즘과 {@code crit}
 * header는 거부한다. 서명을 확인한 뒤에만 claim을 읽는다.
 */
final class IdTokenVerifier {

	/** callback까지 걸릴 수 있는 시간. 이보다 오래전에 발급한 token은 받지 않는다. */
	static final Duration MAX_AGE = OidcIdentityProvider.LOGIN_TIMEOUT;

	private static final int MAX_TOKEN_LENGTH = 16 * 1024;

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final String issuer;

	private final String clientId;

	private final Duration skew;

	IdTokenVerifier(String issuer, String clientId, Duration skew) {
		this.issuer = issuer;
		this.clientId = clientId;
		this.skew = skew;
	}

	/**
	 * @param keys {@code kid}(없으면 null)로 서명 key를 찾는다. 찾지 못하면 예외를 던진다
	 * @return 검증한 claim
	 */
	JsonNode verify(String token, Function<String, RSAPublicKey> keys, String nonce, Instant now) {
		if (token.length() > MAX_TOKEN_LENGTH) {
			throw new ExternalLoginException("id_token_format");
		}
		String[] parts = token.split("\\.", -1);
		if (parts.length != 3) {
			throw new ExternalLoginException("id_token_format");
		}
		JsonNode header = json(parts[0]);
		if (!"RS256".equals(string(header.path("alg"))) || header.has("crit")) {
			throw new ExternalLoginException("id_token_alg");
		}
		JsonNode kid = header.path("kid");
		if (!kid.isMissingNode() && !kid.isString()) {
			throw new ExternalLoginException("id_token_format");
		}
		RSAPublicKey key = keys.apply(kid.isString() ? kid.stringValue() : null);
		if (!signatureMatches(key, parts)) {
			throw new ExternalLoginException("id_token_signature");
		}
		JsonNode claims = json(parts[1]);
		if (!this.issuer.equals(string(claims.path("iss")))) {
			throw new ExternalLoginException("id_token_issuer");
		}
		checkAudience(claims);
		long seconds = now.getEpochSecond();
		long skewSeconds = this.skew.toSeconds();
		long exp = numericDate(claims, "exp");
		long iat = numericDate(claims, "iat");
		if (seconds >= exp + skewSeconds) {
			throw new ExternalLoginException("id_token_expired");
		}
		if (iat > seconds + skewSeconds || iat < seconds - MAX_AGE.toSeconds() - skewSeconds) {
			throw new ExternalLoginException("id_token_iat");
		}
		if (claims.has("nbf") && numericDate(claims, "nbf") > seconds + skewSeconds) {
			throw new ExternalLoginException("id_token_not_yet_valid");
		}
		String tokenNonce = string(claims.path("nonce"));
		if (tokenNonce == null || !MessageDigest.isEqual(tokenNonce.getBytes(StandardCharsets.UTF_8),
				nonce.getBytes(StandardCharsets.UTF_8))) {
			throw new ExternalLoginException("id_token_nonce");
		}
		String sub = string(claims.path("sub"));
		if (sub == null || sub.isEmpty()) {
			throw new ExternalLoginException("id_token_sub");
		}
		return claims;
	}

	// aud에 이 client가 있어야 한다. 대상이 여럿이면 azp가 이 client여야 하고, azp가 있으면 언제나 이 client여야 한다.
	private void checkAudience(JsonNode claims) {
		JsonNode aud = claims.path("aud");
		boolean contains;
		int count;
		if (aud.isString()) {
			contains = this.clientId.equals(aud.stringValue());
			count = 1;
		}
		else if (aud.isArray()) {
			contains = false;
			for (JsonNode value : aud) {
				contains |= value.isString() && this.clientId.equals(value.stringValue());
			}
			count = aud.size();
		}
		else {
			throw new ExternalLoginException("id_token_audience");
		}
		if (!contains) {
			throw new ExternalLoginException("id_token_audience");
		}
		JsonNode azp = claims.path("azp");
		if ((count > 1 && azp.isMissingNode()) || (!azp.isMissingNode() && !this.clientId.equals(string(azp)))) {
			throw new ExternalLoginException("id_token_azp");
		}
	}

	private static boolean signatureMatches(RSAPublicKey key, String[] parts) {
		try {
			Signature signature = Signature.getInstance("SHA256withRSA");
			signature.initVerify(key);
			signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
			return signature.verify(Base64.getUrlDecoder().decode(parts[2]));
		}
		catch (GeneralSecurityException | IllegalArgumentException ex) {
			return false;
		}
	}

	private static String string(JsonNode value) {
		return value.isString() ? value.stringValue() : null;
	}

	private static long numericDate(JsonNode claims, String name) {
		JsonNode value = claims.path(name);
		if (!value.isNumber()) {
			throw new ExternalLoginException("id_token_" + name);
		}
		return value.asLong();
	}

	private static JsonNode json(String part) {
		try {
			JsonNode node = MAPPER.readTree(Base64.getUrlDecoder().decode(part));
			if (!node.isObject()) {
				throw new ExternalLoginException("id_token_format");
			}
			return node;
		}
		catch (IllegalArgumentException | JacksonException ex) {
			throw new ExternalLoginException("id_token_format");
		}
	}

}
