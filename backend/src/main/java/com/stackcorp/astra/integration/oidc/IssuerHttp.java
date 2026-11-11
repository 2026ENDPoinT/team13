package com.stackcorp.astra.integration.oidc;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.stackcorp.astra.identity.api.ExternalLoginException;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * IdP를 부르는 HTTP client. issuer의 origin과 설정한 https host만 부르고 redirect를 따르지 않는다. 요청마다 전체 제한 시간과
 * 응답 크기 상한을 둔다(SC-08). 실패는 단계 이름과 짧은 code로만 알린다. 응답 본문은 로그에 남기지 않는다(SC-07).
 */
final class IssuerHttp {

	static final long MAX_RESPONSE_BYTES = 256 * 1024;

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private final HttpClient http;

	private final URI issuer;

	private final List<String> allowedHosts;

	private final Duration timeout;

	IssuerHttp(URI issuer, List<String> allowedHosts, Duration timeout) {
		this.issuer = issuer;
		this.allowedHosts = allowedHosts;
		this.timeout = timeout;
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(timeout)
			.build();
	}

	/**
	 * discovery 문서가 가리키는 주소를 허용 목록으로 검사한다. issuer와 같은 scheme·host·port이거나, https 기본 port의 허용 host여야
	 * 한다.
	 */
	URI endpoint(JsonNode value, String step) {
		if (!value.isString()) {
			throw new ExternalLoginException(step + "_missing");
		}
		URI uri;
		try {
			uri = new URI(value.stringValue());
		}
		catch (URISyntaxException ex) {
			throw new ExternalLoginException(step + "_invalid");
		}
		if (uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
			throw new ExternalLoginException(step + "_invalid");
		}
		String host = uri.getHost().toLowerCase(Locale.ROOT);
		boolean sameOrigin = this.issuer.getScheme().equalsIgnoreCase(uri.getScheme())
				&& this.issuer.getHost().equalsIgnoreCase(host) && port(this.issuer) == port(uri);
		boolean allowed = "https".equalsIgnoreCase(uri.getScheme()) && this.allowedHosts.contains(host)
				&& port(uri) == 443;
		if (!sameOrigin && !allowed) {
			throw new ExternalLoginException(step + "_host_not_allowed");
		}
		return uri;
	}

	JsonNode get(URI uri, String step) {
		return send(HttpRequest.newBuilder(uri).header("accept", "application/json").GET(), step);
	}

	JsonNode postForm(URI uri, Map<String, String> form, String authorization, String step) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
			.header("accept", "application/json")
			.header("content-type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form(form)));
		if (authorization != null) {
			request.header("authorization", authorization);
		}
		return send(request, step);
	}

	static String form(Map<String, String> values) {
		return values.entrySet()
			.stream()
			.map((e) -> encode(e.getKey()) + "=" + encode(e.getValue()))
			.collect(Collectors.joining("&"));
	}

	static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private JsonNode send(HttpRequest.Builder builder, String step) {
		CompletableFuture<HttpResponse<byte[]>> future = this.http.sendAsync(builder.timeout(this.timeout).build(),
				HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), MAX_RESPONSE_BYTES));
		HttpResponse<byte[]> response;
		try {
			response = future.get(this.timeout.toMillis(), TimeUnit.MILLISECONDS);
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new ExternalLoginException(step + "_interrupted");
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw new ExternalLoginException(step + "_timeout");
		}
		catch (ExecutionException ex) {
			throw new ExternalLoginException(
					step + (ex.getCause() instanceof HttpTimeoutException ? "_timeout" : "_failed"));
		}
		if (response.statusCode() != 200) {
			throw new ExternalLoginException(step + "_status_" + response.statusCode());
		}
		try {
			JsonNode root = MAPPER.readTree(response.body());
			if (!root.isObject()) {
				throw new ExternalLoginException(step + "_invalid");
			}
			return root;
		}
		catch (JacksonException ex) {
			throw new ExternalLoginException(step + "_invalid");
		}
	}

	private static int port(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

}
