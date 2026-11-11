package com.stackcorp.astra.communication;

import com.stackcorp.astra.shared.ApiException;

/**
 * 쪽지와 임시저장 글의 입력 검사. 글은 일반 텍스트로만 받고 HTML로 해석하지 않은 채 그대로 저장하고 돌려준다(COMM-07).
 * 오류 문장에는 입력값을 넣지 않는다(SC-04).
 */
final class PlainText {

	private PlainText() {
	}

	/** 앞뒤 공백을 지운 한 줄 글. 비어 있으면 거부한다. */
	static String line(String value, int max, String field) {
		String text = value == null ? "" : value.strip();
		if (text.isEmpty() || text.length() > max || hasControl(text, false)) {
			throw ApiException.invalid(field + "은 1자 이상 " + max + "자 이하여야 합니다.");
		}
		return text;
	}

	/** 비어 있어도 되는 한 줄 글. 공백을 지우지 않고 그대로 둔다(임시저장). */
	static String partialLine(String value, int max, String field) {
		String text = value == null ? "" : value;
		if (text.length() > max || hasControl(text, false)) {
			throw ApiException.invalid(field + "은 " + max + "자 이하의 한 줄 글이어야 합니다.");
		}
		return text;
	}

	/** 앞뒤 공백을 지운 한 줄 검색어. 비어 있으면 null이다. */
	static String query(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String text = value.strip();
		if (text.length() > max || hasControl(text, false)) {
			throw ApiException.invalid("q는 " + max + "자 이하여야 합니다.");
		}
		return text;
	}

	/** 줄바꿈과 tab 말고는 제어 문자를 받지 않는 여러 줄 글. 비어 있으면 빈 문자열이다. */
	static String block(String value, int max, String field) {
		String text = value == null ? "" : value;
		if (text.length() > max || hasControl(text, true)) {
			throw ApiException.invalid(field + "은 " + max + "자 이하의 일반 텍스트여야 합니다.");
		}
		return text;
	}

	/**
	 * 원본 화면의 글자 수 계산(getLength, 07 §5.1). ASCII는 1, 그 밖의 UTF-16 단위는 2로 센다. 쪽지 본문 상한 2000byte가 이 값이라
	 * 서버도 같은 방식으로 센다(COMM-12).
	 */
	static int screenBytes(String text) {
		int length = 0;
		for (int i = 0; i < text.length(); i++) {
			length += text.charAt(i) > 127 ? 2 : 1;
		}
		return length;
	}

	private static boolean hasControl(String text, boolean allowLineBreaks) {
		return text.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) && !(allowLineBreaks && (cp == '\n' || cp == '\r' || cp == '\t')));
	}

}
