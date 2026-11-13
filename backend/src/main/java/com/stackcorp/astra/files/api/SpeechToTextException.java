package com.stackcorp.astra.files.api;

/**
 * 음성 인식 실패. message에는 제공자 응답 본문, 오디오 내용, key를 넣지 않는다.
 */
public class SpeechToTextException extends RuntimeException {

	public enum Code {
		/** 오디오를 읽지 못했다(손상, 지원하지 않는 형식). */
		INVALID_AUDIO(false),
		/** key가 거부됐거나 잔액이 없거나 요청 설정이 잘못됐다. 다시 보내도 같다. */
		CONFIG(false),
		/** 요청 한도(429)에 걸렸다. */
		RATE_LIMIT(true),
		/** 제공자 서버 오류(5xx, 504 처리 시간 초과 포함). */
		SERVER(true),
		/** 연결하지 못했거나 제한 시간 안에 응답이 끝나지 않았다. */
		NETWORK(true),
		/** 응답을 해석하지 못했다. */
		PROTOCOL(true);

		private final boolean retryable;

		Code(boolean retryable) {
			this.retryable = retryable;
		}

		public boolean retryable() {
			return this.retryable;
		}
	}

	private final Code code;

	public SpeechToTextException(Code code, String message) {
		super(message);
		this.code = code;
	}

	public SpeechToTextException(Code code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public Code code() {
		return this.code;
	}

	public boolean retryable() {
		return this.code.retryable();
	}

}
