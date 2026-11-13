package com.stackcorp.converter;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.stackcorp.astra.TestPdfs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

class ConverterServerTests {

	private final HttpClient client = HttpClient.newHttpClient();

	@Test
	void healthAnswersWhileRenderIsBusyAndRenderStaysSerialized() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger active = new AtomicInteger();
		AtomicInteger maxActive = new AtomicInteger();
		OfficeEngine slow = (document, format) -> {
			maxActive.accumulateAndGet(active.incrementAndGet(), Math::max);
			entered.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			active.decrementAndGet();
			throw new ConversionFailure("convert_failed", "test");
		};
		try (ConverterServer server = ConverterServer.start(new InetSocketAddress("127.0.0.1", 0), 1 << 20, slow)) {
			String base = "http://127.0.0.1:" + server.port();
			CompletableFuture<HttpResponse<String>> first = render(base);
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			CompletableFuture<HttpResponse<String>> second = render(base);
			CompletableFuture<HttpResponse<String>> avatar = post(base + "/avatar");

			assertThat(health(server)).isEqualTo(200);
			assertThat(avatar.isDone()).isFalse();

			release.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(422);
			assertThat(second.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(422);
			assertThat(maxActive.get()).isEqualTo(1);
			assertThat(avatar.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(422);
		}
	}

	@Test
	void rejectsRequestsBeyondTheWaitingLimitWhileHealthStillAnswers() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		OfficeEngine slow = (document, format) -> {
			entered.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			throw new ConversionFailure("convert_failed", "test");
		};
		try (ConverterServer server = ConverterServer.start(new InetSocketAddress("127.0.0.1", 0), 1 << 20, slow)) {
			String base = "http://127.0.0.1:" + server.port();
			CompletableFuture<HttpResponse<String>> first = render(base);
			assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
			List<CompletableFuture<HttpResponse<String>>> extra = new ArrayList<>();
			for (int i = 0; i < ConverterServer.MAX_WAITING + 1; i++) {
				extra.add(render(base));
			}
			HttpResponse<String> rejected = (HttpResponse<String>) CompletableFuture
				.anyOf(extra.toArray(CompletableFuture[]::new))
				.get(5, TimeUnit.SECONDS);
			assertThat(rejected.statusCode()).isEqualTo(503);
			assertThat(rejected.body()).contains("busy");

			assertThat(health(server)).isEqualTo(200);

			release.countDown();
			assertThat(first.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(422);
			long served = 0;
			for (CompletableFuture<HttpResponse<String>> response : extra) {
				if (response.get(10, TimeUnit.SECONDS).statusCode() == 422) {
					served++;
				}
			}
			assertThat(served).isEqualTo(ConverterServer.MAX_WAITING);
		}
	}

	@Test
	void healthAnswersWhileSlowClientsHoldEveryConverterThread() throws Exception {
		List<Socket> slow = new ArrayList<>();
		try (ConverterServer server = ConverterServer.start(new InetSocketAddress("127.0.0.1", 0), 1 << 20)) {
			// header만 보내고 본문을 보내지 않는 연결이 변환 thread를 모두 붙잡는다. 대기 상한을 넘은 요청도 503을 보낸 뒤 남은 본문을
			// 기다리며 thread를 쥔다.
			for (int i = 0; i < ConverterServer.THREADS + 2; i++) {
				Socket socket = new Socket("127.0.0.1", server.port());
				slow.add(socket);
				socket.getOutputStream()
					.write("POST /render HTTP/1.1\r\nHost: converter\r\nContent-Length: 1000\r\n\r\n"
						.getBytes(StandardCharsets.US_ASCII));
			}
			assertThatExceptionOfType(HttpTimeoutException.class).isThrownBy(() -> this.client.send(
					HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/render"))
						.POST(HttpRequest.BodyPublishers.ofByteArray(TestPdfs.pdf(1)))
						.timeout(Duration.ofSeconds(1))
						.build(),
					HttpResponse.BodyHandlers.discarding()));

			assertThat(health(server)).isEqualTo(200);
		}
		finally {
			for (Socket socket : slow) {
				socket.close();
			}
		}
	}

	@Test
	void aDocumentThatExhaustsTheRenderHeapIsTooLargeAndTheConverterKeepsServing(@TempDir Path work) throws Exception {
		RenderProcess renderer = RenderProcess.sameJvm(work, 128, Duration.ofSeconds(60), Duration.ofSeconds(60));
		try (ConverterServer server = ConverterServer.start(new InetSocketAddress("127.0.0.1", 0),
				new InetSocketAddress("127.0.0.1", 0), 50L << 20, null, renderer)) {
			String base = "http://127.0.0.1:" + server.port();
			CompletableFuture<HttpResponse<String>> bomb = this.client.sendAsync(
					HttpRequest.newBuilder(URI.create(base + "/render"))
						.POST(HttpRequest.BodyPublishers.ofByteArray(BombPdfs.xrefBomb()))
						.build(),
					HttpResponse.BodyHandlers.ofString());
			while (!bomb.isDone()) {
				assertThat(health(server)).isEqualTo(200);
				Thread.sleep(100);
			}

			assertThat(bomb.get().statusCode()).isEqualTo(422);
			assertThat(bomb.get().body()).contains("\"too_large\"");
			assertThat(health(server)).isEqualTo(200);
			HttpResponse<byte[]> next = this.client.send(HttpRequest.newBuilder(URI.create(base + "/render"))
				.POST(HttpRequest.BodyPublishers.ofByteArray(TestPdfs.pdf(1)))
				.build(), HttpResponse.BodyHandlers.ofByteArray());
			assertThat(next.statusCode()).isEqualTo(200);
			assertThat(next.headers().firstValue("content-type")).hasValue("application/zip");
		}
	}

	// compose healthcheck가 부르는 상태 확인 port. healthcheck의 제한 시간(5초)보다 짧게 기다린다.
	private int health(ConverterServer server) throws Exception {
		return this.client.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.healthPort() + "/health"))
					.timeout(Duration.ofSeconds(2))
					.build(),
				HttpResponse.BodyHandlers.discarding())
			.statusCode();
	}

	private CompletableFuture<HttpResponse<String>> render(String base) {
		return post(base + "/render?format=docx");
	}

	private CompletableFuture<HttpResponse<String>> post(String uri) {
		return this.client.sendAsync(HttpRequest.newBuilder(URI.create(uri))
			.POST(HttpRequest.BodyPublishers.ofByteArray(new byte[] { 1, 2, 3 }))
			.build(), HttpResponse.BodyHandlers.ofString());
	}

}
