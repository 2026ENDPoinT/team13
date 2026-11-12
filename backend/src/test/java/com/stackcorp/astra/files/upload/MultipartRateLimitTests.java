package com.stackcorp.astra.files.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestcontainersConfiguration;
import com.stackcorp.astra.shared.RateLimiter;

/**
 * multipart 업로드의 사용자 요청 제한은 서블릿 컨테이너가 본문을 임시 디스크로 받기 전에 센다. 실제 port로 본문 없이 머리만 보내고,
 * 제한을 넘긴 사용자는 본문을 기다리지 않고 바로 429를 받는지 본다. 업로드가 아닌 경로로 온 multipart도 본문을 받지 않고 415다.
 * MockMvc는 multipart를 미리 나눠 두므로 실제 Tomcat으로 본다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class MultipartRateLimitTests {

	// multipart 요청 상한(51MB)보다 작게 선언해 Tomcat의 크기 검사에 걸리지 않게 한다.
	private static final long DECLARED = 50L * 1024 * 1024;

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Autowired
	TestData data;

	@Autowired
	RateLimiter rateLimiter;

	@AfterAll
	static void closeClient() {
		HTTP.close();
	}

	@Test
	void rejectsALimitedUserBeforeReceivingTheMultipartBody() throws Exception {
		for (String[] endpoint : new String[][] { { "/api/uploads", "upload" }, { "/api/filebox/files", "upload" },
				{ "/api/me/profile/image", "avatar-upload" } }) {
			long user = this.data.user("STUDENT");
			// 이 사용자가 연산의 한도를 이미 다 쓴 상태로 만든다. 서버 쪽 한도는 어느 연산이든 1000보다 작다.
			for (int i = 0; i < 1000; i++) {
				this.rateLimiter.check(endpoint[1], user, Integer.MAX_VALUE, Duration.ofMinutes(10));
			}
			List<String> head = postHeadersOnly(endpoint[0], this.data.session(user), csrfToken());
			assertThat(head.getFirst()).as(endpoint[0]).startsWith("HTTP/1.1 429");
			assertThat(head).as(endpoint[0]).anyMatch((line) -> line.regionMatches(true, 0, "Retry-After:", 0, 12));
		}
	}

	@Test
	void refusesMultipartOutsideTheUploadPathsBeforeReceivingTheBody() throws Exception {
		// 로그인한 사용자의 JSON 명령, 로그인 전에도 부를 수 있는 명령
		List<String> signedIn = postHeadersOnly("/api/filebox/trash/empty", this.data.session(this.data.user("STUDENT")),
				csrfToken());
		assertThat(signedIn.getFirst()).startsWith("HTTP/1.1 415");
		List<String> anonymous = postHeadersOnly("/api/auth/break-glass", null, csrfToken());
		assertThat(anonymous.getFirst()).startsWith("HTTP/1.1 415");
	}

	@Test
	void refusesMultipartWithoutTheCsrfHeaderBeforeReceivingTheBody() throws Exception {
		// header가 없으면 CSRF 검사가 본문의 _csrf parameter를 찾느라 본문 전체를 받는다. 로그인 전 요청도 그렇다.
		assertThat(postHeadersOnly("/api/uploads", null, null).getFirst()).startsWith("HTTP/1.1 403");
		assertThat(postHeadersOnly("/api/uploads", this.data.session(this.data.user("STUDENT")), null).getFirst())
			.startsWith("HTTP/1.1 403");
	}

	@Test
	void refusesFirewallRejectedMultipartBeforeReceivingTheBody() throws Exception {
		// Spring Security firewall이 거부한 경로(;). 오류 처리 단계(/error)로 넘기면 그곳의 DispatcherServlet이 본문을 받는다.
		String session = this.data.session(this.data.user("STUDENT"));
		assertThat(postHeadersOnly("/api/uploads;x", session, csrfToken()).getFirst()).startsWith("HTTP/1.1 400");
		assertThat(postHeadersOnly("/api/uploads;x", null, null).getFirst()).startsWith("HTTP/1.1 400");
	}

	/**
	 * 요청 머리만 보내고 본문은 한 byte도 보내지 않는다. 서버가 본문을 기다리면 읽기 시간 제한에 걸린다.
	 * @param csrf null이면 CSRF 쿠키와 header를 보내지 않는다
	 */
	private List<String> postHeadersOnly(String path, String session, String csrf) throws IOException {
		try (Socket socket = new Socket("127.0.0.1", this.port)) {
			socket.setSoTimeout(10_000);
			List<String> cookies = new ArrayList<>();
			if (session != null) {
				cookies.add("ASTRA_SESSION=" + session);
			}
			if (csrf != null) {
				cookies.add("XSRF-TOKEN=" + csrf);
			}
			String request = "POST " + path + " HTTP/1.1\r\n" + "Host: 127.0.0.1:" + this.port + "\r\n"
					+ (cookies.isEmpty() ? "" : "Cookie: " + String.join("; ", cookies) + "\r\n")
					+ (csrf != null ? "X-XSRF-TOKEN: " + csrf + "\r\n" : "")
					+ "Content-Type: multipart/form-data; boundary=astra-boundary\r\n" + "Content-Length: " + DECLARED
					+ "\r\n" + "Connection: close\r\n\r\n";
			OutputStream out = socket.getOutputStream();
			out.write(request.getBytes(StandardCharsets.US_ASCII));
			out.flush();
			BufferedReader in = new BufferedReader(
					new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
			List<String> head = new ArrayList<>();
			for (String line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
				head.add(line);
			}
			assertThat(head).as("response head").isNotEmpty();
			return head;
		}
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

}
