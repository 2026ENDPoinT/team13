package com.stackcorp.astra.integration.oidc;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트용 OpenID Provider. 같은 process의 JDK HttpServer로 discovery, JWKS, token endpoint를 응답하고, 생성한 RSA key로 ID
 * token에 서명한다. 브라우저가 IdP에서 로그인하는 단계는 {@link #authorize}가 대신한다.
 * <p>
 * 실제 IdP처럼 code는 한 번만 받고, redirect_uri, PKCE verifier, client 인증을 확인한다. 테스트는 hook으로 ID token의 header,
 * claim, 서명, 응답을 바꾸어 adapter가 거부하는지 본다.
 */
public final class MockOpenIdProvider implements AutoCloseable {

	public static final String CLIENT_ID = "astra-e-class";

	public static final String CLIENT_SECRET = "mock-client-secret-0123456789";

	/** 학번을 담는 claim. sub는 IdP 내부 값이라 학번과 다르다. */
	public static final String USER_CLAIM = "student_number";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final HttpServer server;

	private final String issuer;

	private final String redirectUri;

	private final Map<String, Pending> codes = new ConcurrentHashMap<>();

	private final Map<String, HttpHandler> overrides = new ConcurrentHashMap<>();

	private final AtomicInteger requests = new AtomicInteger();

	private final List<String> issued = new CopyOnWriteArrayList<>();

	private volatile boolean down;

	private volatile Key signing;

	private volatile List<Key> published;

	private volatile UnaryOperator<Map<String, Object>> discovery = UnaryOperator.identity();

	private volatile Consumer<Map<String, Object>> header = (h) -> {
	};

	private volatile Consumer<Map<String, Object>> claims = (c) -> {
	};

	private volatile UnaryOperator<String> token = UnaryOperator.identity();

	private MockOpenIdProvider(String redirectUri) throws IOException {
		this.redirectUri = redirectUri;
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.issuer = "http://127.0.0.1:" + this.server.getAddress().getPort() + "/realms/school";
		this.server.createContext("/", this::handle);
		this.server.start();
		reset();
	}

	public static MockOpenIdProvider start(String redirectUri) {
		try {
			return new MockOpenIdProvider(redirectUri);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public String issuer() {
		return this.issuer;
	}

	/** hook과 key를 처음 상태로 돌리고 응답을 다시 시작한다. */
	public void reset() {
		Key key = Key.generate();
		this.signing = key;
		this.published = List.of(key);
		this.discovery = UnaryOperator.identity();
		this.header = (h) -> {
		};
		this.claims = (c) -> {
		};
		this.token = UnaryOperator.identity();
		this.overrides.clear();
		this.down = false;
	}

	/** true면 모든 요청에 503으로 응답한다. */
	public void down(boolean down) {
		this.down = down;
	}

	public int requests() {
		return this.requests.get();
	}

	/** 지금까지 발급한 code, access token, ID token. 응답과 로그에 나오면 안 되는 값이다. */
	public List<String> issuedSecrets() {
		return List.copyOf(this.issued);
	}

	public void discovery(UnaryOperator<Map<String, Object>> customizer) {
		this.discovery = customizer;
	}

	public void header(Consumer<Map<String, Object>> customizer) {
		this.header = customizer;
	}

	public void claims(Consumer<Map<String, Object>> customizer) {
		this.claims = customizer;
	}

	/** 서명까지 마친 ID token 문자열을 바꾼다. */
	public void token(UnaryOperator<String> customizer) {
		this.token = customizer;
	}

	/** 경로 하나의 응답을 바꾼다. */
	public void override(String path, HttpHandler handler) {
		this.overrides.put(path, handler);
	}

	/** 새 key로 서명하고 JWKS에는 새 key만 둔다. key마다 kid가 다르다. */
	public void rotate() {
		Key key = Key.generate();
		this.signing = key;
		this.published = List.of(key);
	}

	/** JWKS에 없는 key로 서명한다. */
	public void signWithUnpublishedKey() {
		this.signing = Key.generate();
	}

	/**
	 * 사용자가 IdP에서 로그인을 마친 것처럼 code를 만들고 callback 주소를 돌려준다.
	 * @param location adapter가 만든 authorization 주소
	 * @param studentNumber {@link #USER_CLAIM}에 넣을 값
	 */
	public URI authorize(URI location, String studentNumber) {
		Map<String, String> query = query(location.getRawQuery());
		if (!"code".equals(query.get("response_type")) || !CLIENT_ID.equals(query.get("client_id"))
				|| !this.redirectUri.equals(query.get("redirect_uri")) || !"S256".equals(query.get("code_challenge_method"))
				|| !query.getOrDefault("scope", "").contains("openid") || query.get("state") == null
				|| query.get("nonce") == null || query.get("code_challenge") == null) {
			throw new IllegalArgumentException("invalid authorization request");
		}
		String code = UUID.randomUUID().toString();
		this.issued.add(code);
		this.codes.put(code, new Pending(query.get("nonce"), query.get("code_challenge"), studentNumber));
		return URI.create(this.redirectUri + "?code=" + code + "&state=" + encode(query.get("state")) + "&iss="
				+ encode(this.issuer));
	}

	@Override
	public void close() {
		this.server.stop(0);
	}

	private void handle(HttpExchange exchange) throws IOException {
		this.requests.incrementAndGet();
		try {
			String prefix = URI.create(this.issuer).getPath();
			String full = exchange.getRequestURI().getPath();
			String path = full.startsWith(prefix) ? full.substring(prefix.length()) : full;
			if (this.down) {
				respond(exchange, 503, Map.of("error", "temporarily_unavailable"));
				return;
			}
			HttpHandler override = this.overrides.get(path);
			if (override != null) {
				override.handle(exchange);
				return;
			}
			switch (path) {
				case "/.well-known/openid-configuration" -> respond(exchange, 200, this.discovery.apply(discoveryDocument()));
				case "/jwks" -> respond(exchange, 200, Map.of("keys", this.published.stream().map(Key::jwk).toList()));
				case "/token" -> token(exchange);
				default -> respond(exchange, 404, Map.of("error", "not_found"));
			}
		}
		finally {
			exchange.close();
		}
	}

	private Map<String, Object> discoveryDocument() {
		Map<String, Object> document = new LinkedHashMap<>();
		document.put("issuer", this.issuer);
		document.put("authorization_endpoint", this.issuer + "/protocol/openid-connect/auth");
		document.put("token_endpoint", this.issuer + "/token");
		document.put("jwks_uri", this.issuer + "/jwks");
		document.put("response_types_supported", List.of("code"));
		document.put("subject_types_supported", List.of("public"));
		document.put("id_token_signing_alg_values_supported", List.of("RS256"));
		document.put("code_challenge_methods_supported", List.of("S256"));
		document.put("token_endpoint_auth_methods_supported", List.of("client_secret_basic", "client_secret_post"));
		return document;
	}

	private void token(HttpExchange exchange) throws IOException {
		Map<String, String> form = query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
		if (!"POST".equals(exchange.getRequestMethod()) || !clientAuthenticated(exchange, form)) {
			respond(exchange, 401, Map.of("error", "invalid_client"));
			return;
		}
		Pending pending = form.get("code") == null ? null : this.codes.remove(form.get("code"));
		if (pending == null || !"authorization_code".equals(form.get("grant_type"))
				|| !this.redirectUri.equals(form.get("redirect_uri")) || form.get("code_verifier") == null
				|| !pending.challenge().equals(challenge(form.get("code_verifier")))) {
			respond(exchange, 400, Map.of("error", "invalid_grant"));
			return;
		}
		long now = Instant.now().getEpochSecond();
		Map<String, Object> claims = new LinkedHashMap<>();
		claims.put("iss", this.issuer);
		claims.put("sub", "pairwise-" + UUID.randomUUID());
		claims.put("aud", CLIENT_ID);
		claims.put("exp", now + 300);
		claims.put("iat", now);
		claims.put("nonce", pending.nonce());
		claims.put(USER_CLAIM, pending.studentNumber());
		this.claims.accept(claims);
		Key key = this.signing;
		Map<String, Object> header = new LinkedHashMap<>();
		header.put("alg", "RS256");
		header.put("typ", "JWT");
		header.put("kid", key.kid());
		this.header.accept(header);
		String idToken = this.token.apply(key.sign(header, claims));
		String accessToken = "at-" + UUID.randomUUID();
		this.issued.add(idToken);
		this.issued.add(accessToken);
		respond(exchange, 200, Map.of("access_token", accessToken, "token_type", "Bearer", "expires_in", 300,
				"id_token", idToken));
	}

	private static boolean clientAuthenticated(HttpExchange exchange, Map<String, String> form) {
		String authorization = exchange.getRequestHeaders().getFirst("authorization");
		if (authorization != null) {
			String expected = "Basic " + Base64.getEncoder()
				.encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
			return expected.equals(authorization);
		}
		return CLIENT_ID.equals(form.get("client_id")) && CLIENT_SECRET.equals(form.get("client_secret"));
	}

	public static void respond(HttpExchange exchange, int status, Object body) throws IOException {
		byte[] bytes = JSON.writeValueAsBytes(body);
		exchange.getResponseHeaders().set("content-type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length);
		exchange.getResponseBody().write(bytes);
	}

	static Map<String, String> query(String raw) {
		Map<String, String> values = new HashMap<>();
		if (raw == null || raw.isEmpty()) {
			return values;
		}
		for (String pair : raw.split("&")) {
			int eq = pair.indexOf('=');
			if (eq < 0) {
				values.put(URLDecoder.decode(pair, StandardCharsets.UTF_8), "");
				continue;
			}
			values.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
					URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
		}
		return values;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static String challenge(String verifier) {
		try {
			return base64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException(ex);
		}
	}

	static String base64(byte[] value) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private record Pending(String nonce, String challenge, String studentNumber) {
	}

	record Key(String kid, KeyPair pair) {

		private static final AtomicInteger GENERATED = new AtomicInteger();

		static Key generate() {
			String kid = "key-" + GENERATED.incrementAndGet();
			try {
				KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
				generator.initialize(2048);
				return new Key(kid, generator.generateKeyPair());
			}
			catch (GeneralSecurityException ex) {
				throw new IllegalStateException(ex);
			}
		}

		Map<String, Object> jwk() {
			RSAPublicKey key = (RSAPublicKey) this.pair.getPublic();
			Map<String, Object> jwk = new LinkedHashMap<>();
			jwk.put("kty", "RSA");
			jwk.put("use", "sig");
			jwk.put("alg", "RS256");
			jwk.put("kid", this.kid);
			jwk.put("n", base64(unsigned(key.getModulus())));
			jwk.put("e", base64(unsigned(key.getPublicExponent())));
			return jwk;
		}

		String sign(Map<String, Object> header, Map<String, Object> claims) {
			String input = base64(JSON.writeValueAsBytes(header)) + "." + base64(JSON.writeValueAsBytes(claims));
			try {
				Signature signature = Signature.getInstance("SHA256withRSA");
				signature.initSign(this.pair.getPrivate());
				signature.update(input.getBytes(StandardCharsets.US_ASCII));
				return input + "." + base64(signature.sign());
			}
			catch (GeneralSecurityException ex) {
				throw new IllegalStateException(ex);
			}
		}

		private static byte[] unsigned(BigInteger value) {
			byte[] bytes = value.toByteArray();
			if (bytes[0] == 0 && bytes.length > 1) {
				byte[] trimmed = new byte[bytes.length - 1];
				System.arraycopy(bytes, 1, trimmed, 0, trimmed.length);
				return trimmed;
			}
			return bytes;
		}

	}

	/** 다른 테스트가 쓰는 JSON 응답 handler. */
	public static HttpHandler json(int status, Object body) {
		return (exchange) -> respond(exchange, status, body);
	}

}
