package com.stackcorp.astra.shared;

import org.springframework.http.HttpStatus;

/**
 * 컨트롤러와 서비스가 던지는 API 오류. {@link ApiExceptionHandler}가 problem JSON으로 바꾼다.
 * detail에는 사용자에게 보여도 되는 문장만 쓴다. 저장 key, SQL, 다른 사용자의 ID를 넣지 않는다(SC-04).
 */
public class ApiException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public ApiException(HttpStatus status, String code, String detail) {
		super(detail);
		this.status = status;
		this.code = code;
	}

	/** 권한이 없거나 대상이 없을 때. 둘을 구분하지 않는다(SC-02). */
	public static ApiException notFound() {
		return new ApiException(HttpStatus.NOT_FOUND, "not_found", "찾을 수 없습니다.");
	}

	public static ApiException invalid(String detail) {
		return new ApiException(HttpStatus.BAD_REQUEST, "invalid_request", detail);
	}

	public HttpStatus status() {
		return this.status;
	}

	public String code() {
		return this.code;
	}

}
