package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Locale;

import com.stackcorp.astra.learning.api.QuizActivities.QuizActivity;

/**
 * 퀴즈. 제목·설명·응시 기간은 learning의 활동이, 시험형태·제한 시간·응시 횟수·정답 공개는 quizzes가, 배점·결과 공개 일시는
 * grade_items가 가진다(ADR-0021).
 */
record Quiz(QuizActivity activity, Settings settings) {

	static final String ONLINE = "online";

	static final String OFFLINE = "offline";

	long quizId() {
		return this.settings.quizId();
	}

	boolean online() {
		return ONLINE.equals(this.settings.examType());
	}

	/** 응시를 시작할 수 있는 구간. 판정은 DB 시계 하나로 {@code openAt <= now < closeAt}이다(SC-06). */
	Phase phase(LocalDateTime now) {
		LocalDateTime openAt = this.activity.openAt();
		if (openAt != null && now.isBefore(openAt)) {
			return Phase.NOT_OPEN;
		}
		return now.isBefore(this.activity.closeAt()) ? Phase.OPEN : Phase.CLOSED;
	}

	/**
	 * 학생에게 결과를 어디까지 보여도 되는지. 모든 학생용 응답이 이 판정 하나를 쓴다(ASSESS-06, SEC-14).
	 * <ul>
	 * <li>총점: 결과 공개 일시가 지났을 때만.</li>
	 * <li>정답, 해설, 문항별 점수: 총점을 공개했고, 정답 공개를 켰고, 응시 기간이 끝났을 때만. 기간 안에 보이면 아직 응시하는 학생에게
	 * 정답이 전해진다.</li>
	 * </ul>
	 */
	Release release(LocalDateTime now) {
		LocalDateTime releaseAt = this.settings.releaseAt();
		boolean scores = "after_release".equals(this.settings.scoreVisibility()) && releaseAt != null
				&& !releaseAt.isAfter(now);
		boolean answers = scores && this.settings.answersVisible() && !now.isBefore(this.activity.closeAt());
		return new Release(scores, answers);
	}

	/**
	 * @param scores 총점과 성적 항목 점수를 보여도 되는지
	 * @param answers 정답, 해설, 문항별 점수와 채점 의견을 보여도 되는지. true면 scores도 true다
	 */
	record Release(boolean scores, boolean answers) {
	}

	enum Phase {

		NOT_OPEN, OPEN, CLOSED;

		String code() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

	/**
	 * quizzes와 grade_items 한 행씩.
	 *
	 * @param durationMinutes 응시 제한 시간. null이면 응시 마감까지
	 * @param attemptLimit 허용 응시 횟수. null이면 제한 없음
	 * @param revision 퀴즈 설정·문항 수정 번호. 수정 요청은 읽은 값을 보내야 한다
	 * @param scoreVisibility hidden 또는 after_release
	 * @param releaseAt 결과 공개 일시. hidden이면 null
	 */
	record Settings(long quizId, long activityId, long courseId, String examType, Integer durationMinutes,
			Integer attemptLimit, boolean shuffle, boolean answersVisible, long revision, long gradeItemId,
			BigDecimal maxScore, String scoreVisibility, LocalDateTime releaseAt) {
	}

}
