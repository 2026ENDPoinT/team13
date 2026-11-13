package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.catalina.connector.ClientAbortException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.WebUtils;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import tools.jackson.databind.JsonNode;

/**
 * 처리기가 예상하지 못한 예외도 problem JSON 500으로 응답하고, detail에 예외 메시지(SQL, 입력값, key)를 싣지 않는다. client 연결
 * 끊김은 남기지 않는다. 응답이 이미 나간 뒤의 실패에는 problem을 덧붙이지 않는다. 컨트롤러 앞 filter에서 난 예외는 container의
 * /error dispatch를 거치므로 실제 port로 {@link ErrorDispatchApiTests}가 확인한다.
 */
@ExtendWith(OutputCaptureExtension.class)
@Import({ UnexpectedErrorApiTests.Boom.class, UnexpectedErrorApiTests.FailingFilter.class })
class UnexpectedErrorApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	FailingFilter filter;

	@Autowired
	Boom boom;

	@Test
	void unexpectedExceptionsBecomeGenericProblemJson(CapturedOutput output) throws Exception {
		ApiClient client = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		for (String path : new String[] { "/api/test-only/integrity", "/api/test-only/state" }) {
			MockHttpServletResponse response = client.get(path)
				.andExpect(status().isInternalServerError())
				.andReturn()
				.getResponse();
			assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
			assertThat(response.getHeader("Cache-Control")).contains("no-store");
			JsonNode body = ApiClient.json(response);
			assertThat(body.get("code").asString()).isEqualTo("internal_error");
			assertThat(body.get("detail").asString()).doesNotContain("SECRET").doesNotContain("insert");
			String id = response.getHeader(RequestIds.HEADER);
			assertThat(body.get("requestId").asString()).isEqualTo(id);
			// 처리기가 한 번 남기고, 예외가 filter 밖으로 나가지 않으므로 filter는 다시 남기지 않는다.
			assertThat(unhandledLines(output, id)).singleElement()
				.asString()
				.contains("\"ERROR\"", "method=GET route=" + path);
		}
	}

	@Test
	void securityRejectionsAreUnchanged() throws Exception {
		this.mvc.perform(get("/api/test-only/integrity")).andExpect(status().isUnauthorized());
		// 처리기 안에서 던진 AccessDeniedException도 500이 아니라 security의 403으로 간다.
		MockHttpServletResponse denied = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")))
			.get("/api/test-only/denied")
			.andExpect(status().isForbidden())
			.andReturn()
			.getResponse();
		assertThat(denied.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
	}

	@Test
	void clientDisconnectsAreNotLoggedAsErrors(CapturedOutput output) throws Exception {
		// 내려받기 도중 client가 끊으면 처리기는 쓸 곳 없는 본문을 만들지 않는다.
		MockHttpServletResponse response = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")))
			.get("/api/test-only/broken-pipe")
			.andReturn()
			.getResponse();
		assertThat(response.getContentAsString()).isEmpty();
		assertThat(unhandledLines(output, response.getHeader(RequestIds.HEADER))).isEmpty();

		// filter 단계의 끊김은 그대로 다시 던지되 ERROR로 남기지 않는다.
		assertThatThrownBy(() -> this.mvc.perform(get("/api/test-only/filter-abort")))
			.isInstanceOf(ClientAbortException.class);
		assertThat(unhandledLines(output, this.filter.responseRequestId.get())).isEmpty();
	}

	@Test
	void clientsThatDoNotAcceptJsonStillGetOneProblemAndOneLogLine(CapturedOutput output) throws Exception {
		MockHttpServletResponse response = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")))
			.send(get("/api/test-only/state").accept(MediaType.TEXT_HTML))
			.andExpect(status().isInternalServerError())
			.andReturn()
			.getResponse();
		assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		assertThat(unhandledLines(output, response.getHeader(RequestIds.HEADER))).hasSize(1);
	}

	@Test
	void failuresAfterTheResponseIsCommittedAreRethrownAndLoggedOnce(CapturedOutput output) {
		// 내려받기가 바이트를 이미 내보낸 뒤 실패하면 상태와 형식을 바꿀 수 없다. 처리기는 problem을 덧붙이지 않고 다시 던지며,
		// RequestIdFilter가 한 줄 남긴다.
		ApiClient client = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		assertThatThrownBy(() -> client.get("/api/test-only/download-fails-midway")).isInstanceOf(IOException.class)
			.hasMessage("storage read timed out");
		MockHttpServletResponse response = this.boom.committed.get();
		assertThat(response.isCommitted()).isTrue();
		assertThat(response.getContentAsByteArray()).isEqualTo(Boom.PARTIAL);
		assertThat(unhandledLines(output, this.filter.responseRequestId.get())).singleElement()
			.asString()
			.contains("\"ERROR\"", "route=/api/test-only/download-fails-midway", "java.io.IOException");
		assertThat(output.getAll()).doesNotContain("Failure in @ExceptionHandler");
	}

	private static List<String> unhandledLines(CapturedOutput output, String id) {
		return output.getAll().lines().filter((line) -> line.contains("unhandled exception requestId=" + id)).toList();
	}

	@TestConfiguration
	@RestController
	static class Boom {

		@GetMapping("/api/test-only/integrity")
		String integrity() {
			throw new DataIntegrityViolationException("insert into users values ('SECRET') Duplicate entry 'SECRET'");
		}

		@GetMapping("/api/test-only/state")
		String state() {
			throw new IllegalStateException("SECRET key=abc");
		}

		@GetMapping("/api/test-only/denied")
		String denied() {
			throw new AccessDeniedException("SECRET");
		}

		@GetMapping("/api/test-only/broken-pipe")
		String brokenPipe() throws IOException {
			throw new IOException("Broken pipe");
		}

		static final byte[] PARTIAL = new byte[16 * 1024];

		final AtomicReference<MockHttpServletResponse> committed = new AtomicReference<>();

		@GetMapping("/api/test-only/download-fails-midway")
		void downloadFailsMidway(HttpServletResponse response) throws IOException {
			this.committed.set(WebUtils.getNativeResponse(response, MockHttpServletResponse.class));
			response.setContentType("application/pdf");
			response.getOutputStream().write(PARTIAL);
			response.flushBuffer();
			throw new IOException("storage read timed out");
		}

	}

	/** 컨트롤러에 닿기 전, security filter보다도 앞에서 실패하는 filter. */
	@TestConfiguration
	@Order(Ordered.HIGHEST_PRECEDENCE + 2)
	static class FailingFilter extends OncePerRequestFilter {

		final AtomicReference<String> responseRequestId = new AtomicReference<>();

		@Override
		protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
				throws ServletException, IOException {
			this.responseRequestId.set(response.getHeader(RequestIds.HEADER));
			switch (request.getRequestURI()) {
				case "/api/test-only/filter-abort" -> throw new ClientAbortException(new IOException("Broken pipe"));
				default -> chain.doFilter(request, response);
			}
		}

	}

}
