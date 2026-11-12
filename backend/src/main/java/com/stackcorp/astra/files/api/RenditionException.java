package com.stackcorp.astra.files.api;

public class RenditionException extends RuntimeException {

	public enum Code {
		/** 파일이 없거나 삭제됐다. */
		NOT_FOUND,
		/** 검사를 통과하지 않았다. */
		NOT_READY,
		/** PDF가 아니다. */
		UNSUPPORTED,
		/** converter가 변환하지 못했다. detail은 ConversionException.Code 이름이다. */
		CONVERSION_FAILED,
		/** 저장된 rendition을 읽지 못했다. */
		STORAGE
	}

	private final Code code;

	private final String detail;

	public RenditionException(Code code, String detail, String message) {
		super(message);
		this.code = code;
		this.detail = detail;
	}

	public Code code() {
		return this.code;
	}

	/** 실패 세부 코드. 없으면 null. */
	public String detail() {
		return this.detail;
	}

}
