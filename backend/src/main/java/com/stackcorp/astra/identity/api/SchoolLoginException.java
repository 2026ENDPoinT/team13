package com.stackcorp.astra.identity.api;

/**
 * 학교 e-Class 학번·비밀번호 확인 실패. message에는 짧은 reason code만 둔다. 학번, 비밀번호, 학교 응답 본문을 넣지 않는다(SC-07).
 */
public class SchoolLoginException extends RuntimeException {

	/** {@code REJECTED}: 학교가 학번·비밀번호를 받지 않았다. {@code UNAVAILABLE}: 학교 서버에 닿지 못했거나 화면을 읽지 못했다. */
	public enum Kind {

		REJECTED, UNAVAILABLE

	}

	private final Kind kind;

	public SchoolLoginException(Kind kind, String reason) {
		super(reason, null, false, false);
		this.kind = kind;
	}

	public SchoolLoginException(Kind kind, String reason, Throwable cause) {
		super(reason, cause, false, false);
		this.kind = kind;
	}

	public Kind kind() {
		return this.kind;
	}

}
