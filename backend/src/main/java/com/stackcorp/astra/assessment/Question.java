package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 퀴즈 문항과 보기. 응시가 하나라도 생기면 바꾸지 않는다(ADR-0021).
 *
 * @param questionNo 출제 순서(1부터)
 * @param type single, multiple, short
 * @param options 보기 번호 순서. 단답형은 비어 있다
 */
record Question(long questionId, int questionNo, String type, String text, BigDecimal points, String explanation,
		List<Option> options) {

	static final String SINGLE = "single";

	static final String MULTIPLE = "multiple";

	static final String SHORT = "short";

	/** 서버가 정답 보기로 채점하는 문항인지. 단답형은 담당자가 채점한다. */
	boolean objective() {
		return !SHORT.equals(this.type);
	}

	Set<Long> correctOptionIds() {
		return this.options.stream().filter(Option::correct).map(Option::optionId).collect(Collectors.toSet());
	}

	boolean hasOption(long optionId) {
		return this.options.stream().anyMatch((o) -> o.optionId() == optionId);
	}

	/**
	 * 선택 문항의 채점. 고른 보기 집합이 정답 보기 집합과 같을 때만 정답이다. 복수 선택도 부분 점수가 없다.
	 */
	boolean isCorrect(Set<Long> selected) {
		return !selected.isEmpty() && selected.equals(correctOptionIds());
	}

	record Option(long optionId, int optionNo, String text, boolean correct) {
	}

}
