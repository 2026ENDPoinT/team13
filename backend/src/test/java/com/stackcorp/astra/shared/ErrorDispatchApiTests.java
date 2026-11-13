package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.SocketException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;

import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestcontainersConfiguration;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * DispatcherServlet 밖(servlet filter, security filter chain)에서 난 오류도 컨트롤러 단계와 같은 problem JSON으로 응답한다. 그런
 * 오류는 servlet container가 /error로 다시 보내므로 MockMvc로는 볼 수 없어 실제 port로 확인한다. security가 직접 쓰는 401, 403은
 * 그대로이고, 이미 나가기 시작한 응답에는 problem을 덧붙이지 않는다.
 */
@Import({ TestcontainersConfiguration.class, ErrorDispatchApiTests.Endpoints.class,
		ErrorDispatchApiTests.FailingFilter.class })
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ErrorDispatchApiTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Autowired
	TestData data;

	@AfterAll
	static void closeClient() {
		HTTP.close();
	}

	@Test
	void filterFailuresGetTheSameProblemAsControllerFailures(CapturedOutput output) throws Exception {
		String session = this.data.session(this.data.user("STUDENT"));
		HttpResponse<String> controller = get("/api/test-only/state", session, "application/json");
		assertProblem(controller, 500, "internal_error");

		HttpResponse<String> filter = get("/api/test-only/filter-boom?q=SECRET-QUERY", session, "application/json");
		assertProblem(filter, 500, "internal_error");
		// 컨트롤러 단계의 problem과 header와 필드가 같다. Spring 기본 /error의 timestamp, error, path, trace는 없다.
		assertThat(filter.headers().firstValue("Cache-Control"))
			.isEqualTo(controller.headers().firstValue("Cache-Control"));
		assertThat(fieldNames(filter)).isEqualTo(fieldNames(controller));
		assertThat(json(filter).path("instance").asString()).isEqualTo("/api/test-only/filter-boom");
		assertThat(filter.body()).doesNotContain("SECRET", "jdbc");
		// 처음 dispatch에서 RequestIdFilter가 응답과 같은 requestId로 한 번 남긴다. /error는 다시 남기지 않는다.
		assertThat(unhandledLines(output, filter.headers().firstValue(RequestIds.HEADER).orElseThrow())).singleElement()
			.asString()
			.contains("\"ERROR\"", "method=GET route=-", "DataAccessResourceFailureException")
			.doesNotContain("SECRET-QUERY", "filter-boom");

		// JSON을 받지 않는 client에도 problem JSON 하나로 응답한다.
		assertProblem(get("/api/test-only/filter-boom", session, "text/html"), 500, "internal_error");
	}

	@Test
	void requestsRejectedBySecurityFirewallGetAProblem() throws Exception {
		// StrictHttpFirewall이 거부하면 sendError(400)로 /error에 간다.
		HttpResponse<String> rejected = get("/api/me;x=y", null, "application/json");
		assertProblem(rejected, 400, "invalid_request");
		assertThat(rejected.body()).doesNotContain("timestamp", "\"path\"");
	}

	@Test
	void securityRejectionsKeepTheirOwnProblems() throws Exception {
		assertProblem(get("/api/me", null, "application/json"), 401, "unauthorized");
		HttpResponse<String> forbidden = HTTP.send(
				HttpRequest.newBuilder(uri("/api/auth/break-glass")).POST(HttpRequest.BodyPublishers.noBody()).build(),
				HttpResponse.BodyHandlers.ofString());
		assertProblem(forbidden, 403, "forbidden");
	}

	@Test
	void requestingTheErrorPathDirectlyIsNotFound() throws Exception {
		assertProblem(get("/error", null, "application/json"), 404, "not_found");
	}

	@Test
	void failuresAfterTheResponseIsCommittedAreNotFollowedByAProblem() throws Exception {
		// 응답이 이미 나갔으면 container는 /error를 include로 부른다. JSON을 보내던 중이라 problem JSON도 쓸 수 있지만, 보낸 바이트
		// 뒤에 덧붙이지 않고 연결이 끊긴다.
		String session = this.data.session(this.data.user("STUDENT"));
		byte[] received;
		try (Socket socket = new Socket("127.0.0.1", this.port)) {
			socket.setSoTimeout(10_000);
			socket.getOutputStream()
				.write(("GET /api/test-only/export-fails-midway HTTP/1.1\r\nHost: 127.0.0.1\r\nCookie: ASTRA_SESSION="
						+ session + "\r\nConnection: close\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
			received = readUntilClosed(socket.getInputStream());
		}
		String response = new String(received, StandardCharsets.ISO_8859_1);
		assertThat(response).startsWith("HTTP/1.1 200")
			.containsIgnoringCase("Content-Type: application/json")
			.doesNotContain("problem+json", "internal_error", "\"requestId\"");
		assertThat(received.length).isGreaterThan(Endpoints.PARTIAL.length);
	}

	private static void assertProblem(HttpResponse<String> response, int status, String code) {
		assertThat(response.statusCode()).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
				(type) -> assertThat(type).startsWith("application/problem+json"));
		assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
		JsonNode body = json(response);
		assertThat(body.path("status").asInt()).isEqualTo(status);
		assertThat(body.path("code").asString()).isEqualTo(code);
		String id = response.headers().firstValue(RequestIds.HEADER).orElseThrow();
		assertThat(body.path("requestId").asString()).isEqualTo(id);
	}

	private static JsonNode json(HttpResponse<String> response) {
		return JSON.readTree(response.body());
	}

	private static Set<String> fieldNames(HttpResponse<String> response) {
		return new TreeSet<>(json(response).propertyNames());
	}

	private static List<String> unhandledLines(CapturedOutput output, String id) {
		return output.getAll().lines().filter((line) -> line.contains("unhandled exception requestId=" + id)).toList();
	}

	private static byte[] readUntilClosed(InputStream in) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		byte[] buffer = new byte[8192];
		try {
			for (int n; (n = in.read(buffer)) != -1;) {
				bytes.write(buffer, 0, n);
			}
		}
		catch (SocketException ex) {
			// 서버가 연결을 바로 닫으면 reset으로 끝날 수 있다. 그때까지 받은 것을 본다.
		}
		return bytes.toByteArray();
	}

	private HttpResponse<String> get(String path, String session, String accept)
			throws IOException, InterruptedException {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).header("Accept", accept).GET();
		if (session != null) {
			request.header("Cookie", "ASTRA_SESSION=" + session);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

	private URI uri(String path) {
		return URI.create("http://127.0.0.1:" + this.port + path);
	}

	@TestConfiguration
	@RestController
	static class Endpoints {

		static final byte[] PARTIAL = ("[" + " ".repeat(16 * 1024 - 1)).getBytes(StandardCharsets.US_ASCII);

		@GetMapping("/api/test-only/state")
		String state() {
			throw new IllegalStateException("SECRET key=abc");
		}

		@GetMapping("/api/test-only/export-fails-midway")
		void exportFailsMidway(HttpServletResponse response) throws IOException {
			response.setContentType("application/json");
			response.getOutputStream().write(PARTIAL);
			response.flushBuffer();
			throw new IOException("storage read timed out");
		}

	}

	/**
	 * security filter chain을 지난 뒤, 컨트롤러에 닿기 전에 실패하는 filter. 로그인한 사용자의 요청이 filter 단계에서 DB 장애를 만난 것과
	 * 같다. 오류 dispatch는 로그인 정보 없이 /error로 가므로 /error가 permitAll이 아니면 401로 바뀐다.
	 */
	@TestConfiguration
	@Order(Ordered.LOWEST_PRECEDENCE)
	static class FailingFilter extends OncePerRequestFilter {

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			if ("/api/test-only/filter-boom".equals(request.getRequestURI())) {
				throw new DataAccessResourceFailureException("SECRET jdbc:mysql://db:3306/astra connection refused");
			}
			chain.doFilter(request, response);
		}

	}

}
