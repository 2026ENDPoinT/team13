package com.stackcorp.astra.assessment.api;

import java.util.Collection;
import java.util.List;

/**
 * 채점을 기다리는 과제와 퀴즈(담당자 메인의 확인할 일). 지운 활동은 넣지 않고, 공개 여부와 관계없이 센다. 과목 권한은 판정하지 않는다. 부르는
 * 쪽이 담당을 확인한 과목만 넘긴다.
 * <ul>
 * <li>과제: 수강생 명단({@code catalog.api.CourseRosters})에 있는 학생의 최종 제출(현재 버전이 있는 개인 제출) 가운데 그 학생의 과제 성적
 * 항목 점수가 비어 있는 것. 과제 목록의 {@code submittedCount}에서 성적부의 {@code gradedCount}를 뺀 값과 같은 규칙이고, 점수를 받았지만
 * 제출하지 않은 학생과 명단에서 빠진 학생의 제출은 세지 않는다.</li>
 * <li>퀴즈: 수동 채점을 기다리는 응시(퀴즈 목록의 {@code awaitingGradingCount}).</li>
 * </ul>
 * 쿼리는 과목 수와 관계없이 다섯 이하다: 명단 1, 과제 1, 퀴즈 1, 과제 활동 1과 퀴즈 활동 1(learning.api). 기다리는 것이 없으면 활동 조회는
 * 하지 않는다.
 */
public interface GradingQueue {

	/** @return 기다리는 것이 하나 이상인 과제와 퀴즈. 과목, 종류(과제 먼저), 활동 ID 순 */
	List<PendingGrading> of(Collection<Long> courseIds);

	/**
	 * @param kind assignment|quiz
	 * @param targetId kind가 assignment면 과제 ID, quiz면 퀴즈 ID
	 * @param title 활동 제목
	 * @param count 채점을 기다리는 제출 수(과제) 또는 응시 수(퀴즈). 1 이상
	 */
	record PendingGrading(String kind, long courseId, long activityId, long targetId, String title, int count) {
	}

}
