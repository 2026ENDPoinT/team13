package com.stackcorp.astra.integration.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

import com.stackcorp.astra.files.api.ContentScanner.Clean;
import com.stackcorp.astra.files.api.ContentScanner.Infected;
import com.stackcorp.astra.files.api.ContentScanner.Unavailable;
import com.stackcorp.astra.files.api.ContentScanner.Verdict;
import com.stackcorp.astra.integration.scan.FakeClamd.Mode;
import com.stackcorp.astra.integration.scan.FakeClamd.Session;

/**
 * clamd INSTREAM 형식과 판정 해석. 검사기에 닿지 못하거나 끝까지 보내지 못하면 어떤 경우에도 clean이 아니다(ADR-0018).
 */
class ClamdScannerTests {

	private static FakeClamd clamd;

	private static ClamdScanner scanner;

	@BeforeAll
	static void start() {
		clamd = FakeClamd.start();
		scanner = scanner(clamd.port(), 1024, 1L << 20, Duration.ofSeconds(5));
	}

	@AfterAll
	static void stop() {
		scanner.close();
		clamd.close();
	}

	@BeforeEach
	void reset() {
		clamd.reset();
	}

	@Test
	void streamsContentInLengthPrefixedChunksAndEndsWithAZeroLengthChunk() {
		byte[] content = random(2500);

		Verdict verdict = scan(content);

		assertThat(verdict).isEqualTo(new Clean());
		Session session = clamd.sessions().getLast();
		assertThat(session.command()).isEqualTo("zINSTREAM");
		assertThat(session.chunkSizes()).containsExactly(1024, 1024, 452);
		assertThat(session.terminated()).isTrue();
		assertThat(session.payload()).isEqualTo(content);
	}

	@Test
	void reportsOnlyASanitizedSignatureNameForInfectedContent() {
		assertThat(scan(FakeClamd.EICAR.getBytes(StandardCharsets.US_ASCII)))
			.isEqualTo(new Infected(FakeClamd.EICAR_SIGNATURE));

		clamd.respond((content) -> "stream: Evil<b> Name\u0007" + "x".repeat(300) + " FOUND");
		Verdict odd = scan(random(10));

		assertThat(odd).isInstanceOfSatisfying(Infected.class, (infected) -> {
			assertThat(infected.signature()).startsWith("Evil_b__Name_").hasSize(100).matches("[A-Za-z0-9._:/-]+");
		});
	}

	@Test
	void treatsDaemonErrorsAndUnknownRepliesAsUnavailable() {
		clamd.respond((content) -> "Can't allocate memory ERROR");
		assertThat(scan(random(10))).isEqualTo(new Unavailable("error"));

		clamd.respond((content) -> "stream: OK maybe");
		assertThat(scan(random(10))).isEqualTo(new Unavailable("protocol"));

		clamd.respond((content) -> "x".repeat(5000));
		assertThat(scan(random(10))).isEqualTo(new Unavailable("protocol"));
	}

	@Test
	void reportsTheDaemonSizeLimitInsteadOfAVerdict() {
		clamd.streamMaxLength(1000);

		assertThat(scan(random(5000))).isEqualTo(new Unavailable("size_limit"));
		assertThat(clamd.sessions().getLast().terminated()).isFalse();
	}

	@Test
	void doesNotSendMoreThanTheConfiguredLimit() {
		try (ClamdScanner small = scanner(clamd.port(), 1024, 1000, Duration.ofSeconds(5))) {
			int connections = clamd.connections();
			int sessions = clamd.sessions().size();

			// 기록한 크기가 상한을 넘으면 연결하지 않는다.
			assertThat(small.scan(new ByteArrayInputStream(random(5000)), 5000)).isEqualTo(new Unavailable("size_limit"));
			assertThat(clamd.connections()).isEqualTo(connections);

			// 기록보다 긴 내용은 상한에서 멈추고, 기록과 길이가 다른 내용은 끝 표시를 보내지 않아 판정을 받지 않는다.
			assertThat(small.scan(new ByteArrayInputStream(random(5000)), 900)).isEqualTo(new Unavailable("size_limit"));
			assertThat(small.scan(new ByteArrayInputStream(random(500)), 900)).isEqualTo(new Unavailable("io"));
			assertThat(clamd.sessions().stream().skip(sessions)).noneMatch(Session::terminated);
		}
	}

	@Test
	void givesUpOnASlowDaemonAtTheTimeout() {
		clamd.slow(Duration.ofSeconds(10));
		try (ClamdScanner impatient = scanner(clamd.port(), 1024, 1L << 20, Duration.ofMillis(500))) {
			long started = System.nanoTime();

			Verdict verdict = impatient.scan(new ByteArrayInputStream(random(100)), 100);

			assertThat(verdict).isEqualTo(new Unavailable("timeout"));
			assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
		}
	}

	@Test
	void neverReportsCleanWhenTheDaemonHangsUpOrIsDown() throws IOException {
		clamd.mode(Mode.CLOSE_EARLY);
		assertThat(scan(random(100_000))).isInstanceOf(Unavailable.class);

		int closedPort;
		try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
			closedPort = socket.getLocalPort();
		}
		try (ClamdScanner down = scanner(closedPort, 1024, 1L << 20, Duration.ofSeconds(5))) {
			assertThat(down.scan(new ByteArrayInputStream(random(10)), 10)).isEqualTo(new Unavailable("connect"));
		}
	}

	@Test
	void acceptsOnlyAPlainHostName() {
		for (String host : new String[] { "http://clamav:3310", "clamav/scan", "user@clamav", "clamav 3310" }) {
			assertThatIllegalArgumentException().isThrownBy(() -> properties(host, 3310, 1024, 1000, Duration.ofSeconds(1)));
		}
		assertThat(properties("clamav", 3310, 1024, 1000, Duration.ofSeconds(1)).host()).isEqualTo("clamav");
		assertThat(properties("::1", 3310, 1024, 1000, Duration.ofSeconds(1)).host()).isEqualTo("::1");
	}

	private static Verdict scan(byte[] content) {
		return scanner.scan(new ByteArrayInputStream(content), content.length);
	}

	private static ClamdScanner scanner(int port, int chunk, long max, Duration timeout) {
		return new ClamdScanner(properties("127.0.0.1", port, chunk, max, timeout));
	}

	private static ClamdProperties properties(String host, int port, int chunk, long max, Duration timeout) {
		return new ClamdProperties(host, port, Duration.ofSeconds(2), timeout, DataSize.ofBytes(chunk),
				DataSize.ofBytes(max));
	}

	private static byte[] random(int length) {
		byte[] bytes = new byte[length];
		new Random(length).nextBytes(bytes);
		return bytes;
	}

}
