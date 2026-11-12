package com.stackcorp.astra.files.api;

import java.util.function.Consumer;

/**
 * 문서 변환 port. 구현은 integration 모듈에 있고 격리한 converter process를 부른다(SC-08).
 */
public interface PageConverter {

	/**
	 * PDF를 쪽 이미지와 쪽 텍스트로 바꾼다. 쪽은 1부터 순서대로 pages에 넘긴다.
	 *
	 * @throws ConversionException 변환할 수 없는 입력이거나 converter에 닿지 못했을 때
	 */
	ConvertedDocument convert(byte[] pdf, int maxPages, Consumer<ConvertedPage> pages);

	/**
	 * @param displayJpeg 화면용 쪽 이미지
	 * @param modelJpeg 모델 입력용 쪽 이미지. 긴 변 1024px 이하
	 */
	record ConvertedPage(int page, String text, boolean textTruncated, byte[] displayJpeg, byte[] modelJpeg) {
	}

	/**
	 * @param title PDF 문서 정보의 제목. 없으면 null
	 */
	record ConvertedDocument(int pageCount, String title) {
	}

}
