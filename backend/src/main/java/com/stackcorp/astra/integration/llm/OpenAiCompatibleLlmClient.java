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
		HttpResponse<byte[]> response = send(httpRequest);
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
			if (message.role() == LlmMessage.Role.USER) {
				messages.add(Map.of("role", "user", "content", message.content().stream().map(this::part).toList()));
			}
			else {
				messages.add(Map.of("role", "assistant", "content",
						message.content()
							.stream()
							.filter(LlmContent.Text.class::isInstance)
							.map((c) -> ((LlmContent.Text) c).text())
							.collect(Collectors.joining("\n"))));
			}
		}
		body.put("messages", messages);
		body.put("stream", false);
		if (this.official) {
			body.put("max_completion_tokens", request.maxOutputTokens());
		}
		else {
			body.put("max_tokens", request.maxOutputTokens());
			body.put("temperature", TEMPERATURE);
		}
		return body;
	}

	private Map<String, Object> part(LlmContent content) {
		return switch (content) {
			case LlmContent.Text text -> Map.of("type", "text", "text", text.text());
			case LlmContent.Image image -> Map.of("type", "image_url", "image_url", Map.of("url",
					"data:" + image.mediaType() + ";base64," + Base64.getEncoder().encodeToString(image.data())));
		};
	}

	private HttpResponse<byte[]> send(HttpRequest request) {
		try {
			this.permits.acquire();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new LlmException(Code.NETWORK, "모델 호출을 기다리다 중단되었습니다.", ex);
		}
		long max = this.properties.maxResponseBytes().toBytes();
		CompletableFuture<HttpResponse<byte[]>> future = this.http.sendAsync(request,
				(info) -> new CappedBody(info.statusCode() / 100 == 2 ? max : ERROR_BODY_BYTES,
						info.statusCode() / 100 == 2));
		try {
			return future.get(this.properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
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
		JsonNode content = choice.path("message").path("content");
		if (!content.isString()) {
			throw new LlmException(Code.PROTOCOL, "제공자 응답에 message content가 없습니다.");
		}
		StopReason stopReason = switch (choice.path("finish_reason").asString("")) {
			case "stop" -> StopReason.END;
			case "length" -> StopReason.MAX_TOKENS;
			default -> StopReason.OTHER;
		};
		JsonNode usage = root.path("usage");
		return new LlmResponse(content.stringValue(), stopReason,
				new LlmResponse.Usage(usage.path("prompt_tokens").asLong(0), usage.path("completion_tokens").asLong(0)));
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
