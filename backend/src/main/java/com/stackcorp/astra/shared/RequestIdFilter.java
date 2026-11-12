package com.stackcorp.astra.shared;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.DisconnectedClientHelper;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 요청에 서버가 만든 requestId를 붙인다. security filter보다 먼저 실행해 filter 단계에서 거부한 401, 403에도 붙는다.
 * 오류 dispatch에서는 처음 정한 값을 다시 쓴다. 처리하지 못하고 밖으로 나가는 예외는 requestId가 MDC에 있는 동안 한 번 ERROR로
 * 남기고 그대로 다시 던진다. 응답 상태와 /error 처리는 바뀌지 않는다. client가 연결을 끊은 경우는 남기지 않는다.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdFilter extends OncePerRequestFilter {

	private static final Logger log = LoggerFactory.getLogger(RequestIdFilter.class);

	private static final String LOGGED_ATTRIBUTE = RequestIdFilter.class.getName() + ".logged";

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
		catch (Throwable ex) {
			logUnhandled(request, ex);
			throw ex;
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

	/**
	 * 처리하지 못한 예외를 requestId와 함께 ERROR로 한 번 남긴다. 경로는 {@link RequestLogFilter}처럼 처리한 경로 pattern만
	 * 남기고 실제 경로 값, query string, 본문은 남기지 않는다. 이미 남긴 예외(감싼 예외 포함)와 client 연결 끊김은 남기지 않는다.
	 */
	static void logUnhandled(HttpServletRequest request, Throwable ex) {
		if (DisconnectedClientHelper.isClientDisconnectedException(ex) || alreadyLogged(request, ex)) {
			return;
		}
		request.setAttribute(LOGGED_ATTRIBUTE, ex);
		String route = (request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) instanceof String p) ? p
				: "-";
		log.error("unhandled exception requestId={} method={} route={}", RequestIds.current(), request.getMethod(), route,
				ex);
	}

	private static boolean alreadyLogged(HttpServletRequest request, Throwable ex) {
		Object logged = request.getAttribute(LOGGED_ATTRIBUTE);
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t == logged) {
				return true;
			}
		}
		return false;
	}

}
