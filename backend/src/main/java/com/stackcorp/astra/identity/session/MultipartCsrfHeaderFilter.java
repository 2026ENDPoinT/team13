package com.stackcorp.astra.identity.session;

import java.io.IOException;
import java.util.Set;

import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 상태를 바꾸는 multipart 요청은 CSRF token을 header로만 받는다(ADR-0004 4번). CSRF 검사는 header가 없으면 요청 parameter
 * {@code _csrf}에서 token을 찾는데, multipart 요청의 parameter를 읽으면 서블릿 컨테이너가 본문 전체(multipart 요청 상한까지)를 임시
 * 디스크로 받는다. 로그인 전에도 그렇게 되므로, header가 없으면 본문을 읽지 않고 CSRF 검사가 거부할 때와 같은 403으로 끝낸다. 화면은
 * 언제나 header로 보낸다.
 */
final class MultipartCsrfHeaderFilter extends OncePerRequestFilter {

	/** {@code csrf.spa()}가 쓰는 CookieCsrfTokenRepository의 header 이름. */
	private static final String HEADER = "X-XSRF-TOKEN";

	private static final Set<String> SAFE = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		if (!SAFE.contains(request.getMethod()) && StringUtils.startsWithIgnoreCase(request.getContentType(), "multipart/")
				&& !StringUtils.hasText(request.getHeader(HEADER))) {
			ProblemResponses.write(response, HttpServletResponse.SC_FORBIDDEN, "forbidden",
					"요청을 처리할 수 없습니다. CSRF token을 확인해 주세요.");
			return;
		}
		chain.doFilter(request, response);
	}

}
