package com.stackcorp.astra.files.upload;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Optional;

/**
 * 업로드한 파일 이름의 정규화. 저장 key로는 쓰지 않고 표시와 내려받기 이름으로만 쓴다.
 * client는 이 값을 텍스트로만 그려야 한다(SEC-01). 서버는 경로 구분자, 제어 문자, 방향 제어 문자를 없앤다.
 * 파일함 폴더 이름과 ZIP 항목 이름도 같은 규칙으로 정규화한다(files.filebox).
 */
public final class FileNames {

	public static final int MAX_LENGTH = 255;

	private FileNames() {
	}

	/** @return 쓸 수 있는 이름이 남지 않으면 비어 있는 값 */
	public static Optional<String> sanitize(String original) {
		if (original == null) {
			return Optional.empty();
		}
		String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
		name = Normalizer.normalize(name, Normalizer.Form.NFC);
		StringBuilder out = new StringBuilder(name.length());
		name.codePoints().forEach((cp) -> {
			int type = Character.getType(cp);
			if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
					|| type == Character.PRIVATE_USE || type == Character.UNASSIGNED
					|| type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) {
				return;
			}
			if ("<>:\"|?*".indexOf(cp) >= 0) {
				out.append('_');
			}
			else if (Character.isWhitespace(cp) || Character.isSpaceChar(cp)) {
				out.append(' ');
			}
			else {
				out.appendCodePoint(cp);
			}
		});
		name = out.toString().replaceAll(" {2,}", " ").strip();
		while (name.startsWith(".")) {
			name = name.substring(1).strip();
		}
		while (name.endsWith(".")) {
			name = name.substring(0, name.length() - 1).strip();
		}
		if (name.length() > MAX_LENGTH) {
			String ext = extension(name).map((e) -> "." + e).orElse("");
			int end = MAX_LENGTH - ext.length();
			if (Character.isHighSurrogate(name.charAt(end - 1))) {
				end--;
			}
			name = name.substring(0, end).strip() + ext;
		}
		return name.isEmpty() ? Optional.empty() : Optional.of(name);
	}

	/** 마지막 점 뒤의 영문·숫자 확장자(소문자). */
	public static Optional<String> extension(String name) {
		int dot = name.lastIndexOf('.');
		if (dot < 1 || dot == name.length() - 1) {
			return Optional.empty();
		}
		String ext = name.substring(dot + 1).toLowerCase(Locale.ROOT);
		return ext.matches("[a-z0-9]{1,10}") ? Optional.of(ext) : Optional.empty();
	}

}
