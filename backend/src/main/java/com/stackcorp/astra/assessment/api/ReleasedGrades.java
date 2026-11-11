package com.stackcorp.astra.assessment.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 학생에게 새로 공개된 본인 성적 항목(메인 화면의 "새 성적" 같은 알림용). 공개 판정은 성적 화면과 같다(ASSESS-06). 학생이 볼 수 없는
 * 항목(시작 전 과제, 평가 활동을 비공개로 둔 과목의 항목)과 점수를 받지 않은 항목은 넣지 않는다. 점수와 의견은 담지 않는다.
 * 과목 권한은 판정하지 않는다. 부르는 쪽이 학생으로 수강함을 확인한 과목만 넘긴다.
 * <p>
 * 쿼리는 성적 쪽 둘(설정, 후보)과, 과제 항목이 후보에 있는 과목마다 과제 조회 둘(learning의 과제 활동, 과제 설정)이다. 과목 수와 관계없이
 * 고정하려면 learning.api에 여러 과목의 과제 활동을 한 번에 읽는 조회가 필요하다.
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
	 * @param activityId 과제 항목의 학습활동. 직접 입력 항목이면 null
	 */
	record ReleasedGrade(long courseId, long gradeItemId, Long activityId, String itemName, LocalDateTime releasedAt) {
	}

}
