package com.stackcorp.converter;

/**
 * 변환할 수 없는 입력. code는 응답 JSON의 code 값이다.
 */
public final class ConversionFailure extends Exception {

	private final String code;

	ConversionFailure(String code, String message) {
		super(message);
		this.code = code;
	}

	String code() {
		return this.code;
	}

}
