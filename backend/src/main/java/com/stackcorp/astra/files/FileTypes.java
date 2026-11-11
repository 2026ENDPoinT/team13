package com.stackcorp.astra.files;

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

}
