package com.stackcorp.astra.assessment;

import java.math.BigDecimal;

import com.stackcorp.astra.shared.ApiException;

/**
 * 사용자가 쓴 글과 점수의 입력 검사. 글은 일반 텍스트로만 받는다. HTML로 해석하지 않고 그대로 저장하고 돌려준다(ASSESS-11).
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

	/** 여러 줄 글. 비어 있으면 null이다. 줄바꿈과 tab 말고는 제어 문자를 받지 않는다. */
	static String block(String value, int max, String field) {
		if (value == null || value.isBlank()) {
			return null;
		}
		if (value.length() > max || hasControl(value, true)) {
			throw ApiException.invalid(field + "은 " + max + "자 이하의 일반 텍스트여야 합니다.");
		}
		return value;
	}

	/** 소수 둘째 자리까지의 0 이상 max 이하 값. */
	static BigDecimal points(BigDecimal value, BigDecimal max, String field) {
		if (value == null || value.signum() < 0 || value.compareTo(max) > 0 || value.stripTrailingZeros().scale() > 2) {
			throw ApiException.invalid(field + "은 0 이상 " + max.stripTrailingZeros().toPlainString()
					+ " 이하, 소수 둘째 자리까지여야 합니다.");
		}
		return value;
	}

	private static boolean hasControl(String text, boolean allowLineBreaks) {
		return text.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) && !(allowLineBreaks && (cp == '\n' || cp == '\r' || cp == '\t')));
	}

}
