package com.stackcorp.astra.identity.session;

import java.io.IOException;
import java.util.Set;

import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 상태를 바꾸는 요청의 출처를 검사한다(ADR-0004 4번). CSRF token 검사와 함께 쓴다.
 * 다른 사이트에서 온 요청과 허용하지 않은 Origin은 403이다. Origin이 없는 요청은 CSRF token 검사에 맡긴다.
 */
final class OriginCheckFilter extends OncePerRequestFilter {

	private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	private final Set<String> allowed;

	OriginCheckFilter(SessionProperties properties) {
		this.allowed = Set.copyOf(properties.allowedOrigins());
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!SAFE.contains(request.getMethod()) && !trusted(request)) {
			ProblemResponses.write(response, HttpServletResponse.SC_FORBIDDEN, "forbidden", "허용하지 않은 출처의 요청입니다.");
			return;
		}
		chain.doFilter(request, response);
	}

	private boolean trusted(HttpServletRequest request) {
		if ("cross-site".equals(request.getHeader("Sec-Fetch-Site"))) {
			return false;
		}
		String origin = request.getHeader("Origin");
		if (origin == null) {
			return true;
		}
		if (!this.allowed.isEmpty()) {
			return this.allowed.contains(origin);
		}
		int port = request.getServerPort();
		boolean defaultPort = ("http".equals(request.getScheme()) && port == 80)
				|| ("https".equals(request.getScheme()) && port == 443);
		String self = request.getScheme() + "://" + request.getServerName() + (defaultPort ? "" : ":" + port);
		return self.equalsIgnoreCase(origin);
	}

}
