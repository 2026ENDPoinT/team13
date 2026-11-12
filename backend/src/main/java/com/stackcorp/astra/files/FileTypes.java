package com.stackcorp.astra.files;

import java.util.Optional;

import com.stackcorp.astra.files.api.PageConverter.SourceFormat;

/**
 * 파일 형식 판정. 저장할 때 판정한 MIME을 먼저 본다. 없으면(업로드 API 이전의 파일) 선언한 MIME과 확장자를 본다.
 */
public final class FileTypes {

	private FileTypes() {
	}

	public static boolean pdf(String detectedMimeType, String declaredMimeType, String extension) {
		if (detectedMimeType != null) {
			return "application/pdf".equals(detectedMimeType);
		}
		return "application/pdf".equals(declaredMimeType) || "pdf".equalsIgnoreCase(extension);
	}

	/**
	 * 미리보기로 바꿀 형식(ADR-0033). office 형식은 저장할 때 판정한 MIME으로만 정한다. 판정 값이 없는 파일은 PDF만 본다. HWP,
	 * HWPX, 이미지, 압축 파일, 텍스트는 바꾸지 않는다.
	 */
	static Optional<SourceFormat> previewFormat(String detectedMimeType, String declaredMimeType, String extension) {
		if (pdf(detectedMimeType, declaredMimeType, extension)) {
			return Optional.of(SourceFormat.PDF);
		}
		if (detectedMimeType == null) {
			return Optional.empty();
		}
		return switch (detectedMimeType) {
			case "application/vnd.openxmlformats-officedocument.presentationml.presentation" ->
				Optional.of(SourceFormat.PPTX);
			case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
				Optional.of(SourceFormat.DOCX);
			case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> Optional.of(SourceFormat.XLSX);
			default -> Optional.empty();
		};
	}

	/**
	 * inline으로 보여도 되는 이미지의 MIME(EP-0130, FILES-09). 저장할 때 서버가 판정한 값만 본다. SVG처럼 script를 담을 수 있는
	 * 형식과 판정 값이 없는 파일은 넣지 않는다.
	 */
	static Optional<String> inlineImage(String detectedMimeType) {
		return "image/png".equals(detectedMimeType) || "image/jpeg".equals(detectedMimeType)
				? Optional.of(detectedMimeType) : Optional.empty();
	}

}
