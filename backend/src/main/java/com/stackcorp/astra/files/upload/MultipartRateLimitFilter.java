package com.stackcorp.astra.files.upload;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.RateLimitedException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * multipart 본문을 임시 디스크로 받기 전에 판정한다. DispatcherServlet은 handler를 찾기 전에 multipart 본문 전체를 임시 디스크로
 * 받으므로, handler에서 요청 제한(SC-09)을 세면 제한을 넘긴 사용자도 요청마다 본문을 다 보낸 뒤에야 거부된다. 이 filter는 Spring
 * Security가 사용자를 정한 뒤, DispatcherServlet보다 앞에서 돈다.
 * <ul>
 * <li>multipart를 받는 업로드 경로(아래 세 곳)는 사용자·연산 요청 제한을 여기서 센다. 권한이 없는 시도와 뒤에서 거부하는 업로드도
 * 센다.</li>
 * <li>그 밖의 경로로 온 multipart는 받을 handler가 없으므로 본문을 받지 않고 415로 끝낸다. 이 판정은 CSRF 검사와 로그인 검사를
 * 통과해 이 filter까지 온 요청에만 적용된다. 로그인하지 않은 요청이 보호 경로로 오면 그 전에 401이다(본문은 읽지 않는다).</li>
 * </ul>
 * 거부 응답은 controller에서 던졌을 때와 같다(ApiExceptionHandler).
 */
@Component
@Order(Ordered.LOWEST_PRECEDENCE)
class MultipartRateLimitFilter extends OncePerRequestFilter {

	private final List<Limit> limits;

	private final RateLimiter rateLimiter;

	private final HandlerExceptionResolver errors;

	MultipartRateLimitFilter(UploadProperties properties, RateLimiter rateLimiter,
			@Qualifier("handlerExceptionResolver") HandlerExceptionResolver errors) {
		PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
		this.limits = List.of(
				new Limit(paths.matcher(HttpMethod.POST, "/api/uploads"), "upload", properties.rateLimit(),
						properties.rateWindow()),
				// 파일함에 올리기는 일반 업로드와 같은 제한을 함께 쓴다(FileboxUploads, ADR-0028 12번).
				new Limit(paths.matcher(HttpMethod.POST, "/api/filebox/files"), "upload", properties.rateLimit(),
						properties.rateWindow()),
				// 아바타 사진(files.avatar.AvatarImageController). 사용자당 10분에 10번.
				new Limit(paths.matcher(HttpMethod.POST, "/api/me/profile/image"), "avatar-upload", 10,
						Duration.ofMinutes(10)));
		this.rateLimiter = rateLimiter;
		this.errors = errors;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		// StandardServletMultipartResolver가 본문을 나누는 요청(Content-Type이 multipart/로 시작)만 본다.
		if (!StringUtils.startsWithIgnoreCase(request.getContentType(), "multipart/")) {
			chain.doFilter(request, response);
			return;
		}
		Limit limit = this.limits.stream().filter((l) -> l.matcher().matches(request)).findFirst().orElse(null);
		if (limit == null) {
			reject(request, response, new HttpMediaTypeNotSupportedException("multipart는 업로드 경로에서만 받는다."));
			return;
		}
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication != null && authentication.getPrincipal() instanceof Actor actor) {
			try {
				this.rateLimiter.check(limit.operation(), actor.userId(), limit.limit(), limit.window());
			}
			catch (RateLimitedException ex) {
				reject(request, response, ex);
				return;
			}
		}
		chain.doFilter(request, response);
	}

	private <E extends Exception> void reject(HttpServletRequest request, HttpServletResponse response, E ex) throws E {
		// 응답을 쓰지 못하면(예: 받을 수 없는 Accept) controller에서 던졌을 때처럼 오류 처리 단계로 넘긴다.
		if (this.errors.resolveException(request, response, null, ex) == null) {
			throw ex;
		}
	}

	private record Limit(RequestMatcher matcher, String operation, int limit, Duration window) {
	}

}
