package com.stackcorp.astra.files.api;

public class ConversionException extends RuntimeException {

	public enum Code {
		ENCRYPTED, INVALID_PDF, TOO_MANY_PAGES, EMPTY, TOO_LARGE, UNAVAILABLE, FAILED
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
