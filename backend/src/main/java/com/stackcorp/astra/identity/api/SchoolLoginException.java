package com.stackcorp.astra.identity.api;

/**
 * 학교 e-Class 학번·비밀번호 확인 실패. message에는 짧은 reason code만 둔다. 학번, 비밀번호, 학교 응답 본문을 넣지 않는다(SC-07).
 */
public class SchoolLoginException extends RuntimeException {

	/**
	 * {@code REJECTED}: 학교가 학번·비밀번호를 받지 않았다. {@code UNAVAILABLE}: 학교 서버에 닿지 못했거나 화면을 읽지 못했다.
	 * {@code EXPIRED}: 이 학교 session이 학교에서 끝났다(로그인 화면이 왔다). 다시 로그인해야 한다. {@code NOT_FOUND}: 학교가 그 항목을
	 * 보여 주지 않았다(지워졌거나 이 학생에게 열리지 않았다).
	 */
	public enum Kind {

		REJECTED, UNAVAILABLE, EXPIRED, NOT_FOUND

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
