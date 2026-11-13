package com.stackcorp.astra.files.api;

/**
 * 글 생성 실패. message에는 제공자 응답 본문, 입력 글, key를 넣지 않는다.
 */
public class TextModelException extends RuntimeException {

	private final boolean retryable;

	/** @param retryable 같은 요청을 다시 보내면 성공할 수 있는지(요청 한도, 서버 오류, 연결 실패, 잘린 응답) */
	public TextModelException(boolean retryable, String message, Throwable cause) {
		super(message, cause);
		this.retryable = retryable;
	}

	public boolean retryable() {
		return this.retryable;
	}

}
