package com.stackcorp.astra.integration.llm;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import com.stackcorp.astra.summary.api.LlmClient;
import com.stackcorp.astra.summary.api.LlmContent;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.api.LlmException.Code;
import com.stackcorp.astra.summary.api.LlmMessage;
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.api.LlmResponse;
import com.stackcorp.astra.summary.api.LlmResponse.StopReason;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * OpenAI Chat Completions 호환 제공자를 부르는 adapter. 요청을 한 번 보내고 재시도하지 않는다.
 * 설정한 주소 하나만 부르고 redirect를 따르지 않는다. 응답 크기와 전체 시간, 동시 요청 수에 상한을 둔다(SC-08).
 */
final class OpenAiCompatibleLlmClient implements LlmClient {

	private static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final double TEMPERATURE = 0.3;

	private static final int ERROR_BODY_BYTES = 4000;

	private final LlmProperties properties;

	private final URI endpoint;

	// api.openai.com의 새 모델은 max_tokens와 temperature를 거부한다. 다른 호환 서버는 대부분 max_tokens만 안다.
	private final boolean official;

	private final HttpClient http;

	private final Semaphore permits;

	OpenAiCompatibleLlmClient(LlmProperties properties) {
		if (properties.apiKey() == null || properties.apiKey().isBlank()) {
			throw new IllegalArgumentException("astra.llm.api-key가 비어 있습니다.");
		}
		this.properties = properties;
		String base = properties.baseUrl().toString();
		this.endpoint = URI.create((base.endsWith("/") ? base.substring(0, base.length() - 1) : base)
				+ "/chat/completions");
		this.official = "api.openai.com".equalsIgnoreCase(properties.baseUrl().getHost());
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		this.permits = new Semaphore(properties.maxConcurrency());
	}

	@Override
	public String model() {
		return this.properties.model();
	}

	@Override
	public boolean acceptsImages() {
		return this.properties.vision();
	}

	@Override
	public LlmResponse complete(LlmRequest request) {
		HttpRequest httpRequest = HttpRequest.newBuilder(this.endpoint)
			.header("content-type", "application/json")
			.header("authorization", "Bearer " + this.properties.apiKey())
			.POST(HttpRequest.BodyPublishers.ofByteArray(MAPPER.writeValueAsBytes(body(request))))
			.build();
		HttpResponse<byte[]> response = send(httpRequest, request.timeout());
		int status = response.statusCode();
		if (status < 200 || status >= 300) {
			throw httpFailure(status, response.body());
		}
		return parse(response.body());
	}

	Map<String, Object> body(LlmRequest request) {
		Map<String, Object> body = new LinkedHashMap<>(this.properties.extraBody());
		body.put("model", this.properties.model());
		List<Map<String, Object>> messages = new ArrayList<>();
		messages.add(Map.of("role", "system", "content", request.system()));
		for (LlmMessage message : request.messages()) {
			switch (message.role()) {
				case USER -> messages
					.add(Map.of("role", "user", "content", message.content().stream().map(this::part).toList()));
				case ASSISTANT -> messages.add(assistant(message));
				case TOOL -> message.content().forEach((c) -> messages.add(toolResult(c)));
			}
		}
		body.put("messages", messages);
		if (!request.tools().isEmpty()) {
			body.put("tools",
					request.tools()
						.stream()
						.map((t) -> Map.of("type", "function", "function",
								Map.of("name", t.name(), "description", t.description(), "parameters", t.parameters())))
						.toList());
		}
		if (request.jsonObject()) {
			body.put("response_format", Map.of("type", "json_object"));
		}
		if (request.reasoningEffort() != null) {
			body.put("reasoning_effort", request.reasoningEffort());
		}
		body.put("stream", false);
		if (this.official) {
			body.put("max_completion_tokens", request.maxOutputTokens());
		}
		else {
			body.put("max_tokens", request.maxOutputTokens());
			body.put("temperature", request.temperature() != null ? request.temperature() : TEMPERATURE);
		}
		return body;
	}

	// 도구를 부른 assistant 차례는 content가 비어 있을 수 있다. Chat Completions는 이때 null을 받는다.
	private Map<String, Object> assistant(LlmMessage message) {
		String text = message.content()
			.stream()
			.filter(LlmContent.Text.class::isInstance)
			.map((c) -> ((LlmContent.Text) c).text())
			.collect(Collectors.joining("\n"));
		List<Map<String, Object>> calls = message.content()
			.stream()
			.filter(LlmContent.ToolCall.class::isInstance)
			.map((c) -> (LlmContent.ToolCall) c)
			.map(OpenAiCompatibleLlmClient::toolCall)
			.toList();
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("role", "assistant");
		out.put("content", text.isEmpty() && !calls.isEmpty() ? null : text);
		if (!calls.isEmpty()) {
			out.put("tool_calls", calls);
		}
		return out;
	}

	// 제공자가 호출에 붙인 extra_content는 받은 그대로 돌려보낸다. Gemini는 thought_signature가 빠지면 400을 낸다.
	private static Map<String, Object> toolCall(LlmContent.ToolCall call) {
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("id", call.id());
		out.put("type", "function");
		out.put("function", Map.of("name", call.name(), "arguments", call.arguments()));
		if (call.extraContent() != null) {
			out.put("extra_content", MAPPER.readTree(call.extraContent()));
		}
		return out;
	}

	private Map<String, Object> toolResult(LlmContent content) {
		if (!(content instanceof LlmContent.ToolResult result)) {
			throw new IllegalArgumentException("tool message에는 도구 결과만 넣습니다.");
		}
		return Map.of("role", "tool", "tool_call_id", result.callId(), "content", result.text());
	}

	private Map<String, Object> part(LlmContent content) {
		return switch (content) {
			case LlmContent.Text text -> Map.of("type", "text", "text", text.text());
			case LlmContent.Image image -> Map.of("type", "image_url", "image_url", Map.of("url",
					"data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.data())));
			case LlmContent.ToolCall call -> throw new IllegalArgumentException("user message에는 도구 호출을 넣지 않습니다.");
			case LlmContent.ToolResult result ->
				throw new IllegalArgumentException("user message에는 도구 결과를 넣지 않습니다.");
		};
	}

	// 요청의 timeout이 있으면 동시 요청 자리를 기다리는 시간까지 그 안에 센다. 자리를 못 얻고 시간이 다 되면 제한 시간 초과와 같은
	// 실패다. 없으면 자리를 얻을 때까지 기다리고, 설정한 timeout은 보낸 뒤부터 센다.
	private HttpResponse<byte[]> send(HttpRequest request, Duration requestTimeout) {
		long started = System.nanoTime();
		try {
			if (requestTimeout == null) {
				this.permits.acquire();
			}
			else if (!this.permits.tryAcquire(requestTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
				throw new LlmException(Code.NETWORK, "동시 요청 자리를 제한 시간 안에 얻지 못했습니다.", new TimeoutException());
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new LlmException(Code.NETWORK, "모델 호출을 기다리다 중단되었습니다.", ex);
		}
		long wait = requestTimeout == null ? this.properties.timeout().toNanos()
				: Math.max(0, requestTimeout.toNanos() - (System.nanoTime() - started));
		long max = this.properties.maxResponseBytes().toBytes();
		CompletableFuture<HttpResponse<byte[]>> future = this.http.sendAsync(request,
				(info) -> new CappedBody(info.statusCode() / 100 == 2 ? max : ERROR_BODY_BYTES,
						info.statusCode() / 100 == 2));
		try {
			return future.get(wait, TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException ex) {
			future.cancel(true);
			Thread.currentThread().interrupt();
			throw new LlmException(Code.NETWORK, "모델 호출이 중단되었습니다.", ex);
		}
		catch (TimeoutException ex) {
			future.cancel(true);
			throw new LlmException(Code.NETWORK, "제공자가 제한 시간 안에 응답하지 않았습니다.", ex);
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof ResponseTooLarge) {
				throw new LlmException(Code.PROTOCOL, "제공자 응답이 크기 상한(" + max + " bytes)을 넘었습니다.", ex);
			}
			throw new LlmException(Code.NETWORK, "제공자에 연결하지 못했습니다: " + clean(String.valueOf(ex.getCause()), 200), ex);
		}
		finally {
			this.permits.release();
		}
	}

	private LlmResponse parse(byte[] bytes) {
		JsonNode root;
		try {
			root = MAPPER.readTree(bytes);
		}
		catch (JacksonException ex) {
			throw new LlmException(Code.PROTOCOL, "제공자 응답이 JSON이 아닙니다.", ex);
		}
		JsonNode choice = root.path("choices").path(0);
		JsonNode message = choice.path("message");
		List<LlmContent.ToolCall> calls = toolCalls(message.path("tool_calls"));
		JsonNode content = message.path("content");
		if (!content.isString() && calls.isEmpty()) {
			throw new LlmException(Code.PROTOCOL, "제공자 응답에 message content가 없습니다.");
		}
		StopReason stopReason = switch (choice.path("finish_reason").asString("")) {
			case "stop" -> StopReason.END;
			case "length" -> StopReason.MAX_TOKENS;
			case "tool_calls" -> StopReason.TOOL_USE;
			default -> StopReason.OTHER;
		};
		JsonNode usage = root.path("usage");
		return new LlmResponse(content.isString() ? content.stringValue() : "", stopReason,
				new LlmResponse.Usage(usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0)),
				calls);
	}

	// 인자는 문자열 그대로 넘긴다. 해석과 검사는 도구를 실행하는 쪽이 한다.
	private static List<LlmContent.ToolCall> toolCalls(JsonNode array) {
		List<LlmContent.ToolCall> calls = new ArrayList<>();
		for (int i = 0; i < array.size(); i++) {
			JsonNode call = array.path(i);
			JsonNode function = call.path("function");
			if (!call.path("id").isString() || !function.path("name").isString()) {
				throw new LlmException(Code.PROTOCOL, "제공자 응답의 도구 호출에 id나 이름이 없습니다.");
			}
			JsonNode arguments = function.path("arguments");
			JsonNode extra = call.path("extra_content");
			calls.add(new LlmContent.ToolCall(call.path("id").stringValue(), function.path("name").stringValue(),
					arguments.isString() ? arguments.stringValue() : arguments.isMissingNode() ? "{}" : arguments.toString(),
					extra.isObject() ? extra.toString() : null));
		}
		return calls;
	}

	private LlmException httpFailure(int status, byte[] bytes) {
		String detail = new String(bytes, StandardCharsets.UTF_8);
		try {
			JsonNode root = MAPPER.readTree(bytes);
			JsonNode error = root.path("error");
			if (error.isString()) {
				detail = error.stringValue();
			}
			else if (error.path("message").isString()) {
				detail = error.path("message").stringValue();
			}
			else if (root.path("message").isString()) {
				detail = root.path("message").stringValue();
			}
		}
		catch (JacksonException ex) {
			// JSON이 아니면 본문 앞부분을 그대로 쓴다.
		}
		detail = clean(detail, 300);
		String suffix = " (HTTP " + status + ")" + (detail.isEmpty() ? "" : ": " + detail);
		if (status / 100 == 3) {
			return new LlmException(Code.PROTOCOL, "제공자가 redirect를 돌려주었습니다. redirect는 따르지 않습니다" + suffix);
		}
		if (status == 401 || status == 403) {
			return new LlmException(Code.AUTH, "제공자가 API key를 거부했습니다" + suffix);
		}
		if (status == 429) {
			return new LlmException(Code.RATE_LIMIT, "제공자의 요청 한도에 걸렸습니다" + suffix);
		}
		if (status >= 500) {
			return new LlmException(Code.SERVER, "제공자 서버 오류입니다" + suffix);
		}
		return new LlmException(Code.BAD_REQUEST, "제공자가 요청을 거부했습니다" + suffix);
	}

	// 제공자가 돌려준 문구에서 key를 지우고 한 줄로 줄인다. 지운 뒤에 자른다.
	private String clean(String text, int max) {
		String cleaned = text.replace(this.properties.apiKey(), "[redacted]").replaceAll("\\s+", " ").strip();
		return cleaned.length() > max ? cleaned.substring(0, max) : cleaned;
	}

	private static final class ResponseTooLarge extends RuntimeException {

	}

	/**
	 * 본문을 상한까지만 모은다. 성공 응답이 상한을 넘으면 실패시키고, 오류 응답은 앞부분만 남긴다.
	 */
	private static final class CappedBody implements HttpResponse.BodySubscriber<byte[]> {

		private final long max;

		private final boolean failWhenExceeded;

		private final CompletableFuture<byte[]> result = new CompletableFuture<>();

		private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		private Flow.Subscription subscription;

		CappedBody(long max, boolean failWhenExceeded) {
			this.max = max;
			this.failWhenExceeded = failWhenExceeded;
		}

		@Override
		public CompletionStage<byte[]> getBody() {
			return this.result;
		}

		@Override
		public void onSubscribe(Flow.Subscription subscription) {
			this.subscription = subscription;
			subscription.request(Long.MAX_VALUE);
		}

		@Override
		public void onNext(List<ByteBuffer> items) {
			for (ByteBuffer item : items) {
				long room = this.max - this.buffer.size();
				if (item.remaining() > room) {
					if (this.failWhenExceeded) {
						this.subscription.cancel();
						this.result.completeExceptionally(new ResponseTooLarge());
						return;
					}
					byte[] head = new byte[(int) room];
					item.get(head);
					this.buffer.writeBytes(head);
					this.subscription.cancel();
					this.result.complete(this.buffer.toByteArray());
					return;
				}
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				this.buffer.writeBytes(chunk);
			}
		}

		@Override
		public void onError(Throwable throwable) {
			this.result.completeExceptionally(throwable);
		}

		@Override
		public void onComplete() {
			this.result.complete(this.buffer.toByteArray());
		}

	}

}
