package com.stackcorp.astra.files.api;

import java.util.Locale;

/**
 * 영상 변환 실패. message에는 영상 내용이나 transcoder 출력을 넣지 않는다.
 */
public class TranscodeException extends RuntimeException {

	public enum Code {
		/** 영상으로 읽을 수 없거나 영상 stream이 없다. */
		INVALID_MEDIA(false),
		/** 원본이나 결과가 크기 상한을 넘는다. */
		TOO_LARGE(false),
		/** 재생 길이가 상한을 넘는다. */
		TOO_LONG(false),
		/** 변환이 제한 시간을 넘었다. */
		TIMEOUT(false),
		/** transcoder에 닿지 못했다. */
		UNAVAILABLE(true),
		/** 변환 중이거나 응답을 읽다가 실패했다. */
		FAILED(true),
		/** 취소 요청으로 멈췄다. */
		CANCELLED(false);

		private final boolean retryable;

		Code(boolean retryable) {
			this.retryable = retryable;
		}

		/** 같은 입력으로 다시 시도하면 성공할 수 있는지. */
		public boolean retryable() {
			return this.retryable;
		}

		/** 원장에 남기는 값. */
		public String value() {
			return name().toLowerCase(Locale.ROOT);
		}
	}

	private final Code code;

	public TranscodeException(Code code, String message) {
		super(message);
		this.code = code;
	}

	public TranscodeException(Code code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public Code code() {
		return this.code;
	}

}
