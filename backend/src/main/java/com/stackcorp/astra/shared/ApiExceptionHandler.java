package com.stackcorp.astra.shared;

import org.apache.tomcat.util.http.fileupload.FileUploadException;
import org.apache.tomcat.util.http.fileupload.InvalidFileNameException;
import org.apache.tomcat.util.http.fileupload.MultipartStream.IllegalBoundaryException;
import org.apache.tomcat.util.http.fileupload.MultipartStream.MalformedStreamException;
import org.apache.tomcat.util.http.fileupload.impl.InvalidContentTypeException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.util.DisconnectedClientHelper;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 모든 API 오류를 RFC 9457 problem JSON으로 응답한다. 오류 응답도 캐시하지 않는다.
 * Spring MVC가 만든 오류(본문 파싱 실패, 형식이 틀린 경로 변수 등)의 detail은 입력값을 되돌려 줄 수 있어 고정 문장으로 바꾼다.
 */
@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	@ExceptionHandler(ApiException.class)
	ResponseEntity<ProblemDetail> api(ApiException ex) {
		Builder problem = problem(ex.status(), ex.code(), ex.getMessage());
		if (ex.retryAfter() != null) {
			problem.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, ex.retryAfter().toSeconds())));
		}
		return problem.build();
	}

	@ExceptionHandler(RateLimitedException.class)
	ResponseEntity<ProblemDetail> rateLimited(RateLimitedException ex) {
		return problem(HttpStatus.TOO_MANY_REQUESTS, "rate_limited", "요청이 너무 많습니다. 잠시 뒤 다시 시도해 주세요.")
			.header(HttpHeaders.RETRY_AFTER, Long.toString(Math.max(1, ex.retryAfter().toSeconds())))
			.build();
	}

	/**
	 * 예상하지 못한 예외는 기본 /error 대신 고정 문장의 500 problem JSON으로 응답한다. 예외 메시지(SQL, 입력값, key)는 응답에 싣지
	 * 않고 requestId와 함께 서버 로그에만 남긴다. Spring Security 예외는 원래 흐름(401/403)으로 돌려보낸다. client가 연결을 끊었으면
	 * 쓸 곳이 없으므로 로그도 본문도 남기지 않는다. 응답이 이미 나가기 시작했으면(내려받기 도중 실패 등) 상태와 형식을 바꿀 수 없으므로
	 * 본문을 쓰지 않고 다시 던진다. 로그는 {@link RequestIdFilter}가 한 번 남긴다.
	 */
	@ExceptionHandler(Exception.class)
	ResponseEntity<ProblemDetail> unexpected(Exception ex, HttpServletRequest request, HttpServletResponse response)
			throws Exception {
		if (ex instanceof AccessDeniedException || ex instanceof AuthenticationException) {
			throw ex;
		}
		if (DisconnectedClientHelper.isClientDisconnectedException(ex)) {
			return null;
		}
		if (response.isCommitted()) {
			throw ex;
		}
		RequestIdFilter.logUnhandled(request, ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "요청을 처리하지 못했습니다.").build();
	}

	/**
	 * multipart 본문을 나누지 못한 요청(DispatcherServlet이 handler를 찾기 전에 나눈다). client가 보낸 본문이 multipart 형식이 아니면
	 * 400이다: boundary가 없거나, 끝 boundary 전에 본문이 끝났거나, Content-Type이 받을 수 있는 multipart가 아니다. 임시 디스크에 받지
	 * 못한 것(spool 위치가 없다, 디스크가 찼다)처럼 서버 쪽 실패는 {@link #unexpected}와 같은 500이다. 크기 초과는
	 * {@link #handleMaxUploadSizeExceededException}의 413이고, {@code file} part가 없는 것은 handler의 400이다.
	 */
	@ExceptionHandler(MultipartException.class)
	ResponseEntity<ProblemDetail> multipart(MultipartException ex, HttpServletRequest request,
			HttpServletResponse response) throws Exception {
		if (malformedByClient(ex)) {
			return problem(HttpStatus.BAD_REQUEST, "invalid_request", "multipart 본문 형식이 올바르지 않습니다.").build();
		}
		return unexpected(ex, request, response);
	}

	/**
	 * Tomcat의 multipart parser가 본문 자체를 거부했는지. 본문을 읽거나 임시 파일에 쓰다 난 입출력 오류는 parser가
	 * {@code IOFileUploadException}이나 {@code FileUploadException}의 cause로 감싸므로, cause가 없는 parser 오류와 형식 오류
	 * 예외만 client 탓으로 본다: 끝나지 않은 본문, 받을 수 없는 Content-Type, NUL이 든 파일 이름, 바깥과 길이가 다른 안쪽
	 * multipart boundary.
	 */
	static boolean malformedByClient(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
			if (cause instanceof MalformedStreamException || cause instanceof InvalidContentTypeException
					|| cause instanceof InvalidFileNameException || cause instanceof IllegalBoundaryException
					|| (cause.getClass() == FileUploadException.class && cause.getCause() == null)) {
				return true;
			}
		}
		return false;
	}

	@Override
	protected ResponseEntity<Object> handleMaxUploadSizeExceededException(MaxUploadSizeExceededException ex,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		return generic(problem(HttpStatus.CONTENT_TOO_LARGE, "too_large", "파일이 너무 큽니다.").build());
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		return generic(forStatus(statusCode.value()));
	}

	/**
	 * 상태 코드만으로 정하는 problem. Spring MVC 예외와 servlet container의 /error dispatch({@link ProblemErrorController})가 같은
	 * code와 고정 문장을 쓴다. 5xx와 알 수 없는 상태는 500 internal_error다.
	 */
	static ResponseEntity<ProblemDetail> forStatus(int statusCode) {
		HttpStatus status = HttpStatus.resolve(statusCode);
		if (status == null || status.is5xxServerError()) {
			return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "요청을 처리하지 못했습니다.").build();
		}
		Builder problem = switch (status) {
			case NOT_FOUND -> problem(status, "not_found", "찾을 수 없습니다.");
			case METHOD_NOT_ALLOWED -> problem(status, "method_not_allowed", "허용하지 않는 method입니다.");
			case UNSUPPORTED_MEDIA_TYPE -> problem(status, "unsupported_media_type", "지원하지 않는 본문 형식입니다.");
			case NOT_ACCEPTABLE -> problem(status, "not_acceptable", "요청한 형식으로 응답할 수 없습니다.");
			case BAD_REQUEST -> problem(status, "invalid_request", "요청 형식이 올바르지 않습니다.");
			default -> problem(status, "request_rejected", "요청을 처리할 수 없습니다.");
		};
		return problem.build();
	}

	private static ResponseEntity<Object> generic(ResponseEntity<ProblemDetail> response) {
		return ResponseEntity.status(response.getStatusCode()).headers(response.getHeaders()).body(response.getBody());
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
