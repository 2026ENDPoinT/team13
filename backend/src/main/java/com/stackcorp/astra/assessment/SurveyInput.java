package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.List;

import com.stackcorp.astra.learning.api.SurveyActivities.SurveyActivityInput;

/**
 * 검사를 마친 강의실 설문 설정. 활동 부분은 learning에, 나머지는 surveys와 문항 테이블에 저장한다.
 *
 * @param anonymous 수정에서 null이면 그대로 둔다
 * @param resultsReleaseAt 결과 공개 일시. null이면 마감 뒤 바로 공개한다(resultsVisible일 때)
 * @param questions 순서대로의 문항. 수정에서 null이면 그대로 둔다
 */
record SurveyInput(SurveyActivityInput activity, Boolean anonymous, boolean resultsVisible,
		LocalDateTime resultsReleaseAt, List<QuestionInput> questions) {

	/**
	 * @param type single, multiple, scale, essay
	 * @param minSelect multiple만. 답할 때 고를 최소 보기 수
	 * @param maxSelect multiple만. 답할 때 고를 최대 보기 수
	 * @param options 보기 문구. 순서가 보기 번호다. scale이면 보기 번호(1부터)가 척도 값이다. essay면 비어 있다
	 */
	record QuestionInput(String type, String text, boolean required, Integer minSelect, Integer maxSelect,
			List<String> options) {
	}

}
