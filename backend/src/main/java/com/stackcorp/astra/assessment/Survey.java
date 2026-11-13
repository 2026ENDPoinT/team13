package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.Locale;

import com.stackcorp.astra.learning.api.SurveyActivities.SurveyActivity;

/**
 * 설문. 강의실 설문의 제목·설명·주차·공개·기간은 learning의 활동이 가지고, 알림마당 설문(활동 없음)은 surveys 행이 가진다.
 * 응답 방식, 결과 공개, 최소 공개 인원은 surveys 행이 가진다.
 *
 * @param activity 강의실 설문의 활동. 알림마당 설문이면 null
 */
record Survey(Settings settings, SurveyActivity activity) {

	static final String ANONYMOUS = "anonymous";

	static final String NAMED = "named";

	long surveyId() {
		return this.settings.surveyId();
	}

	boolean community() {
		return this.activity == null;
	}

	/** 과목 범위. 알림마당 설문이면 null. */
	Long courseId() {
		return this.activity == null ? null : this.activity.courseId();
	}

	boolean anonymous() {
		return !NAMED.equals(this.settings.responseMode());
	}

	String title() {
		return this.activity == null ? this.settings.title() : this.activity.title();
	}

	String description() {
		return this.activity == null ? this.settings.descriptionText() : this.activity.description();
	}

	LocalDateTime openAt() {
		return this.activity == null ? this.settings.openAt() : this.activity.openAt();
	}

	LocalDateTime closeAt() {
		return this.activity == null ? this.settings.closeAt() : this.activity.closeAt();
	}

	boolean published() {
		return this.activity == null || this.activity.published();
	}

	/** 응답자가 설문을 볼 수 있는지. 공개했고 시작 일시가 지났을 때만이다. now는 DB 시계다(SC-06). */
	boolean openToRespondents(LocalDateTime now) {
		return published() && (openAt() == null || !openAt().isAfter(now));
	}

	/** 응답 구간. 판정은 DB 시계 하나로 하고 {@code openAt <= now < closeAt}만 응답을 받는다(SC-06). */
	Phase phase(LocalDateTime now) {
		if (openAt() != null && now.isBefore(openAt())) {
			return Phase.NOT_OPEN;
		}
		if (closeAt() == null || now.isBefore(closeAt())) {
			return Phase.OPEN;
		}
		return Phase.CLOSED;
	}

	/**
	 * 응답자(학생, 알림마당 대상자)에게 결과를 공개했는지. 설문이 결과 공개를 켰고, 공개 일시가 지났고(없으면 마감 뒤 바로), 응답 기간이
	 * 끝났을 때만이다. 응답 중에는 남의 응답이 응답에 영향을 주지 않도록 공개하지 않는다.
	 */
	boolean resultsReleased(LocalDateTime now) {
		return this.settings.resultsVisible()
				&& (this.settings.resultsReleaseAt() == null || !this.settings.resultsReleaseAt().isAfter(now))
				&& phase(now) == Phase.CLOSED;
	}

	/**
	 * 결과 집계를 내보내지 않는 이유. 내보내도 되면 null이다(ASSESS-07, ASSESS-08, ADR-0022).
	 * <ul>
	 * <li>익명 설문이거나 보는 사람이 응답자(학생)면 응답 기간이 끝나야 하고 응답자가 최소 공개 인원 이상이어야 한다. 기간 중에는 결과가
	 * 바뀌므로 완료 직후의 차이로 한 사람의 응답을 알아낼 수 있다.</li>
	 * <li>실명 설문의 담당자는 언제나 집계를 본다. 누가 무엇을 답했는지는 이 API로 내보내지 않는다.</li>
	 * </ul>
	 * 응답자에게 결과를 공개했는지({@link #resultsReleased})는 부르는 쪽이 먼저 판정한다.
	 */
	String withheldReason(boolean instructor, int respondents, LocalDateTime now) {
		if (!protectsRespondents(instructor)) {
			return null;
		}
		if (phase(now) != Phase.CLOSED) {
			return "not_closed";
		}
		return respondents < this.settings.minRespondents() ? "below_threshold" : null;
	}

	/** 문항 하나의 집계를 가려야 하는지. 그 문항에 답한 사람이 최소 공개 인원보다 적으면 가린다. */
	boolean suppresses(boolean instructor, int answerCount) {
		return protectsRespondents(instructor) && answerCount < this.settings.minRespondents();
	}

	private boolean protectsRespondents(boolean instructor) {
		return anonymous() || !instructor;
	}

	enum Phase {

		NOT_OPEN, OPEN, CLOSED;

		String code() {
			return name().toLowerCase(Locale.ROOT);
		}

	}

	/**
	 * surveys 한 행.
	 *
	 * @param activityId 강의실 설문의 활동. 알림마당 설문이면 null
	 * @param openAt 알림마당 설문의 응답 시작. 강의실 설문이면 null(활동 기간을 쓴다)
	 * @param minRespondents 익명 집계를 내보내는 최소 응답자 수(result_min_cell_k)
	 * @param revision 설정 수정 번호. 수정 요청은 읽은 값을 보내야 한다
	 */
	record Settings(long surveyId, Long activityId, String title, String descriptionText, String responseMode,
			LocalDateTime openAt, LocalDateTime closeAt, boolean resultsVisible, LocalDateTime resultsReleaseAt,
			int minRespondents, long revision) {
	}

}
