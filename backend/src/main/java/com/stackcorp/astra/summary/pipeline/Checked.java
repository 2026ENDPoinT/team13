package com.stackcorp.astra.summary.pipeline;

import java.util.List;

/**
 * 출력 검사 결과. errors가 있으면 value는 null이다. soft는 받아들일 수 있지만 고치면 좋은 문제다.
 */
record Checked<T>(T value, List<String> errors, List<String> soft) {

	static <T> Checked<T> failed(List<String> errors, List<String> soft) {
		return new Checked<>(null, errors, soft);
	}

}
