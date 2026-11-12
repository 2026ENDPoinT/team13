package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.assessment.SurveyController.CompleteRequest;
import com.stackcorp.astra.assessment.SurveyRecords.Audience;
import com.stackcorp.astra.assessment.SurveyService.Completion;
import com.stackcorp.astra.assessment.SurveyViews.Page;
import com.stackcorp.astra.assessment.SurveyViews.RespondentSurvey;
import com.stackcorp.astra.assessment.SurveyViews.SurveyItem;
import com.stackcorp.astra.assessment.SurveyViews.SurveyResults;
import com.stackcorp.astra.assessment.SurveyViews.TextPage;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림마당 설문(EP-0163, EP-0164, EP-0166, EP-0167의 대체 계약). 학습활동이 없는 학교 단위 설문이다. 대상 행(survey_targets)과
 * session의 역할, 학년으로 대상자를 정하고, 대상이 아니거나 시작 전이면 404다. 응답과 결과 규칙은 강의실 설문과 같다.
 * 원본의 후속 쪽 경로(EP-0165)는 확인되지 않아 대체하지 않고, 같은 완료 계약 하나로 받는다. 설문을 만드는 화면과 API는 없다.
 */
@RestController
class CommunitySurveyController {

	private final SurveyAccess access;

	private final SurveyService service;

	private final SurveyRecords records;

	private final SurveyViews views;

	private final RateLimiter rateLimiter;

	CommunitySurveyController(SurveyAccess access, SurveyService service, SurveyRecords records, SurveyViews views,
			RateLimiter rateLimiter) {
		this.access = access;
		this.service = service;
		this.records = records;
		this.views = views;
		this.rateLimiter = rateLimiter;
	}

	/** 대상인 설문 목록. 마감 오름차순, 쪽 번호 방식이다(원본 display 20). 정렬은 고정이고 client가 고르지 않는다. */
	@GetMapping("/api/surveys")
	ResponseEntity<Page<SurveyItem>> list(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
		int p = SurveyViews.page(page);
		int s = SurveyViews.size(size);
		LocalDateTime now = this.access.now();
		Audience audience = this.access.audience(actor).orElse(null);
		if (audience == null) {
			return ok(new Page<>(List.of(), p, s, 0));
		}
		List<Survey> surveys = this.records.community(audience, now, (p - 1) * s, s)
			.stream()
			.map((settings) -> new Survey(settings, null))
			.toList();
		List<Long> ids = surveys.stream().map(Survey::surveyId).toList();
		Map<Long, Integer> questions = this.records.questionCounts(ids);
		Map<Long, String> mine = this.records.statesOf(actor.userId(), ids);
		List<SurveyItem> items = surveys.stream()
			.map((survey) -> SurveyItem.of(survey, now, questions.getOrDefault(survey.surveyId(), 0),
					mine.getOrDefault(survey.surveyId(), "not_started"), null))
			.toList();
		return ok(new Page<>(items, p, s, this.records.communityCount(audience, now)));
	}

	@GetMapping("/api/surveys/{surveyId}")
	ResponseEntity<RespondentSurvey> get(@AuthenticationPrincipal Actor actor, @PathVariable long surveyId) {
		LocalDateTime now = this.access.now();
		Survey survey = this.access.community(actor, surveyId, now);
		return ok(SurveyController.respondentView(this.records, this.views, survey, actor.userId(), now));
	}

	/** 응답 완료. {@code Idempotency-Key} 필수. 규칙은 강의실 설문의 완료와 같다(SC-05-A, ADR-0022). */
	@PostMapping("/api/surveys/{surveyId}/completion")
	ResponseEntity<Completion> complete(@AuthenticationPrincipal Actor actor, @PathVariable long surveyId,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey,
			@RequestBody CompleteRequest body) {
		Survey survey = this.access.community(actor, surveyId, this.access.now());
		return SurveyController.completed(this.service, this.rateLimiter, actor, survey, idempotencyKey, body);
	}

	@GetMapping("/api/surveys/{surveyId}/results")
	ResponseEntity<SurveyResults> results(@AuthenticationPrincipal Actor actor, @PathVariable long surveyId) {
		LocalDateTime now = this.access.now();
		return ok(this.views.results(released(actor, surveyId, now), false, now));
	}

	@GetMapping("/api/surveys/{surveyId}/results/questions/{questionId}/texts")
	ResponseEntity<TextPage> texts(@AuthenticationPrincipal Actor actor, @PathVariable long surveyId,
			@PathVariable long questionId, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		LocalDateTime now = this.access.now();
		return ok(this.views.texts(released(actor, surveyId, now), false, now, questionId, page, size));
	}

	private Survey released(Actor actor, long surveyId, LocalDateTime now) {
		Survey survey = this.access.community(actor, surveyId, now);
		if (!survey.resultsReleased(now)) {
			throw ApiException.notFound();
		}
		return survey;
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

}
