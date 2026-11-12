package com.stackcorp.converter;

import java.util.Optional;

/**
 * PDF로 바꿔 받는 office 형식(ADR-0033). 요청의 format 값은 이 목록에 있을 때만 받는다. HWP, HWPX는 넣지 않았다.
 */
public enum OfficeFormat {

	PPTX("pptx"), DOCX("docx"), XLSX("xlsx");

	private final String extension;

	OfficeFormat(String extension) {
		this.extension = extension;
	}

	/** 입력 파일 이름의 확장자. 엔진이 형식을 고르는 데 쓴다. */
	public String extension() {
		return this.extension;
	}

	static Optional<OfficeFormat> of(String value) {
		for (OfficeFormat format : values()) {
			if (format.extension.equals(value)) {
				return Optional.of(format);
			}
		}
		return Optional.empty();
	}

}
