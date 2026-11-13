package com.stackcorp.astra.communication.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 답을 기다리는 강의실 질의응답 질문(담당자 메인의 확인할 일). 지우지 않은 질문 가운데 답글이 없고, 해결로 표시하지 않았고, FAQ가 아닌 것이다.
 * 담당자는 비공개 질문도 보므로 비공개 여부로 거르지 않는다. 과목 권한은 판정하지 않는다. 부르는 쪽이 담당을 확인한 과목만 넘긴다. 과목 수와
 * 관계없이 메서드마다 쿼리 한 번이다.
 */
public interface OpenQuestions {

	/** @return 과목 ID → 답을 기다리는 질문 수. 없는 과목은 결과에 없다 */
	Map<Long, Integer> counts(Collection<Long> courseIds);

	/** 오래 기다린 질문 순(작성 일시, 질문 ID)의 앞 limit개. */
	List<OpenQuestion> oldest(Collection<Long> courseIds, int limit);

	/**
	 * @param title 질문 제목. 익명 질문의 작성자는 담지 않는다
	 * @param createdAt 작성 일시
	 */
	record OpenQuestion(long questionId, long courseId, String title, LocalDateTime createdAt) {
	}

}
