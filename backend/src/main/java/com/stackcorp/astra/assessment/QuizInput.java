package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.stackcorp.astra.learning.api.QuizActivities.QuizActivityInput;

/**
 * 검사를 마친 퀴즈 설정. 활동 부분은 learning에, 나머지는 quizzes와 grade_items에 저장한다.
 *
 * @param resultReleaseAt 결과 공개 일시. null이면 비공개
 * @param maxScore 오프라인 시험의 배점. 온라인 시험은 문항 배점의 합이라 null이다
 */
record QuizInput(QuizActivityInput activity, Integer timeLimitMinutes, Integer attemptLimit, boolean shuffle,
		LocalDateTime resultReleaseAt, boolean answersVisible, BigDecimal maxScore) {

	/**
	 * 검사를 마친 문항.
	 *
	 * @param explanation 해설. 없으면 null
	 * @param options 보기 순서대로. 단답형은 비어 있다
	 */
	record QuestionInput(String type, String text, BigDecimal points, String explanation, List<OptionInput> options) {
	}

	record OptionInput(String text, boolean correct) {
	}

}
