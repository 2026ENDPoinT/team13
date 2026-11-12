package com.stackcorp.astra.files.upload;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 확장자와 파일 내용이 맞는지 앞부분의 signature로 확인하고 MIME을 정한다. client가 보낸 Content-Type은 쓰지 않는다(SC-08).
 * 검사 규칙이 없는 확장자는 거부한다. 악성코드 검사는 하지 않는다.
 */
final class ContentInspector {

	/** 검사에 필요한 앞부분 길이. */
	static final int HEAD_BYTES = 1024;

	private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] ZIP = { 'P', 'K', 3, 4 };

	private static final byte[] OLE = { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A,
			(byte) 0xE1 };

	private static final byte[] PNG = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

	private static final byte[] JPEG = { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF };

	private ContentInspector() {
	}

	/**
	 * @param head 파일의 앞 {@link #HEAD_BYTES} byte 이하
	 * @return 판정한 MIME. 내용이 확장자와 맞지 않거나 규칙이 없는 확장자면 비어 있는 값
	 */
	static Optional<String> detect(String extension, byte[] head) {
		return switch (extension) {
			case "pdf" -> when(contains(head, PDF), "application/pdf");
			case "png" -> when(startsWith(head, PNG), "image/png");
			case "jpg", "jpeg" -> when(startsWith(head, JPEG), "image/jpeg");
			case "zip" -> when(startsWith(head, ZIP), "application/zip");
			case "docx" -> when(startsWith(head, ZIP),
					"application/vnd.openxmlformats-officedocument.wordprocessingml.document");
			case "pptx" -> when(startsWith(head, ZIP),
					"application/vnd.openxmlformats-officedocument.presentationml.presentation");
			case "xlsx" -> when(startsWith(head, ZIP), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
			case "hwpx" -> when(startsWith(head, ZIP), "application/hwp+zip");
			case "hwp" -> when(startsWith(head, OLE), "application/x-hwp");
			case "txt" -> when(plainText(head), "text/plain");
			default -> Optional.empty();
		};
	}

	private static Optional<String> when(boolean matches, String mime) {
		return matches ? Optional.of(mime) : Optional.empty();
	}

	private static boolean startsWith(byte[] head, byte[] signature) {
		if (head.length < signature.length) {
			return false;
		}
		for (int i = 0; i < signature.length; i++) {
			if (head[i] != signature[i]) {
				return false;
			}
		}
		return true;
	}

	// PDF header는 파일 맨 앞이 아니어도 된다. 앞 1024 byte 안에서 찾는다.
	private static boolean contains(byte[] head, byte[] signature) {
		for (int at = 0; at + signature.length <= head.length; at++) {
			boolean match = true;
			for (int i = 0; i < signature.length && match; i++) {
				match = head[at + i] == signature[i];
			}
			if (match) {
				return true;
			}
		}
		return false;
	}

	private static boolean plainText(byte[] head) {
		for (byte b : head) {
			if (b == 0) {
				return false;
			}
		}
		return true;
	}

}
