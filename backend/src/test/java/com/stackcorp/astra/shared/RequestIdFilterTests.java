package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.DispatcherType;

/**
 * 요청 처리 중의 로그는 MDC의 requestId를 함께 남긴다. 그 값은 응답 header와 같고, 요청이 끝나면 지워진다.
 */
class RequestIdFilterTests {

	@Test
	void logsUnderTheIdItReturnsAndIgnoresTheIdTheClientSent() throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/me");
		request.addHeader(RequestIds.HEADER, "client-chosen");
		MockHttpServletResponse response = new MockHttpServletResponse();
		AtomicReference<String> logged = new AtomicReference<>();

		new RequestIdFilter().doFilter(request, response, (req, res) -> logged.set(MDC.get(RequestIds.MDC_KEY)));

		assertThat(response.getHeader(RequestIds.HEADER)).matches("[0-9a-f-]{36}").isEqualTo(logged.get());
		assertThat(MDC.get(RequestIds.MDC_KEY)).isNull();
	}

	@Test
	void keepsTheSameIdWhenTheContainerDispatchesTheErrorPage() throws Exception {
		RequestIdFilter filter = new RequestIdFilter();
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/uploads");
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, (req, res) -> {
		});
		String first = response.getHeader(RequestIds.HEADER);
		AtomicReference<String> logged = new AtomicReference<>();

		request.setDispatcherType(DispatcherType.ERROR);
		filter.doFilter(request, response, (req, res) -> logged.set(MDC.get(RequestIds.MDC_KEY)));

		assertThat(logged.get()).isEqualTo(first);
		assertThat(response.getHeader(RequestIds.HEADER)).isEqualTo(first);
	}

}
