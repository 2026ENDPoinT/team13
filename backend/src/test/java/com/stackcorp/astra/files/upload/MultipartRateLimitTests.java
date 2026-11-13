package com.stackcorp.astra.files.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.util.unit.DataSize;

import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestcontainersConfiguration;
import com.stackcorp.astra.shared.RateLimiter;

/**
 * multipart 업로드의 사용자 요청 제한은 서블릿 컨테이너가 multipart 본문을 나눠 임시 디스크로 받기 전에 센다. 업로드가 아닌 경로로 온
 * multipart, CSRF header가 없는 multipart, firewall이 거부한 경로도 그 전에 끝낸다. 실제 port로 큰 본문을 선언하고 일부만 보낸 채
 * 거절 응답이 바로 오는지 본다. 응답한 뒤에도 Tomcat은 keep-alive로 연결을 다시 쓰려고 남은 본문을
 * {@code server.tomcat.max-swallow-size}까지 읽어 버린다. 선언한 본문이 그보다 크면 그만큼 읽고 연결을 끊고, 작으면 다 읽고 같은
 * 연결로 다음 요청을 받는지도 본다. MockMvc는 multipart를 미리 나눠 두므로 실제 Tomcat으로 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MultipartRateLimitTests {

	// multipart 요청 상한(51MB)보다 작게 선언해 Tomcat의 크기 검사에 걸리지 않게 한다.
	private static final long DECLARED = 50L * 1024 * 1024;

	// 응답을 기다리기 전에 보내는 본문의 앞부분
	private static final int CHUNK = 64 * 1024;

	// 녹음 올리기. 없는 과목이라 본문을 받은 요청은 handler가 404로 거부한다.
	private static final String RECORDINGS = "/api/me/study-room/courses/999999999/recordings";

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Value("${server.tomcat.max-swallow-size}")
	DataSize maxSwallow;

	@Autowired
	TestData data;

	@Autowired
	RateLimiter rateLimiter;

	@Autowired
	UploadProperties uploads;

	@AfterAll
	static void closeClient() {
		HTTP.close();
	}

	@Test
	void rejectsALimitedUserBeforeParsingTheMultipartBody() throws Exception {
		for (String[] endpoint : new String[][] { { "/api/uploads", "upload" }, { "/api/filebox/files", "upload" },
				{ RECORDINGS, "upload" }, { "/api/me/profile/image", "avatar-upload" } }) {
			long user = this.data.user("STUDENT");
			// 이 사용자가 연산의 한도를 이미 다 쓴 상태로 만든다. 서버 쪽 한도는 어느 연산이든 1000보다 작다.
			for (int i = 0; i < 1000; i++) {
				this.rateLimiter.check(endpoint[1], user, Integer.MAX_VALUE, Duration.ofMinutes(10));
			}
			List<String> head = refuse(endpoint[0], this.data.session(user), csrfToken(), 429);
			assertThat(head).as(endpoint[0]).anyMatch((line) -> line.regionMatches(true, 0, "Retry-After:", 0, 12));
		}
	}

	@Test
	void aRecordingUploadCountsOnceAgainstTheSharedUploadLimit() throws Exception {
		long user = this.data.user("STUDENT");
		String session = this.data.session(user);
		// 두 번을 남기고 한도를 쓴다. 녹음 올리기가 한 번에 둘을 센다면 두 번째 요청이 429다.
		for (int i = 0; i < this.uploads.rateLimit() - 2; i++) {
			this.rateLimiter.check("upload", user, Integer.MAX_VALUE, Duration.ofMinutes(10));
		}
		// 녹음 경로는 multipart를 받는 업로드 경로라 415가 아니고, 본문을 나눈 뒤 handler가 구성원이 아닌 과목이라 404로 거부한다.
		// 거부한 시도도 센다.
		assertThat(recording(session).statusCode()).isEqualTo(404);
		assertThat(recording(session).statusCode()).isEqualTo(404);
		HttpResponse<String> limited = recording(session);
		assertThat(limited.statusCode()).isEqualTo(429);
		assertThat(limited.headers().firstValue("Retry-After")).isPresent();
		// 같은 한도를 일반 업로드와 함께 쓴다.
		refuse("/api/uploads", session, csrfToken(), 429);
	}

	@Test
	void refusesMultipartOutsideTheUploadPathsBeforeParsingTheBody() throws Exception {
		// 로그인한 사용자의 JSON 명령, 로그인 전에도 부를 수 있는 명령
		refuse("/api/filebox/trash/empty", this.data.session(this.data.user("STUDENT")), csrfToken(), 415);
		refuse("/api/auth/break-glass", null, csrfToken(), 415);
	}

	@Test
	void refusesMultipartWithoutTheCsrfHeaderBeforeParsingTheBody() throws Exception {
		// header가 없으면 CSRF 검사가 본문의 _csrf parameter를 찾느라 multipart 본문 전체를 받는다. 로그인 전 요청도 그렇다.
		refuse("/api/uploads", null, null, 403);
		refuse("/api/uploads", this.data.session(this.data.user("STUDENT")), null, 403);
		refuse(RECORDINGS, this.data.session(this.data.user("STUDENT")), null, 403);
	}

	@Test
	void refusesFirewallRejectedMultipartBeforeParsingTheBody() throws Exception {
		// Spring Security firewall이 거부한 경로(;). 오류 처리 단계(/error)로 넘기면 그곳의 DispatcherServlet이 본문을 받는다.
		String session = this.data.session(this.data.user("STUDENT"));
		refuse("/api/uploads;x", session, csrfToken(), 400);
		refuse("/api/uploads;x", null, null, 400);
	}

	/**
	 * keep-alive 요청으로 본문 앞부분만 보내고 거절 응답이 바로 오는지 본다. 서버가 multipart 본문을 기다리면 읽기 시간 제한에 걸린다.
	 * 그 뒤 연결을 두 가지로 본다.
	 * <ul>
	 * <li>선언한 본문이 max-swallow-size보다 크면 응답에 {@code Connection: close}가 붙고, 서버는 남은 본문을 그만큼 읽어 버린 뒤
	 * 연결을 끊는다. 선언한 본문을 끝까지 보낼 수 없다.</li>
	 * <li>그보다 작으면 남은 본문을 다 읽어 버리고 같은 연결로 다음 요청을 받는다. 400은 Tomcat이 언제나 연결을 닫는 상태라 다음 요청을
	 * 받지 않지만, 남은 본문은 다 읽고 닫는다.</li>
	 * </ul>
	 * @param csrf null이면 CSRF 쿠키와 header를 보내지 않는다
	 * @return 선언한 본문이 max-swallow-size보다 큰 요청의 응답 머리
	 */
	private List<String> refuse(String path, String session, String csrf, int status) throws IOException {
		List<String> head;
		try (Socket socket = new Socket("127.0.0.1", this.port)) {
			socket.setSoTimeout(10_000);
			OutputStream out = socket.getOutputStream();
			out.write(requestHead(path, session, csrf, DECLARED));
			out.write(new byte[CHUNK]);
			out.flush();
			head = readHead(socket.getInputStream());
			assertThat(head.getFirst()).as(path).startsWith("HTTP/1.1 " + status);
			assertThat(head).as(path).contains("Connection: close");
			long sent = CHUNK;
			byte[] chunk = new byte[CHUNK];
			try {
				for (; sent < DECLARED; sent += CHUNK) {
					out.write(chunk);
				}
			}
			catch (IOException ex) {
				// 서버가 연결을 끊었다.
			}
			assertThat(sent).as("%s body bytes sent before the server closed the connection", path)
				.isGreaterThan(this.maxSwallow.toBytes())
				.isLessThan(DECLARED);
		}

		long declared = this.maxSwallow.toBytes() / 2;
		try (Socket socket = new Socket("127.0.0.1", this.port)) {
			socket.setSoTimeout(10_000);
			OutputStream out = socket.getOutputStream();
			InputStream in = socket.getInputStream();
			out.write(requestHead(path, session, csrf, declared));
			out.write(new byte[CHUNK]);
			out.flush();
			List<String> small = readHead(in);
			assertThat(small.getFirst()).as(path).startsWith("HTTP/1.1 " + status);
			skipBody(in, small);
			out.write(new byte[(int) declared - CHUNK]);
			out.flush();
			if (status == 400) {
				assertThat(small).as(path).contains("Connection: close");
				assertThat(in.read()).as("%s connection closed after the rest of the body", path).isEqualTo(-1);
			}
			else {
				assertThat(small).as(path).doesNotContain("Connection: close");
				out.write(("GET /api/auth/csrf HTTP/1.1\r\nHost: 127.0.0.1:" + this.port + "\r\n\r\n")
					.getBytes(StandardCharsets.US_ASCII));
				out.flush();
				assertThat(readHead(in).getFirst()).as("%s next request on the same connection", path)
					.startsWith("HTTP/1.1 204");
			}
		}
		return head;
	}

	private byte[] requestHead(String path, String session, String csrf, long contentLength) {
		List<String> cookies = new ArrayList<>();
		if (session != null) {
			cookies.add("ASTRA_SESSION=" + session);
		}
		if (csrf != null) {
			cookies.add("XSRF-TOKEN=" + csrf);
		}
		return ("POST " + path + " HTTP/1.1\r\n" + "Host: 127.0.0.1:" + this.port + "\r\n"
				+ (cookies.isEmpty() ? "" : "Cookie: " + String.join("; ", cookies) + "\r\n")
				+ (csrf != null ? "X-XSRF-TOKEN: " + csrf + "\r\n" : "")
				+ "Content-Type: multipart/form-data; boundary=astra-boundary\r\n" + "Content-Length: " + contentLength
				+ "\r\n\r\n")
			.getBytes(StandardCharsets.US_ASCII);
	}

	private static List<String> readHead(InputStream in) throws IOException {
		List<String> head = new ArrayList<>();
		for (String line = readLine(in); !line.isEmpty(); line = readLine(in)) {
			head.add(line);
		}
		assertThat(head).as("response head").isNotEmpty();
		return head;
	}

	/** 응답 본문(Content-Length 또는 chunked)을 읽어 버린다. 같은 연결의 다음 응답을 읽을 수 있게 한다. */
	private static void skipBody(InputStream in, List<String> head) throws IOException {
		for (String line : head) {
			if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
				in.readNBytes(Integer.parseInt(line.substring(15).strip()));
				return;
			}
		}
		assertThat(head).contains("Transfer-Encoding: chunked");
		for (int size = Integer.parseInt(readLine(in), 16); size > 0; size = Integer.parseInt(readLine(in), 16)) {
			in.readNBytes(size + 2);
		}
		readLine(in);
	}

	private static String readLine(InputStream in) throws IOException {
		ByteArrayOutputStream line = new ByteArrayOutputStream();
		for (int b = in.read(); b != '\n'; b = in.read()) {
			if (b == -1) {
				throw new IOException("connection closed");
			}
			line.write(b);
		}
		return line.toString(StandardCharsets.ISO_8859_1).strip();
	}

	private String csrfToken() throws IOException, InterruptedException {
		HttpResponse<Void> response = HTTP.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/auth/csrf")).GET().build(),
				HttpResponse.BodyHandlers.discarding());
		return response.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter((cookie) -> cookie.startsWith("XSRF-TOKEN="))
			.map((cookie) -> cookie.substring("XSRF-TOKEN=".length(), cookie.indexOf(';')))
			.findFirst()
			.orElseThrow();
	}

	/** 구성원이 아닌 과목에 작은 녹음을 끝까지 보낸다. */
	private HttpResponse<String> recording(String session) throws IOException, InterruptedException {
		String csrf = csrfToken();
		String body = "--astra-boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"r.webm\"\r\n"
				+ "Content-Type: audio/webm\r\n\r\nwebm\r\n" + field("title", "녹음")
				+ field("source", "mic") + field("durationMs", "1000") + field("recordedAt", "2026-11-12T05:00:00Z")
				+ "--astra-boundary--\r\n";
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + RECORDINGS))
			.version(HttpClient.Version.HTTP_1_1)
			.header("Content-Type", "multipart/form-data; boundary=astra-boundary")
			.header("Cookie", "ASTRA_SESSION=" + session + "; XSRF-TOKEN=" + csrf)
			.header("X-XSRF-TOKEN", csrf)
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private static String field(String name, String value) {
		return "--astra-boundary\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n";
	}

}
