package com.stackcorp.astra.files.api;

public class ConversionException extends RuntimeException {

	public enum Code {
		ENCRYPTED, INVALID_PDF, TOO_MANY_PAGES, EMPTY, TOO_LARGE, UNAVAILABLE, FAILED,
		/** converter가 받지 않는 형식이거나 office 엔진이 없는 converter다. */
		UNSUPPORTED_FORMAT,
		/** 변환이 제한 시간을 넘었다. */
		TIMEOUT,
		/** 기다리던 thread가 중단됐다(process 종료). */
		INTERRUPTED
	}

	private final Code code;

	public ConversionException(Code code, String message) {
		super(message);
		this.code = code;
	}

	public ConversionException(Code code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public Code code() {
		return this.code;
	}

}
