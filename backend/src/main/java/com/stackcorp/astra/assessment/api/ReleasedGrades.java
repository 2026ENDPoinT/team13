package com.stackcorp.astra.assessment.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 학생에게 새로 공개된 본인 성적 항목(메인 화면의 새 성적). 공개 판정은 성적 화면과 같다(ASSESS-06): 과제와 직접 입력 항목은 항목의 공개
 * 정책, 퀴즈 항목은 퀴즈의 결과 공개 판정이다. 학생이 볼 수 없는 항목(지운 활동, 공개 전·시작 전 과제와 퀴즈, 평가 활동을 비공개로 둔
 * 과목의 항목)과 점수를 받지 않은 항목은 넣지 않는다. 점수와 의견은 담지 않는다. 과목 권한은 판정하지 않는다. 부르는 쪽이 학생으로 수강함을
 * 확인한 과목만 넘긴다.
 * <p>
 * 쿼리는 과목 수와 항목 수에 관계없이 다섯 이하다: 성적 설정 1, 후보 1, 과제 활동 1(learning.api), 퀴즈 활동 1(learning.api), 퀴즈 설정
 * 1. 후보에 과제나 퀴즈가 없으면 그 조회는 하지 않는다.
 */
public interface ReleasedGrades {

	/** 한 번에 돌려주는 최대 건수. */
	int MAX_LIMIT = 20;

	/**
	 * 공개 일시가 since 뒤이고 now 이전인 항목. 최근 공개순({@code releasedAt}, {@code gradeItemId}의 역순)이다.
	 * @param now DB 시계 값({@code SELECT CURRENT_TIMESTAMP(3)})
	 * @param limit 1 이상 {@link #MAX_LIMIT} 이하
	 */
	List<ReleasedGrade> releasedSince(long studentId, Collection<Long> courseIds, LocalDateTime since,
			LocalDateTime now, int limit);

	/**
	 * @param itemType assignment, quiz, manual
	 * @param activityId 과제·퀴즈 항목의 학습활동. 직접 입력 항목이면 null
	 */
	record ReleasedGrade(long courseId, long gradeItemId, String itemType, Long activityId, String itemName,
			LocalDateTime releasedAt) {
	}

}
