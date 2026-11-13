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

	private static final byte[] EBML = { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3 };

	private static final byte[] ID3 = "ID3".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] OGG = "OggS".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] FLAC = "fLaC".getBytes(StandardCharsets.US_ASCII);

	private static final byte[] RIFF = "RIFF".getBytes(StandardCharsets.US_ASCII);

	private static final String QUICKTIME_BRAND = "qt  ";

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
			case "mp4" -> when(isoBrand(head).filter((brand) -> !QUICKTIME_BRAND.equals(brand)).isPresent(), "video/mp4");
			case "mov" -> when(isoBrand(head).filter(QUICKTIME_BRAND::equals).isPresent(), "video/quicktime");
			case "webm" -> when(ebmlDocType(head).filter("webm"::equals).isPresent(), "video/webm");
			case "mkv" -> when(ebmlDocType(head).filter("matroska"::equals).isPresent(), "video/x-matroska");
			case "m4a" -> when(isoBrand(head).filter((brand) -> !QUICKTIME_BRAND.equals(brand)).isPresent(), "audio/mp4");
			case "mp3" -> when(startsWith(head, ID3) || mpegAudioFrame(head), "audio/mpeg");
			case "aac" -> when(startsWith(head, ID3) || adtsFrame(head), "audio/aac");
			case "ogg" -> when(startsWith(head, OGG), "audio/ogg");
			case "flac" -> when(startsWith(head, FLAC), "audio/flac");
			case "wav" -> when(startsWith(head, RIFF) && head.length >= 12
					&& "WAVE".equals(new String(head, 8, 4, StandardCharsets.US_ASCII)), "audio/wav");
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

	// MPEG 오디오(mp3) frame header: 11 bit sync, 예약 version(01)이 아니고, layer가 00(ADTS·예약)이 아니고, bitrate index가 1111이 아니다.
	private static boolean mpegAudioFrame(byte[] head) {
		return head.length >= 3 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0 && (head[1] & 0x18) != 0x08
				&& (head[1] & 0x06) != 0 && (head[2] & 0xF0) != 0xF0;
	}

	// ADTS(AAC) frame header: 12 bit sync와 layer 00.
	private static boolean adtsFrame(byte[] head) {
		return head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xF6) == 0xF0;
	}

	private static boolean plainText(byte[] head) {
		for (byte b : head) {
			if (b == 0) {
				return false;
			}
		}
		return true;
	}

	// ISO base media(mp4, mov): 첫 box가 ftyp이고 크기가 정상이면 major brand를 돌려준다.
	private static Optional<String> isoBrand(byte[] head) {
		if (head.length < 16 || head[4] != 'f' || head[5] != 't' || head[6] != 'y' || head[7] != 'p') {
			return Optional.empty();
		}
		long size = ((head[0] & 0xFFL) << 24) | ((head[1] & 0xFFL) << 16) | ((head[2] & 0xFFL) << 8) | (head[3] & 0xFFL);
		if (size < 16 || size > 4096) {
			return Optional.empty();
		}
		return Optional.of(new String(head, 8, 4, StandardCharsets.ISO_8859_1));
	}

	// Matroska(mkv, webm): EBML header 안의 DocType(0x4282) 값을 돌려준다. 크기는 1 byte vint만 받는다.
	private static Optional<String> ebmlDocType(byte[] head) {
		if (!startsWith(head, EBML)) {
			return Optional.empty();
		}
		int end = Math.min(head.length, 64);
		for (int at = EBML.length; at + 3 <= end; at++) {
			if (head[at] == 0x42 && head[at + 1] == (byte) 0x82 && (head[at + 2] & 0x80) != 0) {
				int length = head[at + 2] & 0x7F;
				if (length < 1 || at + 3 + length > end) {
					return Optional.empty();
				}
				return Optional.of(new String(head, at + 3, length, StandardCharsets.US_ASCII));
			}
		}
		return Optional.empty();
	}

}
