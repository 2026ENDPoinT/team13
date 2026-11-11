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
 * 확인한다(ADR-0019, architecture.md 3.3절과 9절).
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
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
