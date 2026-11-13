package com.stackcorp.astra.identity.breakglass;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.stackcorp.astra.TestcontainersConfiguration;

/**
 * 실제 Tomcat이 받은 요청의 브라우저 주소. 이 테스트의 연결은 127.0.0.1에서 오고, 프론트엔드 서버(server.mjs)처럼 믿는 proxy다.
 * 앞단(Cloudflare)이 받은 X-Forwarded-For 뒤에 자기가 본 주소를 덧붙이고 server.mjs가 그 앞단(127.0.0.1)을 덧붙인 모양을 보낸다.
 * 브라우저가 위조해 넣은 값(203.0.113.99)은 한 줄이든 여러 줄이든 기록되지 않아야 한다.
 * <p>
 * 실행 환경을 Kubernetes로 알아보게 둔다. server.forward-headers-strategy=none을 빼면 Spring Boot가 Tomcat의 forwarded header 처리를
 * 켜고, Tomcat은 사설 주소(10.0.0.0/8)를 proxy로 보고 건너뛰어 마지막 경우가 위조 값을 기록하므로 이 테스트가 실패한다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "astra.client-address.trusted-proxies=127.0.0.0/8,::1",
				"astra.break-glass.external-ref=" + ClientAddressTomcatTests.REF,
				"astra.break-glass.totp-secret=JBSWY3DPEHPK3PXP", "spring.main.cloud-platform=kubernetes" })
class ClientAddressTomcatTests {

	static final String REF = "tomcat-break-glass";

	private static final String FORGED = "203.0.113.99";

	private static final HttpClient HTTP = HttpClient.newHttpClient();

	@LocalServerPort
	int port;

	@Autowired
	JdbcClient jdbc;

	long userId;

	@DynamicPropertySource
	static void password(DynamicPropertyRegistry registry) {
		String hash = PasswordEncoderFactories.createDelegatingPasswordEncoder().encode("correct-password");
		registry.add("astra.break-glass.password-hash", () -> hash);
	}

	@AfterAll
	static void closeClient() {
		HTTP.close();
	}

	@BeforeEach
	void account() {
		this.userId = this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", REF)
			.query(Long.class)
			.optional()
			.orElseGet(() -> {
				this.jdbc.sql("INSERT INTO users (external_ref, user_name, user_role) VALUES (:ref, '관리자', 'STAFF')")
					.param("ref", REF)
					.update();
				return this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
					.param("ref", REF)
					.query(Long.class)
					.single();
			});
		this.jdbc.sql("DELETE FROM login_events WHERE user_id = :id").param("id", this.userId).update();
	}

	@Test
	void recordsTheAddressTheFrontAppendedNotTheForgedOne() throws Exception {
		String xsrf = xsrf();

		// 한 줄: 위조 값, Cloudflare가 본 주소, server.mjs가 덧붙인 cloudflared.
		assertThat(login(xsrf, FORGED + ", 198.51.100.20, 127.0.0.1").statusCode()).isEqualTo(401);
		// 위조 값이 따로 한 줄로 오고 앞단이 다음 줄에 덧붙인 경우.
		assertThat(login(xsrf, FORGED, "198.51.100.21, 127.0.0.1").statusCode()).isEqualTo(401);
		// 위조한 줄이 둘이어도 오른쪽부터 읽는다.
		assertThat(login(xsrf, FORGED, "203.0.113.98", "2001:db8:7::5, ::1").statusCode()).isEqualTo(401);
		// 믿는 목록에 없는 사설 주소는 브라우저 주소다. Tomcat의 forwarded header 처리가 켜지면 이 주소를 proxy로 건너뛴다.
		assertThat(login(xsrf, FORGED + ", 10.1.2.3").statusCode()).isEqualTo(401);

		assertThat(this.jdbc.sql("SELECT ip_address FROM login_events WHERE user_id = :id ORDER BY login_event_id")
			.param("id", this.userId)
			.query(String.class)
			.list()).containsExactly("198.51.100.20", "198.51.100.21", "2001:db8:7:0:0:0:0:5", "10.1.2.3")
			.doesNotContain(FORGED, "203.0.113.98");
	}

	private String xsrf() throws IOException, InterruptedException {
		HttpResponse<String> csrf = HTTP.send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + "/api/auth/csrf")).GET().build(),
				HttpResponse.BodyHandlers.ofString());
		return csrf.headers()
			.allValues("Set-Cookie")
			.stream()
			.filter((c) -> c.startsWith("XSRF-TOKEN="))
			.map((c) -> c.substring("XSRF-TOKEN=".length(), c.indexOf(';')))
			.findFirst()
			.orElseThrow();
	}

	private HttpResponse<String> login(String xsrf, String... forwardedFor) throws IOException, InterruptedException {
		String origin = "http://127.0.0.1:" + this.port;
		HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + "/api/auth/break-glass"))
			.POST(HttpRequest.BodyPublishers
				.ofString("{\"username\":\"" + REF + "\",\"password\":\"wrong\",\"code\":\"000000\"}"))
			.header("Content-Type", "application/json")
			.header("Origin", origin)
			.header("Sec-Fetch-Site", "same-origin")
			.header("X-XSRF-TOKEN", xsrf)
			.header("Cookie", "XSRF-TOKEN=" + xsrf);
		for (String value : forwardedFor) {
			request.header("X-Forwarded-For", value);
		}
		return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
	}

}
