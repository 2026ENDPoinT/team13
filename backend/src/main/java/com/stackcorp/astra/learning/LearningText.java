package com.stackcorp.astra.learning;

import com.stackcorp.astra.shared.ApiException;

/**
 * 출석 사유, 그룹 이름, 강의계획서 같은 사용자 입력의 일반 텍스트 검사. HTML로 해석하지 않고 그대로 저장하고 돌려준다.
 * 길이를 넘거나 제어 문자가 있으면 400이다. 줄바꿈은 여러 줄 필드에서만 받는다. 오류 문장에는 입력값을 넣지 않는다(SC-04).
 */
final class LearningText {

	private LearningText() {
	}

	/** 필수 한 줄 값. 앞뒤 공백을 지운다. */
	static String required(String value, String field, int max) {
		String text = value == null ? "" : value.strip();
		if (text.isEmpty() || text.length() > max || hasControl(text, false)) {
			throw ApiException.invalid(field + "은(는) 1자 이상 " + max + "자 이하의 한 줄 텍스트여야 합니다.");
		}
		return text;
	}

	/** 선택 값. 비었으면 null이다. */
	static String optional(String value, String field, int max, boolean multiline) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String text = multiline ? value : value.strip();
		if (text.length() > max || hasControl(text, multiline)) {
			throw ApiException.invalid(field + "은(는) " + max + "자 이하의 텍스트여야 합니다.");
		}
		return text;
	}

	private static boolean hasControl(String text, boolean allowLineBreaks) {
		return text.codePoints()
			.anyMatch((cp) -> (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029)
					&& !(allowLineBreaks && (cp == '\n' || cp == '\r' || cp == '\t')));
	}

}
