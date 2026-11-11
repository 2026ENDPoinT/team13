package com.stackcorp.astra.shared.metrics;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 지표 tag 값 규칙(SC-07, ADR-0034). tag 값은 코드가 정한 몇 개의 이름(연산 이름, 결과 코드, 사유 코드)만 쓴다. 사용자, 과목, 파일 ID,
 * 실제 경로, 자유 텍스트는 tag에 넣지 않는다. 형식에 맞지 않는 값은 {@link #OTHER}로 바꿔 값의 종류가 늘지 않게 한다.
 */
public final class MetricTags {

	/** 형식에 맞지 않는 값 대신 쓰는 값. */
	public static final String OTHER = "other";

	/** 값이 없을 때 쓰는 값. */
	public static final String NONE = "none";

	private static final Pattern CODE = Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

	private MetricTags() {
	}

	/**
	 * @param value 연산 이름이나 결과 코드. 예: material.create, assignment-submit, idempotency_conflict
	 * @return 소문자 식별자면 그 값, null이면 {@link #NONE}, 그 밖에는 {@link #OTHER}
	 */
	public static String code(String value) {
		if (value == null) {
			return NONE;
		}
		String lower = value.toLowerCase(Locale.ROOT);
		return CODE.matcher(lower).matches() ? lower : OTHER;
	}

}
