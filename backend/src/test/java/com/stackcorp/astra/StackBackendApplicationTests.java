package com.stackcorp.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 배포하는 설정 그대로 실제 port를 열어 실행한다. 관리 port의 health, API port에서 /actuator가 보이지 않는지, 로그 형식을
 * 확인한다(ADR-0019, architecture.md 3.3절과 9절). 연결한 쪽(loopback)은 compose 단일 host 구성처럼 믿는 proxy로 둔다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = "astra.client-address.trusted-proxies=127.0.0.0/8,::1")
@ExtendWith(OutputCaptureExtension.class)
class StackBackendApplicationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int serverPort;

	@LocalManagementPort
	int managementPort;

	@AfterAll
	static void closeClient() {
		HTTP.close();
	}

	@Test
	void reportsOnlyStatusOnTheManagementPort() throws Exception {
		assertThat(this.managementPort).isNotEqualTo(this.serverPort);
		for (String path : List.of("/actuator/health", "/actuator/health/liveness", "/actuator/health/readiness")) {
			HttpResponse<String> response = get(this.managementPort, path, null);
			assertThat(response.statusCode()).as(path).isEqualTo(200);
			JsonNode body = JSON.readTree(response.body());
			assertThat(body.path("status").asString()).as(path).isEqualTo("UP");
			assertThat(body.has("components")).as(path).isFalse();
			assertThat(body.has("details")).as(path).isFalse();
		}
		// health 말고는 관리 port에도 endpoint가 없다.
		for (String path : List.of("/actuator", "/actuator/env", "/actuator/info", "/actuator/metrics",
				"/actuator/loggers", "/actuator/heapdump", "/actuator/threaddump", "/actuator/configprops",
				"/actuator/beans", "/actuator/mappings", "/actuator/health/db")) {
			HttpResponse<String> response = get(this.managementPort, path, null);
			assertThat(response.statusCode()).as(path).isIn(401, 403, 404);
		}
	}

	@Test
	void doesNotRouteActuatorThroughTheApiPort() throws Exception {
		for (String path : List.of("/actuator/health", "/actuator/health/readiness", "/actuator", "/actuator/env")) {
			HttpResponse<String> response = get(this.serverPort, path, null);
			assertThat(response.statusCode()).as(path).isIn(401, 403, 404);
			assertThat(response.body()).as(path).doesNotContain("\"UP\"");
		}
		assertThat(get(this.serverPort, "/api/auth/csrf", null).statusCode()).isEqualTo(204);
	}

	@Test
	void logsOneJsonLinePerRequestWithoutQueryStringOrCookieValues(CapturedOutput output) throws Exception {
		HttpResponse<String> csrf = get(this.serverPort, "/api/auth/csrf?token=query-secret-4711",
				"ASTRA_SESSION=cookie-secret-4712");
		assertThat(csrf.statusCode()).isEqualTo(204);
		HttpResponse<String> denied = get(this.serverPort, "/api/me?q=query-secret-4713",
				"ASTRA_SESSION=cookie-secret-4714");
		assertThat(denied.statusCode()).isEqualTo(401);

		JsonNode served = awaitRequestLine(output, "/api/auth/csrf");
		assertThat(served.path("message").asString())
			.matches("request method=GET route=/api/auth/csrf status=204 durationMs=\\d+");
		assertThat(served.has("@timestamp")).isTrue();
		assertThat(served.path("log").path("level").asString()).isEqualTo("INFO");
		JsonNode rejected = awaitRequestLine(output, "status=401");
		// 인증 단계에서 끊긴 요청은 처리한 경로 pattern이 없다. 요청 경로 값을 대신 남기지 않는다.
		assertThat(rejected.path("message").asString()).startsWith("request method=GET route=- status=401");

		assertThat(output.getAll()).doesNotContain("query-secret-4711", "query-secret-4713", "cookie-secret-4712",
				"cookie-secret-4714", "/api/me?");
	}

	/**
	 * 허용 Origin이 비면 OriginCheckFilter는 요청 자신의 origin과 비교한다. 실제 Tomcat이 받는 요청에서 그 origin은 연결의 scheme과
	 * Host로 정해지고, 앞단이 붙인 X-Forwarded-Host·Proto·Port로 바뀌지 않아야 한다(ADR-0004 4번). 연결한 쪽이 믿는 proxy여도
	 * 그렇다. 믿는 proxy 설정({@code astra.client-address.trusted-proxies})은 브라우저 주소에만 쓰인다. 이 header를 믿는 설정
	 * (server.forward-headers-strategy 등)이 생기면 loopback에서 온 이 요청은 https://eclass.example.ac.kr로 보여 통과하고 이 테스트가
	 * 실패한다. 그래서 proxy 뒤 배포는 허용 Origin을 명시한다.
	 */
	@Test
	void selfOriginCheckIgnoresForwardedHostEvenWhenItMatchesTheOrigin() throws Exception {
		HttpResponse<String> csrf = get(this.serverPort, "/api/auth/csrf", null);
		String xsrf = csrf.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter((c) -> c.startsWith("XSRF-TOKEN="))
			.map((c) -> c.substring("XSRF-TOKEN=".length(), c.indexOf(';')))
			.findFirst()
			.orElseThrow();

		// 대조군: 연결 그대로의 자기 origin은 Origin과 CSRF 검사를 지나 인증 단계의 401이 된다.
		HttpResponse<String> own = postLogout(xsrf, "http://127.0.0.1:" + this.serverPort, null);
		assertThat(own.statusCode()).isEqualTo(401);

		HttpResponse<String> forwarded = postLogout(xsrf, "https://eclass.example.ac.kr", "eclass.example.ac.kr");
		assertThat(forwarded.statusCode()).isEqualTo(403);
		assertThat(forwarded.body()).contains("\"code\":\"forbidden\"").contains("허용하지 않은 출처");
	}

	private HttpResponse<String> postLogout(String xsrf, String origin, String forwardedHost)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest
			.newBuilder(URI.create("http://127.0.0.1:" + this.serverPort + "/api/auth/logout"))
			.POST(HttpRequest.BodyPublishers.noBody())
			.header("Origin", origin)
			.header("Sec-Fetch-Site", "same-origin")
			.header("X-XSRF-TOKEN", xsrf)
			.header("Cookie", "XSRF-TOKEN=" + xsrf);
		if (forwardedHost != null) {
			request.header("X-Forwarded-Host", forwardedHost)
				.header("X-Forwarded-Proto", "https")
				.header("X-Forwarded-Port", "443")
				.header("X-Forwarded-For", "203.0.113.7");
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private static JsonNode awaitRequestLine(CapturedOutput output, String marker) throws InterruptedException {
		// 응답을 보낸 뒤 filter가 로그를 쓰므로 잠깐 기다린다.
		for (int attempt = 0; attempt < 50; attempt++) {
			Optional<JsonNode> line = output.getOut()
				.lines()
				.map(StackBackendApplicationTests::parse)
				.flatMap(Optional::stream)
				.filter((node) -> node.path("message").asString("").startsWith("request ")
						&& node.path("message").asString("").contains(marker))
				.findFirst();
			if (line.isPresent()) {
				return line.get();
			}
			Thread.sleep(100);
		}
		throw new AssertionError("no request log line containing " + marker + " in:\n" + output.getOut());
	}

	private static Optional<JsonNode> parse(String line) {
		if (!line.startsWith("{")) {
			return Optional.empty();
		}
		try {
			return Optional.of(JSON.readTree(line));
		}
		catch (JacksonException ex) {
			return Optional.empty();
		}
	}

	private static HttpResponse<String> get(int port, String path, String cookie)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
		if (cookie != null) {
			request.header("Cookie", cookie);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

}
