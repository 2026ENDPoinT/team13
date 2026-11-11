package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 성적 항목 한 행(grade_items). 과제 항목은 활동이 있고 과제 화면이 이름, 배점, 공개를 정한다. 직접 입력 항목은 활동이 없고 성적부가 모두
 * 정한다(ADR-0023).
 *
 * @param activityId 과제 항목의 학습활동. 직접 입력 항목이면 null
 * @param type grade_items.item_type: assignment, manual
 * @param weight 성적 반영 비율(%). 정하지 않았으면 null
 * @param revision 수정 번호. 수정 요청은 읽은 값을 보내야 한다
 */
record GradeItem(long gradeItemId, long courseId, Long activityId, String name, String type, BigDecimal maxScore,
		BigDecimal weight, String scoreVisibility, LocalDateTime releaseAt, long revision) {

	static final String MANUAL = "manual";

	/** 성적부에서 이름, 배점, 공개, 점수를 다루는 항목인지. 활동에 딸린 항목은 그 활동의 화면이 다룬다. */
	boolean manual() {
		return this.activityId == null;
	}

	boolean released(LocalDateTime now) {
		return released(this.scoreVisibility, this.releaseAt, now);
	}

	/**
	 * 학생에게 점수와 의견을 보여도 되는지. 과제 상세, 성적 화면, 다른 모듈에 주는 공개 목록이 모두 이 판정 하나를 쓴다(ASSESS-06, SEC-14).
	 * immediate는 지금, after_release는 공개 일시가 DB 시계로 지난 뒤, hidden은 언제나 비공개다.
	 */
	static boolean released(String visibility, LocalDateTime releaseAt, LocalDateTime now) {
		return "immediate".equals(visibility)
				|| ("after_release".equals(visibility) && releaseAt != null && !releaseAt.isAfter(now));
	}

}
