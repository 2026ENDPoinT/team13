package com.stackcorp.astra.files.api;

import java.util.function.Consumer;

/**
 * 문서 변환 port. 구현은 integration 모듈에 있고 격리한 converter process를 부른다(SC-08).
 */
public interface PageConverter {

	/**
	 * 문서를 쪽 이미지와 쪽 텍스트로 바꾼다. office 문서는 converter 안에서 PDF로 바꾼 뒤 같은 길로 처리한다(ADR-0033). 쪽은 1부터
	 * 순서대로 pages에 넘긴다.
	 *
	 * @param format 저장할 때 판정한 형식. converter가 내용을 다시 확인한다
	 * @throws ConversionException 변환할 수 없는 입력이거나 converter에 닿지 못했을 때
	 */
	ConvertedDocument convert(byte[] document, SourceFormat format, int maxPages, Consumer<ConvertedPage> pages);

	/** converter에 보내는 입력 형식. code는 converter의 format 값이다. */
	enum SourceFormat {

		PDF("pdf"), PPTX("pptx"), DOCX("docx"), XLSX("xlsx");

		private final String code;

		SourceFormat(String code) {
			this.code = code;
		}

		public String code() {
			return this.code;
		}

	}

	/**
	 * @param displayJpeg 화면용 쪽 이미지
	 * @param modelJpeg 모델 입력용 쪽 이미지. 긴 변 1024px 이하
	 */
	record ConvertedPage(int page, String text, boolean textTruncated, byte[] displayJpeg, byte[] modelJpeg) {
	}

	/**
	 * @param title PDF 문서 정보의 제목. 없으면 null
	 * @param thumbnailJpeg 첫 쪽 썸네일. 긴 변 360px 이하
	 */
	record ConvertedDocument(int pageCount, String title, byte[] thumbnailJpeg) {
	}

}
