package com.stackcorp.astra.summary.api;

import java.util.concurrent.TimeoutException;

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

	/**
	 * 제한 시간 안에 답을 받지 못한 실패인지({@link Code#NETWORK}의 한 경우). 동시 요청 자리를 기다리다 시간이 다 된 경우도 포함한다.
	 * adapter는 이때 {@link TimeoutException}을 원인으로 담는다.
	 */
	public boolean timedOut() {
		return code == Code.NETWORK && getCause() instanceof TimeoutException;
	}

}
