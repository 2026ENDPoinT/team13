package com.stackcorp.astra.integration.transcoder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.stackcorp.astra.files.api.TranscodeException;
import com.stackcorp.astra.files.api.TranscodeException.Code;
import com.stackcorp.astra.files.api.VideoTranscoder.Transcoded;
import com.stackcorp.astra.files.api.VideoTranscoder.VideoSource;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.util.unit.DataSize;

/**
 * transcoder 응답은 신뢰하지 않는다. 재생 목록에 적힌 이름과 순서대로 온 segment만 넘기고, 경로나 순서가 다르거나 크기를 넘으면 멈춘다.
 * 오류 응답은 다시 시도할 수 있는 것과 없는 것으로 나눈다(ADR-0016).
 */
class HttpVideoTranscoderTests {

	private static final String PLAYLIST = "#EXTM3U\n#EXT-X-TARGETDURATION:3\n#EXTINF:2.5,\nseg_00000.ts\n#EXTINF:1.0,\nseg_00001.ts\n#EXT-X-ENDLIST\n";

	HttpServer server;

	volatile int status;

	volatile byte[] response;

	HttpVideoTranscoder transcoder;

	@BeforeEach
	void start() throws IOException {
		this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		this.server.createContext("/transcode", (exchange) -> {
			exchange.getRequestBody().readAllBytes();
			exchange.sendResponseHeaders(this.status, this.response.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(this.response);
			}
		});
		this.server.start();
		this.transcoder = transcoder(this.server.getAddress().getPort());
	}

	@AfterEach
	void stop() {
		this.transcoder.close();
		this.server.stop(0);
	}

	@Test
	void passesSegmentsInPlaylistOrderWithTheirDurations() throws Exception {
		respond(200, zip("index.m3u8", ascii(PLAYLIST), "seg_00000.ts", new byte[] { 1 }, "seg_00001.ts", new byte[] { 2 }));
		List<String> received = new ArrayList<>();

		Transcoded result = this.transcoder.transcode(source(), (i, d, b) -> received.add(i + ":" + d + ":" + b[0]),
				() -> false);

		assertThat(received).containsExactly("0:2.5:1", "1:1.0:2");
		assertThat(result.segmentCount()).isEqualTo(2);
		assertThat(result.durationSeconds()).isEqualTo(3.5);
	}

	@Test
	void stopsAtNamesOrderOrSizesThatDoNotMatchThePlaylist() throws Exception {
		byte[] traversal = ascii(PLAYLIST.replace("seg_00001.ts", "../../vod-keys/x.key"));
		List<byte[]> responses = List.of(
				zip("index.m3u8", traversal, "seg_00000.ts", new byte[] { 1 }, "../../vod-keys/x.key", new byte[] { 2 }),
				zip("index.m3u8", ascii(PLAYLIST), "seg_00001.ts", new byte[] { 1 }, "seg_00000.ts", new byte[] { 2 }),
				zip("seg_00000.ts", new byte[] { 1 }, "index.m3u8", ascii(PLAYLIST), "seg_00001.ts", new byte[] { 2 }),
				zip("index.m3u8", ascii(PLAYLIST), "seg_00000.ts", new byte[] { 1 }, "seg_00001.ts", new byte[2 << 20]));
		for (byte[] body : responses) {
			respond(200, body);
			List<Integer> received = new ArrayList<>();
			assertThatThrownBy(() -> this.transcoder.transcode(source(), (i, d, b) -> received.add(i), () -> false))
				.isInstanceOfSatisfying(TranscodeException.class, (ex) -> assertThat(ex.code()).isEqualTo(Code.FAILED));
			assertThat(received).doesNotContain(1);
		}

		respond(200, zip("index.m3u8", ascii(PLAYLIST), "seg_00000.ts", new byte[] { 1 }, "seg_00001.ts", new byte[] { 2 }));
		assertThatThrownBy(() -> this.transcoder.transcode(source(), (i, d, b) -> {
		}, () -> true)).isInstanceOfSatisfying(TranscodeException.class,
				(ex) -> assertThat(ex.code()).isEqualTo(Code.CANCELLED));
	}

	@Test
	void separatesBadInputFromTransientFailures() throws Exception {
		respond(422, "{\"code\":\"too_long\",\"message\":\"재생 길이가 상한을 넘습니다.\"}".getBytes(StandardCharsets.UTF_8));
		assertCode(this.transcoder, Code.TOO_LONG, false);
		respond(422, "{\"code\":\"invalid_media\"}".getBytes(StandardCharsets.UTF_8));
		assertCode(this.transcoder, Code.INVALID_MEDIA, false);
		respond(500, "{\"code\":\"internal\"}".getBytes(StandardCharsets.UTF_8));
		assertCode(this.transcoder, Code.UNAVAILABLE, true);

		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		try (HttpVideoTranscoder unreachable = transcoder(closedPort)) {
			assertCode(unreachable, Code.UNAVAILABLE, true);
		}
	}

	private static void assertCode(HttpVideoTranscoder transcoder, Code code, boolean retryable) {
		assertThatThrownBy(() -> transcoder.transcode(source(), (i, d, b) -> {
		}, () -> false)).isInstanceOfSatisfying(TranscodeException.class, (ex) -> {
			assertThat(ex.code()).isEqualTo(code);
			assertThat(ex.code().retryable()).isEqualTo(retryable);
		});
	}

	private void respond(int status, byte[] body) {
		this.status = status;
		this.response = body;
	}

	private static HttpVideoTranscoder transcoder(int port) {
		return new HttpVideoTranscoder(new TranscoderProperties(URI.create("http://127.0.0.1:" + port),
				Duration.ofSeconds(10), DataSize.ofKilobytes(64), DataSize.ofMegabytes(1), 10));
	}

	private static VideoSource source() {
		return new VideoSource("mp4", 10, () -> new ByteArrayInputStream(new byte[10]));
	}

	private static byte[] ascii(String text) {
		return text.getBytes(StandardCharsets.US_ASCII);
	}

	/** @param entries 이름과 내용을 번갈아 둔 zip 항목. 이 순서대로 쓴다 */
	private static byte[] zip(Object... entries) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (int i = 0; i < entries.length; i += 2) {
				zip.putNextEntry(new ZipEntry((String) entries[i]));
				zip.write((byte[]) entries[i + 1]);
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}

}
