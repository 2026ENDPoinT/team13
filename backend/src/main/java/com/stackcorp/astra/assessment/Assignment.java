package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

import com.stackcorp.astra.learning.api.ReportActivities.ReportActivity;

/**
 * 과제. 제목·설명·기간은 learning의 활동이, 제출 형태·지각 정책은 assignments가, 배점·점수 공개는 grade_items가 가진다.
 */
record Assignment(ReportActivity activity, Settings settings) {

	static final String LATE_NONE = "none";

	static final String LATE_ALLOW_UNTIL = "allow_until";

	long assignmentId() {
		return this.settings.assignmentId();
	}

	/**
	 * 제출 구간. 판정은 DB 시계 하나로 한다(SC-06). 정시는 {@code openAt <= now < closeAt}, 지각은 정책이
	 * allow_until일 때 {@code closeAt <= now < lateUntil}이다. 그 밖의 지각 허용은 없다.
	 */
	Phase phase(LocalDateTime now) {
		LocalDateTime openAt = this.activity.openAt();
		LocalDateTime closeAt = this.activity.closeAt();
		if (openAt != null && now.isBefore(openAt)) {
			return Phase.NOT_OPEN;
		}
		if (closeAt == null || now.isBefore(closeAt)) {
			return Phase.OPEN;
		}
		if (LATE_ALLOW_UNTIL.equals(this.settings.latePolicy()) && this.settings.lateUntil() != null
				&& now.isBefore(this.settings.lateUntil())) {
			return Phase.LATE;
		}
		return Phase.CLOSED;
	}

	/** 학생에게 점수와 의견을 보여도 되는지. 판정은 {@link GradeItem#released(String, LocalDateTime, LocalDateTime)} 하나다. */
	boolean scoresReleased(LocalDateTime now) {
		return GradeItem.released(this.settings.scoreVisibility(), this.settings.releaseAt(), now);
	}

	enum Phase {

		NOT_OPEN, OPEN, LATE, CLOSED;

		String code() {
			return name().toLowerCase(Locale.ROOT);
		}

		boolean acceptsSubmissions() {
			return this == OPEN || this == LATE;
		}

	}

	/**
	 * assignments와 grade_items 한 행씩.
	 *
	 * @param revision 과제 설정 수정 번호. 수정 요청은 읽은 값을 보내야 한다
	 */
	record Settings(long assignmentId, long activityId, boolean allowText, boolean allowFile, String latePolicy,
			LocalDateTime lateUntil, long revision, long gradeItemId, BigDecimal maxScore, String scoreVisibility,
			LocalDateTime releaseAt) {
	}

}
