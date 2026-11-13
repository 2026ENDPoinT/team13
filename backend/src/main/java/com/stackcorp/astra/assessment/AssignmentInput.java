package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import com.stackcorp.astra.learning.api.ReportActivities.ReportActivityInput;

/**
 * 검사를 마친 과제 설정. 활동 부분은 learning에, 나머지는 assignments와 grade_items에 저장한다.
 *
 * @param lateUntil 지각 정책이 allow_until일 때만 값이 있다
 */
record AssignmentInput(ReportActivityInput activity, boolean allowText, boolean allowFile, String latePolicy,
		LocalDateTime lateUntil, BigDecimal maxScore, boolean scoresReleased) {
}
