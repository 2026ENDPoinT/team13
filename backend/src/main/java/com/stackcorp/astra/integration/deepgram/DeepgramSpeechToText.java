package com.stackcorp.astra.integration.deepgram;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.stackcorp.astra.files.api.SpeechToText;
import com.stackcorp.astra.files.api.SpeechToTextException;
import com.stackcorp.astra.files.api.SpeechToTextException.Code;

import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deepgram 사전 녹음 API({@code POST /v1/listen})로 녹음을 전사한다(ADR-0041). 원본은 저장소에서 연 stream을 그대로 요청 본문으로
 * 보낸다. 응답은 tree로 읽지 않고 streaming parser로 {@code metadata.duration}과 {@code results.utterances[].start/transcript}만
 * 뽑는다. 단어별 정보({@code words})는 건너뛴다. 제한 시간이 지나면 응답 stream을 닫아 끊는다. redirect는 따르지 않는다.
 * <p>
 * 요청 option(그 이유는 ADR-0041): {@code model}, {@code language}(기본 multi)와 multi일 때 {@code language_hint}, 발화 단위로
 * 시각을 받는 {@code utterances=true}, 문장 부호와 숫자 표기 {@code punctuate=true&smart_format=true}, 모델 개선 프로그램에서
 * 빼는 {@code mip_opt_out=true}(설정으로 끌 수 없다). key는 {@code Authorization: Token} header로만 보내고 로그와 예외 문구에
 * 넣지 않는다. 오류 응답 본문은 4 KB까지 {@code err_code}만 읽어 분류에 쓰고 남기지 않는다.
 */
final class DeepgramSpeechToText implements SpeechToText, AutoCloseable {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final int ERROR_BODY_BYTES = 4000;

	/** 발화 하나의 글 상한. 넘으면 응답이 비정상이다. */
	private static final int MAX_UTTERANCE_CHARS = 20_000;

	private static final int MAX_UTTERANCES = 100_000;

	private final DeepgramProperties properties;

	private final URI endpoint;

	private final HttpClient http;

	private final ScheduledExecutorService watchdog;

	DeepgramSpeechToText(DeepgramProperties properties) {
		this.properties = properties;
		this.endpoint = URI.create(base(properties.url()) + "/v1/listen?" + query(properties));
		this.http = HttpClient.newBuilder()
			.version(HttpClient.Version.HTTP_1_1)
			.followRedirects(HttpClient.Redirect.NEVER)
			.connectTimeout(Duration.ofSeconds(10))
			.build();
		this.watchdog = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().factory());
	}

	/** 요청 query. 녹음마다 같다. */
	static String query(DeepgramProperties properties) {
		StringBuilder query = new StringBuilder();
		query.append("model=").append(encode(properties.model()));
		query.append("&language=").append(encode(properties.language()));
		if ("multi".equals(properties.language())) {
			for (String hint : properties.languageHints()) {
				query.append("&language_hint=").append(encode(hint));
			}
		}
		query.append("&punctuate=true&smart_format=true&utterances=true&mip_opt_out=true");
		return query.toString();
	}

	@Override
	public boolean available() {
		return this.properties.configured();
	}

	@Override
	public Transcript transcribe(Audio audio) {
		if (!available()) {
			throw new SpeechToTextException(Code.CONFIG, "Deepgram key가 설정되지 않았습니다.");
		}
		HttpRequest request = HttpRequest.newBuilder(this.endpoint)
			.header("authorization", "Token " + this.properties.apiKey())
			.header("content-type", audio.mimeType())
			.header("accept", "application/json")
			.timeout(this.properties.timeout())
			.POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofInputStream(() -> {
				try {
					return audio.opener().open();
				}
				catch (IOException ex) {
					throw new UncheckedIOException(ex);
				}
			}), audio.sizeBytes()))
			.build();
		HttpResponse<InputStream> response;
		try {
			response = this.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
		}
		catch (ConnectException ex) {
			throw new SpeechToTextException(Code.NETWORK, "Deepgram에 연결하지 못했습니다.", ex);
		}
		catch (HttpTimeoutException ex) {
			throw new SpeechToTextException(Code.NETWORK, "Deepgram이 제한 시간 안에 응답하지 않았습니다.", ex);
		}
		catch (IOException | UncheckedIOException ex) {
			throw new SpeechToTextException(Code.NETWORK, "Deepgram 호출이 실패했습니다.", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new SpeechToTextException(Code.NETWORK, "Deepgram 응답을 기다리다 중단되었습니다.", ex);
		}
		try (InputStream body = response.body()) {
			if (response.statusCode() != 200) {
				throw failure(response.statusCode(), body);
			}
			AtomicBoolean expired = new AtomicBoolean();
			ScheduledFuture<?> deadline = this.watchdog.schedule(() -> {
				expired.set(true);
				closeQuietly(body);
			}, this.properties.timeout().toMillis(), TimeUnit.MILLISECONDS);
			try {
				return parse(new Bounded(body, this.properties.maxResponseBytes().toBytes()));
			}
			catch (JacksonException | IOException | UncheckedIOException ex) {
				throw new SpeechToTextException(expired.get() ? Code.NETWORK : Code.PROTOCOL,
						expired.get() ? "Deepgram 응답이 제한 시간을 넘었습니다." : "Deepgram 응답을 해석하지 못했습니다.", ex);
			}
			finally {
				deadline.cancel(false);
			}
		}
		catch (IOException ex) {
			throw new SpeechToTextException(Code.NETWORK, "Deepgram 응답을 닫지 못했습니다.", ex);
		}
	}

	@Override
	public void close() {
		this.watchdog.shutdownNow();
		this.http.close();
	}

	/** 응답 JSON에서 길이와 발화만 읽는다. 다른 값은 건너뛴다. */
	static Transcript parse(InputStream body) throws IOException {
		Double duration = null;
		List<Utterance> utterances = null;
		try (JsonParser parser = JSON.createParser(body)) {
			if (parser.nextToken() != JsonToken.START_OBJECT) {
				throw protocol("응답이 JSON 객체가 아닙니다.");
			}
			while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
				String name = parser.currentName();
				JsonToken value = parser.nextToken();
				if ("metadata".equals(name) && value == JsonToken.START_OBJECT) {
					duration = metadataDuration(parser);
				}
				else if ("results".equals(name) && value == JsonToken.START_OBJECT) {
					utterances = results(parser);
				}
				else {
					parser.skipChildren();
				}
			}
		}
		if (utterances == null) {
			throw protocol("응답에 results.utterances가 없습니다.");
		}
		return new Transcript(duration, utterances);
	}

	private static Double metadataDuration(JsonParser parser) {
		Double duration = null;
		while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
			String name = parser.currentName();
			JsonToken value = parser.nextToken();
			if ("duration".equals(name) && value.isNumeric()) {
				double seconds = parser.getDoubleValue();
				duration = Double.isFinite(seconds) && seconds >= 0 ? seconds : null;
			}
			else {
				parser.skipChildren();
			}
		}
		return duration;
	}

	private static List<Utterance> results(JsonParser parser) {
		List<Utterance> utterances = null;
		while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
			String name = parser.currentName();
			JsonToken value = parser.nextToken();
			if ("utterances".equals(name) && value == JsonToken.START_ARRAY) {
				utterances = new ArrayList<>();
				while (parser.nextToken() == JsonToken.START_OBJECT) {
					if (utterances.size() >= MAX_UTTERANCES) {
						throw protocol("발화 수가 상한을 넘었습니다.");
					}
					Utterance utterance = utterance(parser);
					if (utterance != null) {
						utterances.add(utterance);
					}
				}
			}
			else {
				parser.skipChildren();
			}
		}
		return utterances;
	}

	// 시각이나 글이 없는 발화는 버린다.
	private static Utterance utterance(JsonParser parser) {
		Double start = null;
		String text = null;
		while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
			String name = parser.currentName();
			JsonToken value = parser.nextToken();
			if ("start".equals(name) && value.isNumeric()) {
				start = parser.getDoubleValue();
			}
			else if ("transcript".equals(name) && value == JsonToken.VALUE_STRING) {
				text = parser.getString();
				if (text.length() > MAX_UTTERANCE_CHARS) {
					throw protocol("발화 글이 상한을 넘었습니다.");
				}
			}
			else {
				parser.skipChildren();
			}
		}
		return start == null || text == null || !Double.isFinite(start) ? null : new Utterance(start, text);
	}

	private static SpeechToTextException failure(int status, InputStream body) throws IOException {
		String errCode = "";
		byte[] bytes = body.readNBytes(ERROR_BODY_BYTES);
		try {
			errCode = JSON.readTree(bytes).path("err_code").asString("");
		}
		catch (JacksonException ex) {
			// 본문이 JSON이 아니면 상태 코드만 쓴다.
		}
		Code code = switch (status) {
			// 잘못된 query(모델, 언어 이름)는 설정 오류이고, 그 밖의 400은 오디오를 읽지 못한 것이다.
			case 400 -> "INVALID_QUERY_PARAMETER".equals(errCode) ? Code.CONFIG : Code.INVALID_AUDIO;
			case 401, 402, 403, 404, 405 -> Code.CONFIG;
			case 408, 429 -> Code.RATE_LIMIT;
			default -> status >= 500 ? Code.SERVER : Code.CONFIG;
		};
		return new SpeechToTextException(code, "Deepgram이 전사하지 못했습니다 (HTTP " + status + ")");
	}

	private static SpeechToTextException protocol(String message) {
		return new SpeechToTextException(Code.PROTOCOL, message);
	}

	private static String base(URI url) {
		String base = url.toString();
		return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static void closeQuietly(InputStream in) {
		try {
			in.close();
		}
		catch (IOException ex) {
			// 제한 시간으로 끊는 중이다.
		}
	}

	/** 읽은 바이트가 상한을 넘으면 실패한다. */
	private static final class Bounded extends FilterInputStream {

		private final long max;

		private long read;

		Bounded(InputStream in, long max) {
			super(in);
			this.max = max;
		}

		@Override
		public int read() throws IOException {
			int b = super.read();
			if (b >= 0) {
				count(1);
			}
			return b;
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			int n = super.read(buffer, offset, length);
			if (n > 0) {
				count(n);
			}
			return n;
		}

		private void count(long n) {
			this.read += n;
			if (this.read > this.max) {
				throw protocol("응답 본문이 크기 상한을 넘었습니다.");
			}
		}

	}

}
