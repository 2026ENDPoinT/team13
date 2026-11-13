package com.stackcorp.astra.integration.deepgram;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.stackcorp.astra.files.api.SpeechToText.Audio;
import com.stackcorp.astra.files.api.SpeechToText.Transcript;
import com.stackcorp.astra.files.api.SpeechToTextException;
import com.stackcorp.astra.files.api.SpeechToTextException.Code;
import com.sun.net.httpserver.HttpServer;

/**
 * Deepgram 사전 녹음 요청(ADR-0041): query option, key header, stream 본문, 응답에서 길이와 발화만 읽기, 오류 상태 분류, 설정 검사.
 */
class DeepgramSpeechToTextTests {

	private static final String KEY = "test-key-not-a-secret";

	private HttpServer server;

	private final AtomicReference<String> query = new AtomicReference<>();

	private final AtomicReference<String> authorization = new AtomicReference<>();

	private final AtomicReference<String> contentType = new AtomicReference<>();

	private final AtomicReference<byte[]> body = new AtomicReference<>();

	@AfterEach
	void stop() {
		if (this.server != null) {
			this.server.stop(0);
			this.server = null;
		}
	}

	@Test
	void sendsTheAudioWithFixedOptionsAndReadsOnlyDurationAndUtterances() throws Exception {
		DeepgramSpeechToText client = client(200, """
				{"metadata": {"request_id": "r", "duration": 25.93, "models": ["x"]},
				 "results": {"channels": [{"alternatives": [{"transcript": "전체", "words": [{"word": "a", "start": 0}]}]}],
				   "utterances": [
				     {"start": 0.08, "end": 2.1, "transcript": "안녕하세요 Stack 수업입니다", "words": [{"word": "안녕하세요"}], "channel": 0},
				     {"start": 3.5, "end": 5, "transcript": "", "speaker": 0},
				     {"end": 6, "transcript": "시각 없음"}]}}""");
		byte[] audio = "OggS-audio-bytes".getBytes(StandardCharsets.US_ASCII);
		Transcript transcript = client.transcribe(new Audio("audio/ogg", audio.length, () -> new ByteArrayInputStream(audio)));
		assertThat(transcript.durationSeconds()).isEqualTo(25.93);
		assertThat(transcript.utterances()).hasSize(2);
		assertThat(transcript.utterances().getFirst().start()).isEqualTo(0.08);
		assertThat(transcript.utterances().getFirst().text()).isEqualTo("안녕하세요 Stack 수업입니다");
		assertThat(this.query.get()).isEqualTo("model=nova-3&language=multi&language_hint=ko&language_hint=en"
				+ "&punctuate=true&smart_format=true&utterances=true&mip_opt_out=true");
		assertThat(this.authorization.get()).isEqualTo("Token " + KEY);
		assertThat(this.contentType.get()).isEqualTo("audio/ogg");
		assertThat(this.body.get()).isEqualTo(audio);
		client.close();
	}

	@Test
	void languageHintsAreSentOnlyForMulti() {
		assertThat(DeepgramSpeechToText.query(properties(URI.create("https://api.deepgram.com"), "ko")))
			.isEqualTo("model=nova-3&language=ko&punctuate=true&smart_format=true&utterances=true&mip_opt_out=true");
	}

	@Test
	void errorStatusesAreClassifiedWithoutTheProviderText() throws Exception {
		record Case(int status, String body, Code code) {
		}
		for (Case c : List.of(new Case(400, "{\"err_code\":\"INVALID_QUERY_PARAMETER\",\"err_msg\":\"secret detail\"}", Code.CONFIG),
				new Case(400, "{\"err_code\":\"Bad Request\",\"err_msg\":\"corrupt or unsupported data\"}", Code.INVALID_AUDIO),
				new Case(401, "{}", Code.CONFIG), new Case(402, "not json", Code.CONFIG),
				new Case(429, "{}", Code.RATE_LIMIT), new Case(504, "", Code.SERVER), new Case(200, "{\"metadata\":{}}", Code.PROTOCOL),
				new Case(200, "<html>", Code.PROTOCOL))) {
			DeepgramSpeechToText client = client(c.status(), c.body());
			assertThatThrownBy(() -> client.transcribe(new Audio("audio/webm", 3, () -> new ByteArrayInputStream(new byte[3]))))
				.as(c.toString())
				.isInstanceOfSatisfying(SpeechToTextException.class, (ex) -> {
					assertThat(ex.code()).isEqualTo(c.code());
					assertThat(ex.getMessage()).doesNotContain("secret detail").doesNotContain(KEY);
				});
			assertThat(client.available()).isTrue();
			client.close();
			stop();
		}
	}

	@Test
	void theAudioStreamIsClosedWhenTheProviderAnswersBeforeReadingTheBody() throws Exception {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/v1/listen", (exchange) -> {
			// 본문을 읽지 않고 바로 거절한다(key 거부, 요청 한도 등).
			exchange.sendResponseHeaders(401, -1);
			exchange.close();
		});
		this.server.start();
		DeepgramSpeechToText client = new DeepgramSpeechToText(
				properties(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort()), "multi"));
		long size = 256L * 1024 * 1024;
		Endless audio = new Endless(size);
		assertThatThrownBy(() -> client.transcribe(new Audio("audio/webm", size, () -> audio)))
			.isInstanceOf(SpeechToTextException.class);
		assertThat(audio.closed).isTrue();
		assertThat(audio.read).isLessThan(size);
		client.close();
	}

	@Test
	void anUnreachableProviderIsANetworkFailure() {
		DeepgramSpeechToText client = new DeepgramSpeechToText(properties(URI.create("http://127.0.0.1:1"), "multi"));
		assertThatThrownBy(() -> client.transcribe(new Audio("audio/webm", 1, () -> new ByteArrayInputStream(new byte[1]))))
			.isInstanceOfSatisfying(SpeechToTextException.class, (ex) -> assertThat(ex.retryable()).isTrue());
		client.close();
	}

	@Test
	void settingsAreCheckedAtStartupAndTheKeyNeverPrinted() {
		DeepgramProperties ok = properties(URI.create("https://api.eu.deepgram.com"), "multi");
		assertThat(ok.toString()).doesNotContain(KEY).contains("configured=true");
		assertThat(new DeepgramProperties(null, URI.create("https://api.deepgram.com"), "nova-3", "multi", null,
				Duration.ofMinutes(1), DataSize.ofMegabytes(1)).configured()).isFalse();
		assertThatThrownBy(() -> properties(URI.create("http://api.deepgram.com"), "multi"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(URI.create("https://user@api.deepgram.com"), "multi"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> properties(URI.create("https://api.deepgram.com"), "ko&x=1"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DeepgramProperties(KEY, URI.create("https://api.deepgram.com"), "nova 3", "multi",
				List.of(), Duration.ofMinutes(1), DataSize.ofMegabytes(1))).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new DeepgramProperties(KEY, URI.create("https://api.deepgram.com"), "nova-3", "multi",
				List.of("multi"), Duration.ofMinutes(1), DataSize.ofMegabytes(1))).isInstanceOf(IllegalArgumentException.class);
	}

	private DeepgramSpeechToText client(int status, String response) throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/v1/listen", (exchange) -> {
			this.query.set(exchange.getRequestURI().getRawQuery());
			this.authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
			this.contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
			this.body.set(exchange.getRequestBody().readAllBytes());
			byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		});
		this.server.start();
		return new DeepgramSpeechToText(
				properties(URI.create("http://127.0.0.1:" + this.server.getAddress().getPort()), "multi"));
	}

	private static DeepgramProperties properties(URI url, String language) {
		return new DeepgramProperties(KEY, url, "nova-3", language, List.of("ko", "en"), Duration.ofSeconds(10),
				DataSize.ofMegabytes(1));
	}

	/** 저장소 stream 대신: 끝없이 0을 주고 닫혔는지와 읽힌 바이트 수를 센다. */
	private static final class Endless extends InputStream {

		private final long size;

		volatile long read;

		volatile boolean closed;

		Endless(long size) {
			this.size = size;
		}

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			return read(one, 0, 1) < 0 ? -1 : one[0];
		}

		@Override
		public int read(byte[] buffer, int offset, int length) throws IOException {
			if (this.closed) {
				throw new IOException("closed");
			}
			if (this.read >= this.size) {
				return -1;
			}
			int n = (int) Math.min(length, this.size - this.read);
			java.util.Arrays.fill(buffer, offset, offset + n, (byte) 0);
			this.read += n;
			return n;
		}

		@Override
		public void close() {
			this.closed = true;
		}

	}

}
