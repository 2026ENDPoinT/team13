package com.stackcorp.astra.files.upload;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.util.unit.DataSize;

import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestcontainersConfiguration;
import com.stackcorp.astra.shared.RequestIds;

import jakarta.servlet.ServletContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * multipart 본문을 나누지 못한 업로드. client가 보낸 본문이 multipart 형식이 아니면 400 {@code invalid_request}, 요청 상한을
 * 넘으면 413 {@code too_large}이고, 서버가 본문을 임시 디스크로 받지 못하면 그대로 500 {@code internal_error}다. MockMvc는
 * multipart를 미리 나눠 두므로 실제 Tomcat으로 본다. 상한을 넘는 본문을 빨리 보내도록 이 context의 multipart 상한을 낮춘다. 상한을
 * 넘은 본문은 Tomcat이 max-swallow-size(2MB)까지 읽어 버리므로 그보다 작게 둔다.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "spring.servlet.multipart.max-file-size=" + MultipartParseFailureTests.LIMIT,
				"spring.servlet.multipart.max-request-size=" + MultipartParseFailureTests.LIMIT })
class MultipartParseFailureTests {

	private static final HttpClient HTTP = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String BOUNDARY = "astra-boundary";

	static final String LIMIT = "1MB";

	// 업로드 경로 네 곳. 일반 업로드의 purpose는 과목이 필요 없는 것을 쓴다. 본문을 나누는 일은 handler보다 먼저라 녹음 경로의 과목은
	// 구성원이 아니어도 된다.
	private static final String[] ENDPOINTS = { "/api/uploads?purpose=message_attachment", "/api/filebox/files",
			"/api/me/profile/image", "/api/me/study-room/courses/1/recordings" };

	@LocalServerPort
	int port;

	@Autowired
	TestData data;

	@Autowired
	ServletContext servletContext;

	@AfterAll
	static void closeClient() {
		HTTP.close();
	}

	@Test
	void aBodyThatEndsBeforeTheClosingBoundaryIsABadRequest() throws Exception {
		// 파일 part를 시작했지만 끝 boundary 없이 본문이 끝난다(audit D2의 요청).
		byte[] body = (part("file", "a.png") + "\u0089PNG partial").getBytes(StandardCharsets.ISO_8859_1);
		for (String path : ENDPOINTS) {
			assertProblem(send(path, "multipart/form-data; boundary=" + BOUNDARY, body), 400, "invalid_request", path);
		}
	}

	@Test
	void aMultipartBodyWithoutABoundaryIsABadRequest() throws Exception {
		byte[] body = complete(part("file", "a.png") + "\u0089PNG").getBytes(StandardCharsets.ISO_8859_1);
		for (String path : ENDPOINTS) {
			assertProblem(send(path, "multipart/form-data", body), 400, "invalid_request", path);
		}
	}

	@Test
	void aWellFormedBodyWithoutTheFilePartIsABadRequest() throws Exception {
		byte[] body = complete("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"note\"\r\n\r\nhello")
			.getBytes(StandardCharsets.ISO_8859_1);
		for (String path : ENDPOINTS) {
			assertProblem(send(path, "multipart/form-data; boundary=" + BOUNDARY, body), 400, "invalid_request", path);
		}
	}

	@Test
	void aFileNameWithANulByteIsABadRequest() throws Exception {
		byte[] body = complete(part("file", "a\u0000.png") + "\u0089PNG").getBytes(StandardCharsets.ISO_8859_1);
		for (String path : ENDPOINTS) {
			assertProblem(send(path, "multipart/form-data; boundary=" + BOUNDARY, body), 400, "invalid_request", path);
		}
	}

	@Test
	void aNestedMultipartWhoseBoundaryLengthDiffersIsABadRequest() throws Exception {
		// 안쪽 multipart/mixed의 boundary는 바깥 boundary와 길이가 같아야 한다(Tomcat parser가 같은 buffer를 쓴다).
		byte[] body = complete("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"\r\n"
				+ "Content-Type: multipart/mixed; boundary=x\r\n\r\n--x\r\n"
				+ "Content-Disposition: attachment; filename=\"a.png\"\r\n\r\n\u0089PNG\r\n--x--")
			.getBytes(StandardCharsets.ISO_8859_1);
		for (String path : ENDPOINTS) {
			assertProblem(send(path, "multipart/form-data; boundary=" + BOUNDARY, body), 400, "invalid_request", path);
		}
	}

	@Test
	void aBodyOverTheMultipartLimitIsStillTooLarge() throws Exception {
		// 넓은 MultipartException 처리기가 생겨도 크기 초과(MaxUploadSizeExceededException)는 413이다.
		byte[] file = new byte[(int) DataSize.parse(LIMIT).toBytes() + 1024];
		byte[] head = part("file", "a.png").getBytes(StandardCharsets.ISO_8859_1);
		byte[] tail = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.ISO_8859_1);
		byte[] body = new byte[head.length + file.length + tail.length];
		System.arraycopy(head, 0, body, 0, head.length);
		System.arraycopy(file, 0, body, head.length, file.length);
		System.arraycopy(tail, 0, body, head.length + file.length, tail.length);
		for (String path : ENDPOINTS) {
			assertProblem(send(path, "multipart/form-data; boundary=" + BOUNDARY, body), 413, "too_large", path);
		}
	}

	@Test
	void aSpoolDirectoryTheServerCannotUseStaysAServerError() throws Exception {
		// Tomcat은 multipart location을 정하지 않으면 context의 임시 디렉터리에 본문을 받는다. 그 자리를 일반 파일로 바꿔 디렉터리로
		// 쓸 수 없게 한다. 없애기만 하면 Tomcat이 다시 만든다(Spring Boot가 createUploadTargets를 켠다).
		Path spool = ((File) this.servletContext.getAttribute(ServletContext.TEMPDIR)).toPath();
		Path moved = spool.resolveSibling(spool.getFileName() + ".moved");
		byte[] body = complete(part("file", "a.png") + "\u0089PNG").getBytes(StandardCharsets.ISO_8859_1);
		Files.move(spool, moved);
		try {
			Files.createFile(spool);
			for (String path : ENDPOINTS) {
				assertProblem(send(path, "multipart/form-data; boundary=" + BOUNDARY, body), 500, "internal_error",
						path);
			}
		}
		finally {
			Files.deleteIfExists(spool);
			Files.move(moved, spool);
		}
	}

	private HttpResponse<byte[]> send(String path, String contentType, byte[] body)
			throws IOException, InterruptedException {
		String csrf = csrfToken();
		String session = this.data.session(this.data.user("STUDENT"));
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + this.port + path))
			.header("Content-Type", contentType)
			.header("Cookie", "ASTRA_SESSION=" + session + "; XSRF-TOKEN=" + csrf)
			.header("X-XSRF-TOKEN", csrf)
			.POST(HttpRequest.BodyPublishers.ofByteArray(body))
			.build();
		return HTTP.send(request, HttpResponse.BodyHandlers.ofByteArray());
	}

	private static void assertProblem(HttpResponse<byte[]> response, int status, String code, String path) {
		assertThat(response.statusCode()).as(path).isEqualTo(status);
		assertThat(response.headers().firstValue("Content-Type")).as(path)
			.hasValueSatisfying((type) -> assertThat(type).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE));
		JsonNode problem = JSON.readTree(response.body());
		assertThat(problem.get("code").asString()).as(path).isEqualTo(code);
		assertThat(problem.get("requestId").asString()).as(path)
			.isEqualTo(response.headers().firstValue(RequestIds.HEADER).orElseThrow());
	}

	private static String part(String name, String filename) {
		return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"" + name + "\"; filename=\"" + filename
				+ "\"\r\nContent-Type: application/octet-stream\r\n\r\n";
	}

	private static String complete(String parts) {
		return parts + "\r\n--" + BOUNDARY + "--\r\n";
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
