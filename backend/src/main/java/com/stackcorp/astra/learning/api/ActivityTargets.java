package com.stackcorp.astra.learning.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * 평가 활동(과제, 퀴즈, 강의실 설문)의 유형별 ID. 주차 목록, 할 일, 캘린더의 행이 활동 ID만으로 과제·퀴즈·설문 화면에 바로 이어지게 한다.
 * 평가 설정은 assessment가 가지고 learning은 assessment에 의존하지 않으므로(architecture.md 4.1) learning이 이 port를 정의하고
 * assessment가 구현한다. 강의 자료와 영상 활동은 활동 ID가 곧 그 화면의 ID라 여기에 없다.
 * <p>
 * 권한과 공개 여부는 판정하지 않는다. 부르는 쪽이 그 사용자에게 보이는 활동의 ID만 넘긴다. 활동 수와 관계없이 쿼리 한 번이다.
 */
public interface ActivityTargets {

	/** @return 넘긴 활동 가운데 평가 활동만. 활동 ID → 대상 */
	Map<Long, Target> of(Collection<Long> activityIds);

	/**
	 * @param kind assignment, quiz, survey
	 * @param id 그 유형의 ID(assignmentId, quizId, surveyId)
	 * @param lateUntil 지각 제출을 받는 과제의 지각 마감. 지각을 받지 않으면 null
	 */
	record Target(String kind, long id, LocalDateTime lateUntil) {
	}

}
