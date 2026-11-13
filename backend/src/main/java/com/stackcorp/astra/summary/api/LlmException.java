package com.stackcorp.astra.summary.api;

/**
 * 모델 호출 실패. message에는 key 같은 비밀값을 넣지 않는다.
 */
public class LlmException extends RuntimeException {

	public enum Code {
		AUTH, RATE_LIMIT, BAD_REQUEST, SERVER, NETWORK, PROTOCOL
	}

	private final Code code;

	public LlmException(Code code, String message) {
		super(message);
		this.code = code;
	}

	public LlmException(Code code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public Code code() {
		return code;
	}

	/** 같은 요청을 다시 보내면 성공할 수 있는 실패인지. */
	public boolean retryable() {
		return code == Code.RATE_LIMIT || code == Code.SERVER || code == Code.NETWORK;
	}

}
