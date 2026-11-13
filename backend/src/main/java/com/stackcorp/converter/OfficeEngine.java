package com.stackcorp.converter;

/**
 * office 문서를 PDF로 바꾸는 엔진(ADR-0033). 입력은 신뢰할 수 없는 파일로 본다. 결과 PDF는 {@link PdfRendering}이 PDF 입력과
 * 같은 검사를 거쳐 쪽 이미지로 바꾼다. 운영 구현은 {@link SofficeEngine}이다.
 */
public interface OfficeEngine {

	/**
	 * @return PDF 바이트
	 * @throws ConversionFailure 변환하지 못했을 때. code는 응답 JSON의 code 값이다
	 */
	byte[] toPdf(byte[] document, OfficeFormat format) throws ConversionFailure;

}
