package com.stackcorp.astra.shared;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 요청 하나에 로그 한 줄을 남긴다. method, 처리한 경로 pattern, 상태 코드, 처리 시간만 남긴다. 실제 경로 값, query string,
 * header, cookie, 본문은 남기지 않는다(SC-07, architecture.md 9절). requestId는 이 filter보다 앞선 filter가 MDC에 넣고,
 * JSON 로그가 줄마다 함께 쓴다. 인증 단계에서 거부한 요청도 남도록 Spring Security보다 앞에 둔다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestLogFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RequestLogFilter.class);

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		long started = System.nanoTime();
		boolean failed = true;
		try {
			chain.doFilter(request, response);
			failed = false;
		}
		finally {
			long elapsed = System.nanoTime() - started;
			// 예외가 밖으로 나가면 상태 코드는 오류 처리 단계에서 정해진다. 그 경우는 500으로 남긴다.
			int status = failed ? HttpServletResponse.SC_INTERNAL_SERVER_ERROR : response.getStatus();
			String route = (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String p) ? p
					: "-";
			log.atInfo()
				.addKeyValue("http.request.method", request.getMethod())
				.addKeyValue("http.route", route)
				.addKeyValue("http.response.status_code", status)
				.addKeyValue("event.duration", elapsed)
				.log("request method={} route={} status={} durationMs={}", request.getMethod(), route, status,
						elapsed / 1_000_000);
		}
	}

}
