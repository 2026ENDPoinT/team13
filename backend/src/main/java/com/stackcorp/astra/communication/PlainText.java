package com.stackcorp.astra.communication;

import com.stackcorp.astra.shared.ApiException;

/**
 * 게시판 글, 쪽지, 임시저장 글의 일반 텍스트 검사. HTML은 받지 않고 받은 글자를 그대로 저장하고 돌려준다(COMM-07). 제어 문자는 거부한다.
 * 본문만 줄바꿈과 탭을 허용한다. 오류 문장에는 입력값을 넣지 않는다(SC-04).
 */
final class PlainText {

	static final int MAX_QUERY = 100;

	private static final int EXCERPT = 150;

	private PlainText() {
	}

	/** 앞뒤 공백을 지운 제목. 비었거나 길거나 제어 문자가 있으면 400. */
	static String title(String raw, int max) {
		return line(raw, max, "title");
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

	/** 비어 있으면 null. */
	static String optionalBody(String raw, int max) {
		String text = raw == null || raw.isBlank() ? null : raw;
		if (text != null && (text.length() > max || hasControl(text, true))) {
			throw ApiException.invalid("body는 " + max + "자 이하여야 합니다.");
		}
		return text;
	}

	/** 비어 있으면 400. */
	static String requiredBody(String raw, int max) {
		String text = optionalBody(raw, max);
		if (text == null) {
			throw ApiException.invalid("body를 입력해 주세요.");
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

	/** 제목 검색어. 비어 있으면 null. */
	static String query(String raw) {
		return query(raw, MAX_QUERY);
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

	/** 목록 미리보기. 줄바꿈을 공백으로 바꾼 본문 앞 150자다. 서로게이트 쌍을 자르지 않는다. */
	static String excerpt(String text) {
		if (text == null) {
			return null;
		}
		String flat = text.replaceAll("\\s+", " ").strip();
		if (flat.codePointCount(0, flat.length()) <= EXCERPT) {
			return flat;
		}
		return flat.substring(0, flat.offsetByCodePoints(0, EXCERPT));
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

	static boolean hasControl(String text, boolean allowLineBreaks) {
		return text.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) && !(allowLineBreaks && (cp == '\n' || cp == '\r' || cp == '\t')));
	}

	/** LIKE 안에서 사용자 입력의 %, _, \를 글자 그대로 비교하게 escape한다. */
	static String likeLiteral(String text) {
		return text == null ? null : text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
