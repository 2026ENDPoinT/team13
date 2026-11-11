package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.assessment.SurveyRecords.Option;
import com.stackcorp.astra.assessment.SurveyRecords.Question;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * 강의실 설문과 알림마당 설문이 함께 쓰는 응답 모양과 결과 집계(EP-0097, EP-0102, EP-0167).
 * 결과는 집계(보기별 선택 수, 척도 평균, 서술형 문구)만 내보낸다. 누가 무엇을 답했는지, 응답 세트 ID, 제출 시각은 어떤 응답에도
 * 없다. 공개 판정은 {@link Survey#withheldReason}과 {@link Survey#suppresses} 하나를 쓴다(ASSESS-07, ASSESS-08).
 */
@Component
class SurveyViews {

	static final int DEFAULT_PAGE_SIZE = 20;

	static final int MAX_PAGE_SIZE = 100;

	private static final int MAX_PAGE = 10_000;

	private final SurveyRecords records;

	SurveyViews(SurveyRecords records) {
		this.records = records;
	}

	List<QuestionView> questions(long surveyId) {
		return this.records.questions(surveyId).stream().map(QuestionView::of).toList();
	}

	SurveyResults results(Survey survey, boolean instructor, LocalDateTime now) {
		int respondents = this.records.respondentCount(survey.surveyId());
		String withheld = survey.withheldReason(instructor, respondents, now);
		int k = survey.settings().minRespondents();
		if (withheld != null) {
			return new SurveyResults(survey.surveyId(), survey.anonymous(), respondents, k, false, withheld, List.of());
		}
		Map<Long, Integer> answers = this.records.answerCounts(survey.surveyId());
		Map<Long, Integer> options = this.records.optionCounts(survey.surveyId());
		List<QuestionResult> questions = this.records.questions(survey.surveyId()).stream().map((q) -> {
			int answered = answers.getOrDefault(q.questionId(), 0);
			if (survey.suppresses(instructor, answered)) {
				return new QuestionResult(q.questionId(), q.questionNo(), q.type(), q.text(), answered, true, null, null);
			}
			if ("essay".equals(q.type())) {
				return new QuestionResult(q.questionId(), q.questionNo(), q.type(), q.text(), answered, false, null, null);
			}
			List<OptionResult> counts = q.options()
				.stream()
				.map((o) -> new OptionResult(o.optionId(), o.optionNo(), o.text(), options.getOrDefault(o.optionId(), 0)))
				.toList();
			return new QuestionResult(q.questionId(), q.questionNo(), q.type(), q.text(), answered, false, counts,
					"scale".equals(q.type()) ? mean(counts, answered) : null);
		}).toList();
		return new SurveyResults(survey.surveyId(), survey.anonymous(), respondents, k, true, null, questions);
	}

	/**
	 * 서술형 문항의 답변 문구 한 쪽. 결과를 내보낼 수 없거나 문항이 가려졌으면 409 {@code results_withheld}다.
	 */
	TextPage texts(Survey survey, boolean instructor, LocalDateTime now, long questionId, Integer page, Integer size) {
		int p = page(page);
		int s = size(size);
		Question question = this.records.questions(survey.surveyId())
			.stream()
			.filter((q) -> q.questionId() == questionId && "essay".equals(q.type()))
			.findFirst()
			.orElseThrow(ApiException::notFound);
		int respondents = this.records.respondentCount(survey.surveyId());
		int answered = this.records.answerCounts(survey.surveyId()).getOrDefault(questionId, 0);
		if (survey.withheldReason(instructor, respondents, now) != null || survey.suppresses(instructor, answered)) {
			throw new ApiException(HttpStatus.CONFLICT, "results_withheld", "아직 결과를 볼 수 없습니다.");
		}
		List<String> items = this.records.texts(survey.surveyId(), question.questionId(), (p - 1) * s, s);
		return new TextPage(questionId, items, p, s, answered);
	}

	static int page(Integer page) {
		if (page != null && (page < 1 || page > MAX_PAGE)) {
			throw ApiException.invalid("page는 1 이상 " + MAX_PAGE + " 이하여야 합니다.");
		}
		return page == null ? 1 : page;
	}

	static int size(Integer size) {
		if (size != null && (size < 1 || size > MAX_PAGE_SIZE)) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		return size == null ? DEFAULT_PAGE_SIZE : size;
	}

	// 척도 문항은 답마다 보기가 하나이고 보기 번호가 척도 값이다.
	private static BigDecimal mean(List<OptionResult> counts, int answered) {
		if (answered == 0) {
			return null;
		}
		long sum = counts.stream().mapToLong((o) -> (long) o.optionNo() * o.count()).sum();
		return BigDecimal.valueOf(sum).divide(BigDecimal.valueOf(answered), 2, RoundingMode.HALF_UP);
	}

	/**
	 * 담당자와 응답자에게 같은 설문 정보.
	 *
	 * @param activityId 강의실 설문만
	 * @param weekNo 강의실 설문만
	 * @param resultsReleaseAt 결과 공개 일시. null이면 마감 뒤 바로(resultsVisible일 때)
	 */
	record Info(long surveyId, @JsonInclude(JsonInclude.Include.NON_NULL) Long activityId,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer weekNo, String title, String description,
			LocalDateTime openAt, LocalDateTime closeAt, boolean published, boolean anonymous, boolean resultsVisible,
			LocalDateTime resultsReleaseAt, int questionCount) {

		static Info of(Survey survey, int questionCount) {
			return new Info(survey.surveyId(), survey.community() ? null : survey.activity().activityId(),
					survey.community() ? null : survey.activity().weekNo(), survey.title(), survey.description(),
					survey.openAt(), survey.closeAt(), survey.published(), survey.anonymous(),
					survey.settings().resultsVisible(), survey.settings().resultsReleaseAt(), questionCount);
		}

	}

	/**
	 * @param type single, multiple, scale, essay
	 * @param minSelect multiple만. 그 밖에는 null
	 * @param options 보기 번호 순서. scale이면 보기 번호가 척도 값이다
	 */
	record QuestionView(long questionId, int questionNo, String type, String text, boolean required, Integer minSelect,
			Integer maxSelect, List<OptionView> options) {

		static QuestionView of(Question q) {
			return new QuestionView(q.questionId(), q.questionNo(), q.type(), q.text(), q.required(), q.minSelect(),
					q.maxSelect(), q.options().stream().map(OptionView::of).toList());
		}

	}

	record OptionView(long optionId, int optionNo, String text) {

		static OptionView of(Option o) {
			return new OptionView(o.optionId(), o.optionNo(), o.text());
		}

	}

	/**
	 * 응답자(학생, 알림마당 대상자)용 상세. 문항은 응답 기간 안이고 아직 완료하지 않았을 때만 있다.
	 *
	 * @param phase not_open, open, closed
	 * @param state not_started, completed
	 * @param resultsAvailable 지금 결과를 볼 수 있는지(결과 공개 설정과 마감)
	 */
	record RespondentSurvey(Info survey, String phase, LocalDateTime serverTime, String state, boolean resultsAvailable,
			@JsonInclude(JsonInclude.Include.NON_NULL) List<QuestionView> questions) {
	}

	/**
	 * 목록 항목. 담당자에게는 완료한 사람 수를, 응답자에게는 본인 상태를 담는다.
	 *
	 * @param state 응답자 본인의 상태: not_started, completed. 담당자 응답에는 없다
	 * @param respondentCount 완료한 사람 수. 응답자 응답에는 없다
	 */
	record SurveyItem(long surveyId, @JsonInclude(JsonInclude.Include.NON_NULL) Integer weekNo, String title,
			LocalDateTime openAt, LocalDateTime closeAt, boolean published, boolean anonymous, String phase,
			int questionCount, @JsonInclude(JsonInclude.Include.NON_NULL) String state,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer respondentCount) {

		static SurveyItem of(Survey s, LocalDateTime now, int questionCount, String state, Integer respondentCount) {
			return new SurveyItem(s.surveyId(), s.community() ? null : s.activity().weekNo(), s.title(), s.openAt(),
					s.closeAt(), s.published(), s.anonymous(), s.phase(now).code(), questionCount, state,
					respondentCount);
		}

	}

	/**
	 * 결과 집계. 내보낼 수 없으면 disclosed가 false이고 문항 결과가 비어 있다.
	 *
	 * @param respondentCount 응답을 완료한 사람 수
	 * @param minRespondents 익명 설문(담당자)과 응답자에게 집계를 내보내는 최소 응답자 수
	 * @param withheldReason not_closed(응답 기간 중), below_threshold(응답자가 최소 공개 인원 미만)
	 */
	record SurveyResults(long surveyId, boolean anonymous, int respondentCount, int minRespondents, boolean disclosed,
			@JsonInclude(JsonInclude.Include.NON_NULL) String withheldReason, List<QuestionResult> questions) {
	}

	/**
	 * @param answerCount 이 문항에 답한 사람 수
	 * @param suppressed 답한 사람이 최소 공개 인원 미만이라 집계를 가렸는지
	 * @param options 보기 문항의 보기별 선택 수. 서술형이거나 가렸으면 없다
	 * @param scaleMean 척도 문항의 평균(소수 둘째 자리). 그 밖에는 없다
	 */
	record QuestionResult(long questionId, int questionNo, String type, String text, int answerCount,
			boolean suppressed, @JsonInclude(JsonInclude.Include.NON_NULL) List<OptionResult> options,
			@JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal scaleMean) {
	}

	record OptionResult(long optionId, int optionNo, String text, int count) {
	}

	/**
	 * 서술형 답변 한 쪽. 문구 순서이고 입력 순서가 아니다.
	 *
	 * @param total 이 문항의 답변 수
	 */
	record TextPage(long questionId, List<String> items, int page, int size, int total) {
	}

	record Page<T>(List<T> items, int page, int size, int total) {
	}

}
