package com.stackcorp.astra.assessment;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.assessment.SurveyAnswers.Submitted;
import com.stackcorp.astra.assessment.SurveyInput.QuestionInput;
import com.stackcorp.astra.assessment.SurveyService.Completion;
import com.stackcorp.astra.assessment.SurveyViews.Info;
import com.stackcorp.astra.assessment.SurveyViews.QuestionView;
import com.stackcorp.astra.assessment.SurveyViews.RespondentSurvey;
import com.stackcorp.astra.assessment.SurveyViews.SurveyItem;
import com.stackcorp.astra.assessment.SurveyViews.SurveyResults;
import com.stackcorp.astra.assessment.SurveyViews.TextPage;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.api.SurveyActivities.SurveyActivityInput;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의실 설문 등록·수정·조회·응답·결과(EP-0097~EP-0102의 대체 계약). 과목은 경로로 명시한다(SC-03).
 * 담당자는 그 과목의 설문을 만들고 고치며 결과 집계를 본다. 학생은 공개됐고 시작한 설문만 보고, 응답 기간 안에만 문항을 받고 한 번
 * 완료한다. 기간은 서버 시각으로 정한다(SC-06). 원본의 쪽 이동(EP-0098, EP-0100)은 문항 전체를 한 번에 내려주고 완료 한 번으로
 * 받는 것으로 바꿨다. 설명, 문항, 답은 일반 텍스트로만 받고 내보낸다.
 */
@RestController
class SurveyController {

	static final int MAX_QUESTIONS = 100;

	static final int MAX_OPTIONS = 20;

	private static final int MAX_TITLE = 200;

	private static final int MAX_DESCRIPTION = 10_000;

	private static final int MAX_QUESTION_TEXT = 1_000;

	private static final int MAX_OPTION_TEXT = 300;

	private static final int MAX_SCALE = 11;

	private static final int MAX_WEEK = 60;

	private final SurveyAccess access;

	private final SurveyService service;

	private final SurveyRecords records;

	private final SurveyViews views;

	private final Idempotency idempotency;

	private final RateLimiter rateLimiter;

	SurveyController(SurveyAccess access, SurveyService service, SurveyRecords records, SurveyViews views,
			Idempotency idempotency, RateLimiter rateLimiter) {
		this.access = access;
		this.service = service;
		this.records = records;
		this.views = views;
		this.idempotency = idempotency;
		this.rateLimiter = rateLimiter;
	}

	/** 201. {@code Idempotency-Key}를 보내면 같은 key의 재전송은 처음 응답을 다시 돌려받고 설문을 더 만들지 않는다(SC-05). */
	@PostMapping("/api/courses/{courseId}/surveys")
	ResponseEntity<InstructorSurvey> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody SurveyRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		if (this.access.role(actor.userId(), courseId) != CourseRole.INSTRUCTOR) {
			throw ApiException.notFound();
		}
		if (body.revision() != null) {
			throw ApiException.invalid("revision은 수정할 때만 보냅니다.");
		}
		if (body.questions() == null) {
			throw ApiException.invalid("questions는 반드시 보내야 합니다.");
		}
		SurveyInput input = validated(body);
		this.rateLimiter.check("survey-create", actor.userId(), 30, Duration.ofMinutes(10));
		InstructorSurvey created = this.idempotency.execute(actor.userId(), "survey.create", idempotencyKey,
				List.of(courseId, input), InstructorSurvey.class, () -> {
					long surveyId = this.service.create(actor, courseId, input);
					return instructorView(this.access.find(courseId, surveyId).orElseThrow(), this.access.now());
				});
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(created);
	}

	/** 전체 수정. anonymous와 questions를 보내지 않으면 그대로 둔다. 읽은 revision이 다르면 409다. */
	@PutMapping("/api/courses/{courseId}/surveys/{surveyId}")
	ResponseEntity<InstructorSurvey> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long surveyId, @RequestBody SurveyRequest body) {
		Survey current = this.access.managed(actor.userId(), courseId, surveyId);
		if (body.revision() == null) {
			throw ApiException.invalid("revision은 반드시 보내야 합니다.");
		}
		SurveyInput input = validated(body);
		this.rateLimiter.check("survey-update", actor.userId(), 120, Duration.ofMinutes(10));
		this.service.update(actor, courseId, current, body.revision(), input);
		return ok(instructorView(this.access.find(courseId, surveyId).orElseThrow(), this.access.now()));
	}

	/**
	 * 설문 삭제(ADR-0011, ADR-0037). {@code If-Match}에 읽은 {@code revision}을 보낸다. 없으면 428, 다르면 409 {@code edit_conflict}다.
	 * 참여와 응답은 지우지 않고, 그 commit부터 설문, 응답, 결과는 누구에게도 없다. 204.
	 */
	@DeleteMapping("/api/courses/{courseId}/surveys/{surveyId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long surveyId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		Survey current = this.access.managed(actor.userId(), courseId, surveyId);
		long revision = IfMatch.revision(ifMatch);
		this.rateLimiter.check("survey-update", actor.userId(), 120, Duration.ofMinutes(10));
		this.service.delete(actor, courseId, current, revision);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@GetMapping("/api/courses/{courseId}/surveys")
	ResponseEntity<List<SurveyItem>> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		CourseRole role = this.access.role(actor.userId(), courseId);
		LocalDateTime now = this.access.now();
		List<Survey> all = this.access.all(courseId);
		if (role == CourseRole.INSTRUCTOR) {
			List<Long> ids = all.stream().map(Survey::surveyId).toList();
			Map<Long, Integer> questions = this.records.questionCounts(ids);
			Map<Long, Integer> respondents = this.records.participantCounts(ids);
			return ok(all.stream()
				.map((s) -> SurveyItem.of(s, now, questions.getOrDefault(s.surveyId(), 0), null,
						respondents.getOrDefault(s.surveyId(), 0)))
				.toList());
		}
		List<Survey> visible = all.stream().filter((s) -> s.openToRespondents(now)).toList();
		List<Long> ids = visible.stream().map(Survey::surveyId).toList();
		Map<Long, Integer> questions = this.records.questionCounts(ids);
		Map<Long, String> mine = this.records.statesOf(actor.userId(), ids);
		return ok(visible.stream()
			.map((s) -> SurveyItem.of(s, now, questions.getOrDefault(s.surveyId(), 0),
					mine.getOrDefault(s.surveyId(), "not_started"), null))
			.toList());
	}

	/** 담당자에게는 설정과 문항 전체, 학생에게는 본인 상태와 응답 기간 안의 문항(EP-0101, EP-0102). 읽기만 한다(SC-04). */
	@GetMapping("/api/courses/{courseId}/surveys/{surveyId}")
	ResponseEntity<Object> get(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long surveyId) {
		CourseRole role = this.access.role(actor.userId(), courseId);
		LocalDateTime now = this.access.now();
		Survey survey = this.access.viewable(role, courseId, surveyId, now);
		if (role == CourseRole.INSTRUCTOR) {
			return ok(instructorView(survey, now));
		}
		return ok(respondentView(this.records, this.views, survey, actor.userId(), now));
	}

	/**
	 * 응답 완료(EP-0099). {@code Idempotency-Key} 필수. 201과 최소 receipt를 돌려준다. 익명 설문은 같은 key면 본문이 달라도
	 * 처음 결과를, 실명 설문은 같은 key에 다른 답이면 409를 돌려준다(SC-05-A, ADR-0022).
	 */
	@PostMapping("/api/courses/{courseId}/surveys/{surveyId}/completion")
	ResponseEntity<Completion> complete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long surveyId, @RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey,
			@RequestBody CompleteRequest body) {
		Survey survey = this.access.answerable(actor.userId(), courseId, surveyId, this.access.now());
		return completed(this.service, this.rateLimiter, actor, survey, idempotencyKey, body);
	}

	/** 결과 집계(EP-0097). 담당자, 또는 결과를 공개한 설문의 학생. */
	@GetMapping("/api/courses/{courseId}/surveys/{surveyId}/results")
	ResponseEntity<SurveyResults> results(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long surveyId) {
		CourseRole role = this.access.role(actor.userId(), courseId);
		LocalDateTime now = this.access.now();
		Survey survey = resultsVisible(role, courseId, surveyId, now);
		return ok(this.views.results(survey, role == CourseRole.INSTRUCTOR, now));
	}

	/** 서술형 문항의 답변 문구. 답변 문구 순서, 쪽 번호 방식이다. */
	@GetMapping("/api/courses/{courseId}/surveys/{surveyId}/results/questions/{questionId}/texts")
	ResponseEntity<TextPage> texts(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long surveyId, @PathVariable long questionId, @RequestParam(required = false) Integer page,
			@RequestParam(required = false) Integer size) {
		CourseRole role = this.access.role(actor.userId(), courseId);
		LocalDateTime now = this.access.now();
		Survey survey = resultsVisible(role, courseId, surveyId, now);
		return ok(this.views.texts(survey, role == CourseRole.INSTRUCTOR, now, questionId, page, size));
	}

	private Survey resultsVisible(CourseRole role, long courseId, long surveyId, LocalDateTime now) {
		Survey survey = this.access.viewable(role, courseId, surveyId, now);
		if (role != CourseRole.INSTRUCTOR && !survey.resultsReleased(now)) {
			throw ApiException.notFound();
		}
		return survey;
	}

	private InstructorSurvey instructorView(Survey survey, LocalDateTime now) {
		List<QuestionView> questions = this.views.questions(survey.surveyId());
		int respondents = this.records.participantCounts(List.of(survey.surveyId())).getOrDefault(survey.surveyId(), 0);
		return new InstructorSurvey(Info.of(survey, questions.size()), survey.settings().revision(),
				survey.phase(now).code(), now, respondents, questions);
	}

	/** 학생과 알림마당 대상자에게 같은 상세. */
	static RespondentSurvey respondentView(SurveyRecords records, SurveyViews views, Survey survey, long userId,
			LocalDateTime now) {
		String state = records.statesOf(userId, List.of(survey.surveyId())).getOrDefault(survey.surveyId(), "not_started");
		List<QuestionView> questions = views.questions(survey.surveyId());
		boolean answering = survey.phase(now) == Survey.Phase.OPEN && !"completed".equals(state);
		return new RespondentSurvey(Info.of(survey, questions.size()), survey.phase(now).code(), now, state,
				survey.resultsReleased(now), answering ? questions : null);
	}

	/** 강의실 설문과 알림마당 설문의 완료 요청 처리. 권한은 부르는 쪽이 먼저 판정한다. */
	static ResponseEntity<Completion> completed(SurveyService service, RateLimiter rateLimiter, Actor actor,
			Survey survey, String idempotencyKey, CompleteRequest body) {
		Idempotency.requireKey(idempotencyKey);
		var answers = SurveyAnswers.normalized(body.answers());
		rateLimiter.check("survey-complete", actor.userId(), 30, Duration.ofMinutes(10));
		Completion completion = service.complete(actor, survey, idempotencyKey, answers);
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(completion);
	}

	private static SurveyInput validated(SurveyRequest body) {
		if (body.weekNo() == null || body.weekNo() < 1 || body.weekNo() > MAX_WEEK) {
			throw ApiException.invalid("weekNo는 1 이상 " + MAX_WEEK + " 이하여야 합니다.");
		}
		String title = PlainText.line(body.title(), MAX_TITLE, "title");
		String description = PlainText.block(body.description(), MAX_DESCRIPTION, "description");
		if (body.closeAt() == null) {
			throw ApiException.invalid("closeAt은 반드시 보내야 합니다.");
		}
		// 활동의 기간 컬럼은 초 단위다. 반올림되지 않도록 초 아래를 버린다.
		LocalDateTime closeAt = body.closeAt().truncatedTo(ChronoUnit.SECONDS);
		LocalDateTime openAt = body.openAt() == null ? null : body.openAt().truncatedTo(ChronoUnit.SECONDS);
		if (openAt != null && !openAt.isBefore(closeAt)) {
			throw ApiException.invalid("openAt은 closeAt보다 앞이어야 합니다.");
		}
		boolean resultsVisible = body.resultsVisible() != null && body.resultsVisible();
		LocalDateTime releaseAt = body.resultsReleaseAt() == null ? null
				: body.resultsReleaseAt().truncatedTo(ChronoUnit.SECONDS);
		if (releaseAt != null && !resultsVisible) {
			throw ApiException.invalid("resultsVisible이 true일 때만 resultsReleaseAt을 보냅니다.");
		}
		List<QuestionInput> questions = body.questions() == null ? null : questions(body.questions());
		return new SurveyInput(
				new SurveyActivityInput(body.weekNo(), title, description, openAt, closeAt,
						body.published() == null || body.published()),
				body.anonymous(), resultsVisible, releaseAt, questions);
	}

	private static List<QuestionInput> questions(List<QuestionRequest> requests) {
		if (requests.isEmpty() || requests.size() > MAX_QUESTIONS) {
			throw ApiException.invalid("questions는 1개 이상 " + MAX_QUESTIONS + "개 이하여야 합니다.");
		}
		List<QuestionInput> result = new ArrayList<>();
		for (QuestionRequest q : requests) {
			if (q == null || q.type() == null) {
				throw ApiException.invalid("문항의 type은 single, multiple, scale, essay 중 하나여야 합니다.");
			}
			String text = PlainText.block(q.text(), MAX_QUESTION_TEXT, "question text");
			if (text == null) {
				throw ApiException.invalid("문항의 text는 반드시 보내야 합니다.");
			}
			boolean required = q.required() == null || q.required();
			List<String> options = q.options() == null ? List.of() : q.options();
			switch (q.type()) {
				case "essay" -> {
					if (!options.isEmpty() || q.minSelect() != null || q.maxSelect() != null) {
						throw ApiException.invalid("서술형 문항에는 보기와 선택 수를 보내지 않습니다.");
					}
					result.add(new QuestionInput("essay", text, required, null, null, List.of()));
				}
				case "single", "scale" -> {
					int max = "scale".equals(q.type()) ? MAX_SCALE : MAX_OPTIONS;
					if (q.minSelect() != null || q.maxSelect() != null) {
						throw ApiException.invalid("선택 수는 복수 선택 문항에만 보냅니다.");
					}
					result.add(new QuestionInput(q.type(), text, required, null, null, optionTexts(options, max)));
				}
				case "multiple" -> {
					List<String> texts = optionTexts(options, MAX_OPTIONS);
					int min = q.minSelect() == null ? 1 : q.minSelect();
					int max = q.maxSelect() == null ? texts.size() : q.maxSelect();
					if (min < 1 || min > max || max > texts.size()) {
						throw ApiException.invalid("선택 수는 1 이상 보기 수 이하이고 minSelect는 maxSelect 이하여야 합니다.");
					}
					result.add(new QuestionInput("multiple", text, required, min, max, texts));
				}
				default -> throw ApiException.invalid("문항의 type은 single, multiple, scale, essay 중 하나여야 합니다.");
			}
		}
		return List.copyOf(result);
	}

	private static List<String> optionTexts(List<String> options, int max) {
		if (options.size() < 2 || options.size() > max) {
			throw ApiException.invalid("보기는 2개 이상 " + max + "개 이하여야 합니다.");
		}
		return options.stream().map((o) -> PlainText.line(o, MAX_OPTION_TEXT, "option")).toList();
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/**
	 * 등록·수정 본문. 작성자와 과목은 받지 않는다. session과 경로에서 정한다(SC-02).
	 *
	 * @param anonymous 익명 설문인지. 등록에서 없으면 익명이다. 수정에서 없으면 그대로 둔다
	 * @param resultsVisible 응답자에게 결과를 공개할지. 공개해도 마감 뒤에만 보인다
	 * @param questions 순서대로의 문항. 등록에는 반드시 보낸다. 수정에서 없으면 그대로 둔다
	 * @param revision 수정할 때 읽은 설정 수정 번호. 등록할 때는 보내지 않는다
	 */
	record SurveyRequest(Integer weekNo, String title, String description, LocalDateTime openAt,
			LocalDateTime closeAt, Boolean published, Boolean anonymous, Boolean resultsVisible,
			LocalDateTime resultsReleaseAt, List<QuestionRequest> questions, Long revision) {
	}

	/**
	 * @param type single(객관식 하나), multiple(객관식 여럿), scale(척도, 보기 순서가 1부터의 값), essay(서술형)
	 * @param required 필수 응답인지. 없으면 필수다
	 * @param minSelect multiple만. 없으면 1
	 * @param maxSelect multiple만. 없으면 보기 수
	 * @param options 보기 문구(2~20개, scale은 2~11개). essay에는 보내지 않는다
	 */
	record QuestionRequest(String type, String text, Boolean required, Integer minSelect, Integer maxSelect,
			List<String> options) {
	}

	/** 완료 본문. 응답자는 session에서 정한다(SC-02). 답하지 않은 선택 문항은 넣지 않는다. */
	record CompleteRequest(List<Submitted> answers) {
	}

	/**
	 * @param revision 수정 요청에 그대로 보낼 수정 번호
	 * @param phase 응답 구간: not_open, open, closed
	 * @param respondentCount 완료한 사람 수
	 */
	record InstructorSurvey(Info survey, long revision, String phase, LocalDateTime serverTime, int respondentCount,
			List<QuestionView> questions) {
	}

}
