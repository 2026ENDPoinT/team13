package com.stackcorp.astra.integration.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.stackcorp.astra.summary.api.LlmContent;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.api.LlmException.Code;
import com.stackcorp.astra.summary.api.LlmMessage;
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.api.LlmRequest.Purpose;
import com.stackcorp.astra.summary.api.LlmResponse;
import com.stackcorp.astra.summary.api.LlmResponse.StopReason;
import com.stackcorp.astra.summary.api.LlmTool;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class OpenAiCompatibleLlmClientTests {

	private static final String KEY = "sk-test-secret";

	private static final String COMPLETION = """
			{"choices":[{"message":{"role":"assistant","content":"{\\"slides\\":[]}"},"finish_reason":"stop"}],
			 "usage":{"prompt_tokens":11,"completion_tokens":7}}""";

	private static final LlmRequest REQUEST = new LlmRequest(Purpose.PAGES, "system text",
			List.of(new LlmMessage(LlmMessage.Role.USER,
					List.of(new LlmContent.Text("page text"),
							new LlmContent.Image("image/jpeg", "jpg".getBytes(StandardCharsets.UTF_8))))),
			1234);

	private HttpServer server;

	@BeforeEach
	void startServer() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		this.server.start();
	}

	@AfterEach
	void stopServer() {
		this.server.stop(0);
	}

	@Test
	void sendsChatCompletionAndMapsResponse() {
		AtomicReference<String> authorization = new AtomicReference<>();
		AtomicReference<JsonNode> sent = new AtomicReference<>();
		handle("/v1/chat/completions", (exchange) -> {
			authorization.set(exchange.getRequestHeaders().getFirst("authorization"));
			sent.set(JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes()));
			respond(exchange, 200, COMPLETION);
		});

		LlmResponse response = client(properties("/v1", Map.of("thinking", Map.of("type", "disabled"))))
			.complete(REQUEST);

		assertThat(authorization).hasValue("Bearer " + KEY);
		JsonNode body = sent.get();
		assertThat(body.path("model").stringValue()).isEqualTo("test-model");
		assertThat(body.path("stream").booleanValue()).isFalse();
		assertThat(body.path("max_tokens").intValue()).isEqualTo(1234);
		assertThat(body.path("thinking").path("type").stringValue()).isEqualTo("disabled");
		assertThat(body.path("messages").path(0).path("role").stringValue()).isEqualTo("system");
		assertThat(body.path("messages").path(0).path("content").stringValue()).isEqualTo("system text");
		JsonNode parts = body.path("messages").path(1).path("content");
		assertThat(parts.path(0).path("text").stringValue()).isEqualTo("page text");
		assertThat(parts.path(1).path("image_url").path("url").stringValue()).isEqualTo("data:image/jpeg;base64,anBn");
		assertThat(response.text()).isEqualTo("{\"slides\":[]}");
		assertThat(response.stopReason()).isEqualTo(StopReason.END);
		assertThat(response.usage()).isEqualTo(new LlmResponse.Usage(11, 7));
	}

	@Test
	void sendsToolsWithToolTurnsAndMapsRequestedToolCalls() {
		AtomicReference<JsonNode> sent = new AtomicReference<>();
		handle("/chat/completions", (exchange) -> {
			sent.set(JsonMapper.builder().build().readTree(exchange.getRequestBody().readAllBytes()));
			respond(exchange, 200, """
					{"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"call_1","type":"function",
					 "function":{"name":"read_page","arguments":"{\\"page\\":2}"}}]},"finish_reason":"tool_calls"}],
					 "usage":{"prompt_tokens":5,"completion_tokens":3}}""");
		});
		LlmRequest request = new LlmRequest(Purpose.CHAT, "system text",
				List.of(new LlmMessage(LlmMessage.Role.USER, List.of(new LlmContent.Text("question"))),
						new LlmMessage(LlmMessage.Role.ASSISTANT,
								List.of(new LlmContent.ToolCall("call_0", "search_source", "{\"query\":\"x\"}"))),
						new LlmMessage(LlmMessage.Role.TOOL,
								List.of(new LlmContent.ToolResult("call_0", "result text", false)))),
				100, List.of(new LlmTool("read_page", "read one page", Map.of("type", "object"))));

		LlmResponse response = client(properties("", Map.of())).complete(request);

		JsonNode body = sent.get();
		assertThat(body.path("tools").path(0).path("type").stringValue()).isEqualTo("function");
		assertThat(body.path("tools").path(0).path("function").path("name").stringValue()).isEqualTo("read_page");
		assertThat(body.path("tools").path(0).path("function").path("parameters").path("type").stringValue())
			.isEqualTo("object");
		JsonNode assistant = body.path("messages").path(2);
		assertThat(assistant.path("role").stringValue()).isEqualTo("assistant");
		assertThat(assistant.path("content").isNull()).isTrue();
		assertThat(assistant.path("tool_calls").path(0).path("id").stringValue()).isEqualTo("call_0");
		assertThat(assistant.path("tool_calls").path(0).path("function").path("arguments").stringValue())
			.isEqualTo("{\"query\":\"x\"}");
		JsonNode tool = body.path("messages").path(3);
		assertThat(tool.path("role").stringValue()).isEqualTo("tool");
		assertThat(tool.path("tool_call_id").stringValue()).isEqualTo("call_0");
		assertThat(tool.path("content").stringValue()).isEqualTo("result text");
		assertThat(response.stopReason()).isEqualTo(StopReason.TOOL_USE);
		assertThat(response.text()).isEmpty();
		assertThat(response.toolCalls()).containsExactly(new LlmContent.ToolCall("call_1", "read_page", "{\"page\":2}"));
		assertThat(client(properties("", Map.of())).body(REQUEST)).doesNotContainKey("tools");
	}

	@Test
	void reportsTruncatedOutputAsMaxTokens() {
		handle("/chat/completions", (exchange) -> respond(exchange, 200,
				"{\"choices\":[{\"message\":{\"content\":\"{\"},\"finish_reason\":\"length\"}]}"));

		LlmResponse response = client(properties("", Map.of())).complete(REQUEST);

		assertThat(response.stopReason()).isEqualTo(StopReason.MAX_TOKENS);
		assertThat(response.usage()).isEqualTo(new LlmResponse.Usage(0, 0));
	}

	@Test
	void classifiesHttpFailuresAndHidesKey() {
		AtomicInteger status = new AtomicInteger();
		handle("/chat/completions", (exchange) -> respond(exchange, status.get(),
				"{\"error\":{\"message\":\"bad key " + KEY + " given\"}}"));
		OpenAiCompatibleLlmClient client = client(properties("", Map.of()));

		Map<Integer, Code> expected = Map.of(401, Code.AUTH, 403, Code.AUTH, 429, Code.RATE_LIMIT, 500, Code.SERVER,
				503, Code.SERVER, 400, Code.BAD_REQUEST);
		expected.forEach((code, expectedCode) -> {
			status.set(code);
			LlmException ex = catchThrowableOfType(LlmException.class, () -> client.complete(REQUEST));
			assertThat(ex.code()).as("HTTP %s", code).isEqualTo(expectedCode);
			assertThat(ex.getMessage()).contains("bad key [redacted] given").doesNotContain(KEY);
		});
		assertThat(new LlmException(Code.AUTH, "x").retryable()).isFalse();
		assertThat(new LlmException(Code.RATE_LIMIT, "x").retryable()).isTrue();
	}

	@Test
	void doesNotFollowRedirects() {
		AtomicInteger redirected = new AtomicInteger();
		handle("/elsewhere", (exchange) -> {
			redirected.incrementAndGet();
			respond(exchange, 200, COMPLETION);
		});
		handle("/chat/completions", (exchange) -> {
			exchange.getResponseHeaders().add("location", "/elsewhere");
			respond(exchange, 307, "");
		});

		LlmException ex = catchThrowableOfType(LlmException.class,
				() -> client(properties("", Map.of())).complete(REQUEST));

		assertThat(ex.code()).isEqualTo(Code.PROTOCOL);
		assertThat(redirected).hasValue(0);
	}

	@Test
	void rejectsResponseLargerThanLimit() {
		handle("/chat/completions", (exchange) -> respond(exchange, 200, COMPLETION + " ".repeat(4096)));

		LlmException ex = catchThrowableOfType(LlmException.class,
				() -> client(new LlmProperties(baseUrl(""), "test-model", KEY, true, Duration.ofSeconds(5), 1,
						DataSize.ofBytes(1024), Map.of()))
					.complete(REQUEST));

		assertThat(ex.code()).isEqualTo(Code.PROTOCOL);
	}

	@Test
	void givesUpWhenProviderExceedsTimeout() {
		handle("/chat/completions", (exchange) -> {
			try {
				Thread.sleep(1000);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			respond(exchange, 200, COMPLETION);
		});

		LlmException ex = catchThrowableOfType(LlmException.class,
				() -> client(new LlmProperties(baseUrl(""), "test-model", KEY, true, Duration.ofMillis(200), 1,
						DataSize.ofMegabytes(1), Map.of()))
					.complete(REQUEST));

		assertThat(ex.code()).isEqualTo(Code.NETWORK);
		assertThat(ex.retryable()).isTrue();
	}

	@Test
	void usesMaxCompletionTokensForOpenAi() {
		OpenAiCompatibleLlmClient client = client(new LlmProperties(URI.create("https://api.openai.com/v1"), "gpt",
				KEY, true, Duration.ofSeconds(5), 1, DataSize.ofMegabytes(1), Map.of()));

		assertThat(client.body(REQUEST)).containsEntry("max_completion_tokens", 1234)
			.doesNotContainKeys("max_tokens", "temperature");
	}

	@Test
	void rejectsBaseUrlThatCouldLeakKey() {
		for (String url : List.of("http://example.com/v1", "https://user:pw@example.com", "https://example.com/v1?x=1",
				"https://example.com/v1#frag")) {
			assertThatIllegalArgumentException().as(url)
				.isThrownBy(() -> new LlmProperties(URI.create(url), "m", KEY, true, Duration.ofSeconds(5), 1,
						DataSize.ofMegabytes(1), Map.of()));
		}
	}

	private OpenAiCompatibleLlmClient client(LlmProperties properties) {
		return new OpenAiCompatibleLlmClient(properties);
	}

	private LlmProperties properties(String basePath, Map<String, Object> extraBody) {
		return new LlmProperties(baseUrl(basePath), "test-model", KEY, true, Duration.ofSeconds(5), 1,
				DataSize.ofMegabytes(1), extraBody);
	}

	private URI baseUrl(String basePath) {
		return URI.create("http://127.0.0.1:" + this.server.getAddress().getPort() + basePath);
	}

	private void handle(String path, HttpHandler handler) {
		this.server.createContext(path, handler);
	}

	private static void respond(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("content-type", "application/json");
		exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			exchange.getResponseBody().write(bytes);
		}
		exchange.close();
	}

}
