package com.stackcorp.astra.shared;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 요청에 서버가 만든 requestId를 붙인다. security filter보다 먼저 실행해 filter 단계에서 거부한 401, 403에도 붙는다.
 * 오류 dispatch에서는 처음 정한 값을 다시 쓴다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

	@Override
	protected boolean shouldNotFilterErrorDispatch() {
		return false;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		String id = request.getAttribute(RequestIds.ATTRIBUTE) instanceof String existing ? existing
				: RequestIds.generate();
		request.setAttribute(RequestIds.ATTRIBUTE, id);
		response.setHeader(RequestIds.HEADER, id);
		String outer = MDC.get(RequestIds.MDC_KEY);
		MDC.put(RequestIds.MDC_KEY, id);
		try {
			chain.doFilter(request, response);
		}
		finally {
			if (outer == null) {
				MDC.remove(RequestIds.MDC_KEY);
			}
			else {
				MDC.put(RequestIds.MDC_KEY, outer);
			}
		}
	}

}
