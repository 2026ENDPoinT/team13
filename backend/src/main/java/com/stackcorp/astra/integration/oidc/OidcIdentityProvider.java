package com.stackcorp.astra.integration.oidc;

import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPublicKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import javax.crypto.spec.SecretKeySpec;

import com.stackcorp.astra.identity.api.ExternalIdentityProvider;
import com.stackcorp.astra.identity.api.ExternalLoginException;

import tools.jackson.databind.JsonNode;

/**
 * 학교 IdP의 OpenID Connect adapter(ADR-0017). authorization code flow에 PKCE(S256), state, nonce를 쓴다.
 * <ul>
 * <li>discovery 문서와 JWKS는 처음 필요할 때 받는다. 시작할 때는 부르지 않으므로 IdP 장애가 앱 시작과 break-glass 로그인을 막지
 * 않는다. discovery는 1시간 동안, JWKS는 모르는 {@code kid}가 올 때까지 다시 쓴다. 이 값은 사용자별 상태가 아니다.</li>
 * <li>모르는 {@code kid}가 오면 JWKS를 한 번 다시 받는다(key 교체). 그래도 없으면 거부한다. ID token은 IdP token endpoint에서
 * 직접 받으므로 브라우저가 JWKS 재요청을 일으킬 수 없다.</li>
 * <li>IdP가 준 token은 저장하지 않는다. ID token의 claim 하나로 사용자를 찾고 버린다. access token과 refresh token은 쓰지
 * 않는다.</li>
 * </ul>
 */
final class OidcIdentityProvider implements ExternalIdentityProvider {

	/** 로그인 시작부터 callback까지 허용하는 시간. 로그인 전용 쿠키의 수명이기도 하다. */
	static final Duration LOGIN_TIMEOUT = Duration.ofMinutes(10);

	static final Duration METADATA_TTL = Duration.ofHours(1);

	private static final int MAX_CODE_LENGTH = 2048;

	private static final int MAX_EXTERNAL_REF_LENGTH = 64;

	private final OidcProperties properties;

	private final Clock clock;

	private final IssuerHttp http;

	private final IdTokenVerifier verifier;

	private final SecretKeySpec bindingKey;

	private volatile Metadata metadata;

	private volatile List<SigningKey> keys = List.of();

	OidcIdentityProvider(OidcProperties properties, Clock clock) {
		this.properties = properties;
		this.clock = clock;
		this.http = new IssuerHttp(properties.issuerUri(), properties.allowedHosts(), properties.timeout());
		this.verifier = new IdTokenVerifier(properties.issuer(), properties.clientId(), properties.clockSkew());
		this.bindingKey = LoginBinding.key(properties.clientSecret());
	}

	@Override
	public LoginRedirect begin(String returnTo) {
		Metadata metadata = metadata();
		LoginBinding binding = new LoginBinding(LoginBinding.random(), LoginBinding.random(), LoginBinding.random(),
				returnTo, this.clock.instant().plus(LOGIN_TIMEOUT).getEpochSecond());
		Map<String, String> query = new LinkedHashMap<>();
		query.put("response_type", "code");
		query.put("client_id", this.properties.clientId());
		query.put("redirect_uri", this.properties.redirectUri().toString());
		query.put("scope", String.join(" ", this.properties.scopes()));
		query.put("state", binding.state());
		query.put("nonce", binding.nonce());
		query.put("code_challenge", challenge(binding.verifier()));
		query.put("code_challenge_method", "S256");
		String base = metadata.authorizationEndpoint().toString();
		URI location = URI.create(base + (base.contains("?") ? "&" : "?") + IssuerHttp.form(query));
		return new LoginRedirect(location, binding.seal(this.bindingKey));
	}

	@Override
	public ExternalLogin complete(String sealed, Map<String, String> callback) {
		if (sealed == null) {
			throw new ExternalLoginException("binding_missing");
		}
		LoginBinding binding = LoginBinding.open(sealed, this.bindingKey);
		Instant now = this.clock.instant();
		if (now.getEpochSecond() >= binding.expiresAt()) {
			throw new ExternalLoginException("binding_expired");
		}
		if (callback.containsKey("error")) {
			throw new ExternalLoginException("idp_error");
		}
		String state = callback.get("state");
		if (state == null || !MessageDigest.isEqual(state.getBytes(StandardCharsets.UTF_8),
				binding.state().getBytes(StandardCharsets.UTF_8))) {
			throw new ExternalLoginException("state");
		}
		// RFC 9207: IdP가 iss를 붙였다면 설정한 issuer여야 한다(mix-up 방지).
		String iss = callback.get("iss");
		if (iss != null && !iss.equals(this.properties.issuer())) {
			throw new ExternalLoginException("iss_parameter");
		}
		String code = callback.get("code");
		if (code == null || code.isEmpty() || code.length() > MAX_CODE_LENGTH) {
			throw new ExternalLoginException("code");
		}
		Metadata metadata = metadata();
		JsonNode tokens = exchange(metadata, code, binding.verifier());
		JsonNode idToken = tokens.path("id_token");
		if (!idToken.isString()) {
			throw new ExternalLoginException("token_no_id_token");
		}
		JsonNode claims = this.verifier.verify(idToken.stringValue(), (kid) -> key(metadata, kid), binding.nonce(),
				now);
		return new ExternalLogin(externalRef(claims), binding.returnTo());
	}

	private JsonNode exchange(Metadata metadata, String code, String codeVerifier) {
		Map<String, String> form = new LinkedHashMap<>();
		form.put("grant_type", "authorization_code");
		form.put("code", code);
		form.put("redirect_uri", this.properties.redirectUri().toString());
		form.put("code_verifier", codeVerifier);
		String authorization = null;
		if (metadata.secretInBody()) {
			form.put("client_id", this.properties.clientId());
			form.put("client_secret", this.properties.clientSecret());
		}
		else {
			// RFC 6749 2.3.1: id와 secret을 form encoding한 뒤 Basic으로 보낸다.
			String pair = IssuerHttp.encode(this.properties.clientId()) + ":"
					+ IssuerHttp.encode(this.properties.clientSecret());
			authorization = "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
		}
		return this.http.postForm(metadata.tokenEndpoint(), form, authorization, "token");
	}

	private String externalRef(JsonNode claims) {
		JsonNode claim = claims.path(this.properties.userClaim());
		String value = claim.isString() ? claim.stringValue()
				: claim.isIntegralNumber() ? claim.bigIntegerValue().toString() : null;
		if (value == null || value.isEmpty() || value.length() > MAX_EXTERNAL_REF_LENGTH
				|| value.chars().anyMatch(Character::isISOControl)) {
			throw new ExternalLoginException("user_claim");
		}
		return value;
	}

	private Metadata metadata() {
		Metadata current = this.metadata;
		if (current != null && current.fetchedAt().plus(METADATA_TTL).isAfter(this.clock.instant())) {
			return current;
		}
		String issuer = this.properties.issuer();
		URI discovery = URI.create((issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer)
				+ "/.well-known/openid-configuration");
		JsonNode document = this.http.get(discovery, "discovery");
		// OpenID Connect Discovery 4.3: 문서의 issuer는 설정한 값과 글자 그대로 같아야 한다.
		if (!document.path("issuer").isString() || !issuer.equals(document.path("issuer").stringValue())) {
			throw new ExternalLoginException("discovery_issuer");
		}
		JsonNode algorithms = document.path("id_token_signing_alg_values_supported");
		if (algorithms.isArray() && !contains(algorithms, "RS256")) {
			throw new ExternalLoginException("discovery_alg");
		}
		JsonNode methods = document.path("token_endpoint_auth_methods_supported");
		boolean secretInBody = methods.isArray() && !contains(methods, "client_secret_basic")
				&& contains(methods, "client_secret_post");
		Metadata fresh = new Metadata(this.http.endpoint(document.path("authorization_endpoint"), "discovery"),
				this.http.endpoint(document.path("token_endpoint"), "discovery"),
				this.http.endpoint(document.path("jwks_uri"), "discovery"), secretInBody, this.clock.instant());
		this.metadata = fresh;
		return fresh;
	}

	private RSAPublicKey key(Metadata metadata, String kid) {
		RSAPublicKey key = select(this.keys, kid);
		if (key != null) {
			return key;
		}
		List<SigningKey> fresh = signingKeys(this.http.get(metadata.jwksUri(), "jwks"));
		this.keys = fresh;
		key = select(fresh, kid);
		if (key == null) {
			throw new ExternalLoginException("id_token_unknown_key");
		}
		return key;
	}

	// kid가 없는 token은 JWKS에 key가 하나뿐일 때만 받는다.
	private static RSAPublicKey select(List<SigningKey> keys, String kid) {
		if (kid == null) {
			return keys.size() == 1 ? keys.getFirst().key() : null;
		}
		return keys.stream().filter((k) -> kid.equals(k.kid())).map(SigningKey::key).findFirst().orElse(null);
	}

	// 서명용 RSA key(2048 bit 이상)만 남긴다. 다른 형식의 key는 건너뛴다.
	private static List<SigningKey> signingKeys(JsonNode jwks) {
		List<SigningKey> keys = new ArrayList<>();
		for (JsonNode jwk : jwks.path("keys")) {
			if (!"RSA".equals(text(jwk, "kty")) || !Objects.requireNonNullElse(text(jwk, "use"), "sig").equals("sig")
					|| !Objects.requireNonNullElse(text(jwk, "alg"), "RS256").equals("RS256")) {
				continue;
			}
			try {
				BigInteger modulus = new BigInteger(1, Base64.getUrlDecoder().decode(text(jwk, "n")));
				BigInteger exponent = new BigInteger(1, Base64.getUrlDecoder().decode(text(jwk, "e")));
				if (modulus.bitLength() < 2048) {
					continue;
				}
				RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
					.generatePublic(new RSAPublicKeySpec(modulus, exponent));
				keys.add(new SigningKey(text(jwk, "kid"), key));
			}
			catch (GeneralSecurityException | IllegalArgumentException | NullPointerException ex) {
				// 읽을 수 없는 key는 쓰지 않는다.
			}
		}
		return List.copyOf(keys);
	}

	private static String challenge(String verifier) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static boolean contains(JsonNode array, String value) {
		for (JsonNode item : array) {
			if (item.isString() && value.equals(item.stringValue())) {
				return true;
			}
		}
		return false;
	}

	private static String text(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isString() ? value.stringValue() : null;
	}

	private record Metadata(URI authorizationEndpoint, URI tokenEndpoint, URI jwksUri, boolean secretInBody,
			Instant fetchedAt) {
	}

	private record SigningKey(String kid, RSAPublicKey key) {
	}

}
