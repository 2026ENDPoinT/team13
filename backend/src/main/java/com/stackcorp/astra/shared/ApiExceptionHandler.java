package com.stackcorp.astra.shared;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * 모든 API 오류를 RFC 9457 problem JSON으로 응답한다. 오류 응답도 캐시하지 않는다.
 * Spring MVC가 만든 오류(본문 파싱 실패, 형식이 틀린 경로 변수 등)의 detail은 입력값을 되돌려 줄 수 있어 고정 문장으로 바꾼다.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> api(ApiException ex) {
		return problem(ex.status(), ex.code(), ex.getMessage()).build();
	}

	@ExceptionHandler(RateLimitedException.class)
	ResponseEntity<ProblemDetail> rateLimited(RateLimitedException ex) {
		return problem(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요.")
			.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, ex.retryAfter().toSeconds())))
			.build();
	}

	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return generic(HttpStatus.CONTENT_TOO_LARGE, "too_large", "파일이 너무 큽니다.");
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		HttpStatus status = HttpStatus.resolve(statusCode.value());
		if (status == null || status.is5xxServerError()) {
			return generic(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "요청을 처리하지 못했습니다.");
		}
		return switch (status) {
			case NOT_FOUND -> generic(status, "not_found", "찾을 수 없습니다.");
			case METHOD_NOT_ALLOWED -> generic(status, "method_not_allowed", "허용하지 않는 method입니다.");
			case UNSUPPORTED_MEDIA_TYPE -> generic(status, "unsupported_media_type", "지원하지 않는 본문 형식입니다.");
			case NOT_ACCEPTABLE -> generic(status, "not_acceptable", "요청한 형식으로 응답할 수 없습니다.");
			case BAD_REQUEST -> generic(status, "invalid_request", "요청 형식이 올바르지 않습니다.");
			default -> generic(status, "request_rejected", "요청을 처리할 수 없습니다.");
		};
	}

	private static ResponseEntity<Object> generic(HttpStatus status, String code, String detail) {
		ResponseEntity<ProblemDetail> response = problem(status, code, detail).build();
		return ResponseEntity.status(status).headers(response.getHeaders()).body(response.getBody());
	}

	private static Builder problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return new Builder(ResponseEntity.status(status).cacheControl(CacheControl.noStore()), problem);
	}

	private record Builder(ResponseEntity.BodyBuilder response, ProblemDetail body) {

		Builder header(String name, String value) {
			this.response.header(name, value);
			return this;
		}

		ResponseEntity<ProblemDetail> build() {
			return this.response.body(this.body);
		}

	}

}
