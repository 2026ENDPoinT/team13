package com.stackcorp.astra.identity.api;

/**
 * 학교 IdP 로그인 실패. reason은 로그에만 남기는 짧은 code다(예: {@code state}, {@code signature}). 응답에는 어느 검사에서
 * 실패했는지 드러내지 않는다. reason과 message에 token, code, 비밀값, 사용자 식별자를 넣지 않는다(SC-07).
 */
public class ExternalLoginException extends RuntimeException {

	private final String reason;

	public ExternalLoginException(String reason) {
		super(reason, null, false, false);
		this.reason = reason;
	}

	public ExternalLoginException(String reason, Throwable cause) {
		super(reason, cause, false, false);
		this.reason = reason;
	}

	public String reason() {
		return this.reason;
	}

}
