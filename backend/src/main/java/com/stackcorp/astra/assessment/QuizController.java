package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.assessment.AttemptRecords.Attempt;
import com.stackcorp.astra.assessment.AttemptRecords.Counts;
import com.stackcorp.astra.assessment.Quiz.Release;
import com.stackcorp.astra.assessment.QuizInput.OptionInput;
import com.stackcorp.astra.assessment.QuizInput.QuestionInput;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.api.QuizActivities.QuizActivity;
import com.stackcorp.astra.learning.api.QuizActivities.QuizActivityInput;
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
import org.springframework.web.bind.annotation.RestController;

/**
 * 퀴즈 출제, 수정, 조회(EP-0112의 대체 계약). 과목은 경로로 명시한다(SC-03). 담당자는 그 과목의 퀴즈를 만들고 고치며 정답까지 본다.
 * 학생에게는 공개됐고 시작한 퀴즈만 보이고, 응답에는 본인 응시 상태만 담는다. 문항은 응시를 시작한 뒤 응시 조회로만 받는다(EP-0103).
 * 총점과 성적은 결과 공개 일시가 지난 뒤에만 학생 응답에 들어간다. 공개 전에는 속성 자체가 없다(SEC-14, {@link Quiz#release}).
 * 글은 일반 텍스트로만 받고 내보낸다.
 */
@RestController
class QuizController {

	private static final int MAX_TITLE = 200;

	private static final int MAX_DESCRIPTION = 10_000;

	private static final int MAX_WEEK = 60;

	private static final int MAX_QUESTIONS = 200;

	private static final int MAX_QUESTION_TEXT = 5_000;

	private static final int MAX_OPTIONS = 20;

	private static final int MAX_OPTION_TEXT = 1_000;

	private static final int MAX_TIME_LIMIT = 1_440;

	private static final int MAX_ATTEMPTS = 100;

	private static final BigDecimal MAX_SCORE = new BigDecimal("1000");

	private final QuizAccess access;

	private final QuizService service;

	private final QuizRegrading regrading;

	private final QuizRecords records;

	private final AttemptRecords attempts;

	private final Idempotency idempotency;

	private final RateLimiter rateLimiter;

	QuizController(QuizAccess access, QuizService service, QuizRegrading regrading, QuizRecords records,
			AttemptRecords attempts, Idempotency idempotency, RateLimiter rateLimiter) {
		this.access = access;
		this.service = service;
		this.regrading = regrading;
		this.records = records;
		this.attempts = attempts;
		this.idempotency = idempotency;
		this.rateLimiter = rateLimiter;
	}

	/** 201. {@code Idempotency-Key}를 보내면 같은 key의 재전송은 처음 응답을 다시 받고 퀴즈를 더 만들지 않는다(SC-05). */
	@PostMapping("/api/courses/{courseId}/quizzes")
	ResponseEntity<InstructorQuiz> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody CreateQuizRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		if (this.access.role(actor.userId(), courseId) != CourseRole.INSTRUCTOR) {
			throw ApiException.notFound();
		}
		String examType = body.examType() == null ? Quiz.ONLINE : body.examType();
		if (!Quiz.ONLINE.equals(examType) && !Quiz.OFFLINE.equals(examType)) {
			throw ApiException.invalid("examType은 online 또는 offline이어야 합니다.");
		}
		boolean online = Quiz.ONLINE.equals(examType);
		QuizInput input = validated(online, body.weekNo(), body.title(), body.description(), body.openAt(),
				body.closeAt(), body.published(), body.timeLimitMinutes(), body.attemptLimit(), body.shuffleQuestions(),
				body.resultReleaseAt(), body.answersVisible(), body.maxScore());
		List<QuestionInput> questions = online ? questions(body.questions()) : noQuestions(body.questions());
		this.rateLimiter.check("quiz-create", actor.userId(), 60, Duration.ofMinutes(10));
		InstructorQuiz created = this.idempotency.execute(actor.userId(), "quiz.create", idempotencyKey,
				List.of(courseId, examType, input, questions), InstructorQuiz.class, () -> {
					long quizId = this.service.create(actor, courseId, examType, input, questions);
					return instructorView(this.access.find(courseId, quizId).orElseThrow(), this.access.now());
				});
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(created);
	}

	/** 설정 수정. 읽은 {@code revision}이 다르면 409 {@code edit_conflict}다. 시험형태와 문항은 바꾸지 않는다. */
	@PutMapping("/api/courses/{courseId}/quizzes/{quizId}")
	ResponseEntity<InstructorQuiz> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @RequestBody UpdateQuizRequest body) {
		Quiz current = this.access.managed(actor.userId(), courseId, quizId);
		if (body.revision() == null) {
			throw ApiException.invalid("revision은 반드시 보내야 합니다.");
		}
		QuizInput input = validated(current.online(), body.weekNo(), body.title(), body.description(), body.openAt(),
				body.closeAt(), body.published(), body.timeLimitMinutes(), body.attemptLimit(), body.shuffleQuestions(),
				body.resultReleaseAt(), body.answersVisible(), body.maxScore());
		this.rateLimiter.check("quiz-update", actor.userId(), 120, Duration.ofMinutes(10));
		this.service.update(actor, courseId, current, body.revision(), input);
		return ok(instructorView(this.access.find(courseId, quizId).orElseThrow(), this.access.now()));
	}

	/** 문항 전체 교체. 응시가 하나라도 있으면 409 {@code questions_locked}다(ADR-0021). */
	@PutMapping("/api/courses/{courseId}/quizzes/{quizId}/questions")
	ResponseEntity<InstructorQuiz> replaceQuestions(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @RequestBody QuestionsRequest body) {
		Quiz current = this.access.managed(actor.userId(), courseId, quizId);
		if (body.revision() == null) {
			throw ApiException.invalid("revision은 반드시 보내야 합니다.");
		}
		if (!current.online()) {
			throw ApiException.invalid("오프라인 시험에는 문항이 없습니다.");
		}
		List<QuestionInput> questions = questions(body.questions());
		this.rateLimiter.check("quiz-update", actor.userId(), 120, Duration.ofMinutes(10));
		this.service.replaceQuestions(actor, courseId, current, body.revision(), questions);
		return ok(instructorView(this.access.find(courseId, quizId).orElseThrow(), this.access.now()));
	}

	/**
	 * 정답과 배점 정정(재채점, ADR-0037). 응시가 있어도 문항 하나의 정답 보기와 배점만 바꾸고, 닫힌 응시를 모두 같은 transaction에서 다시
	 * 채점해 총점과 성적을 바꾼다. 지문과 보기는 바꾸지 않는다. 읽은 {@code revision}이 다르면 409 {@code edit_conflict}다.
	 */
	@PutMapping("/api/courses/{courseId}/quizzes/{quizId}/questions/{questionId}/answer-key")
	ResponseEntity<RegradeResult> regrade(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @PathVariable long questionId, @RequestBody AnswerKeyRequest body) {
		Quiz current = this.access.managed(actor.userId(), courseId, quizId);
		if (body.revision() == null) {
			throw ApiException.invalid("revision은 반드시 보내야 합니다.");
		}
		BigDecimal points = PlainText.points(body.points(), MAX_SCORE, "문항 배점");
		if (points.signum() == 0) {
			throw ApiException.invalid("문항 배점은 0보다 커야 합니다.");
		}
		List<Long> requested = body.correctOptionIds() == null ? List.of() : body.correctOptionIds();
		if (requested.stream().anyMatch((id) -> id == null) || new LinkedHashSet<>(requested).size() != requested.size()
				|| requested.size() > MAX_OPTIONS) {
			throw ApiException.invalid("correctOptionIds가 올바르지 않습니다.");
		}
		this.rateLimiter.check("quiz-update", actor.userId(), 120, Duration.ofMinutes(10));
		int rescored = this.regrading.regrade(actor, courseId, current, body.revision(), questionId, points,
				Set.copyOf(requested));
		return ok(new RegradeResult(instructorView(this.access.find(courseId, quizId).orElseThrow(), this.access.now()),
				rescored));
	}

	/**
	 * 퀴즈 삭제(ADR-0011, ADR-0037). {@code If-Match}에 읽은 {@code revision}을 보낸다. 없으면 428, 다르면 409 {@code edit_conflict}다.
	 * 응시, 답안, 성적은 지우지 않고 남기며, 그 commit부터 퀴즈와 응시와 성적 항목은 누구에게도 없다. 204.
	 */
	@DeleteMapping("/api/courses/{courseId}/quizzes/{quizId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		Quiz current = this.access.managed(actor.userId(), courseId, quizId);
		long revision = IfMatch.revision(ifMatch);
		this.rateLimiter.check("quiz-update", actor.userId(), 120, Duration.ofMinutes(10));
		this.service.delete(actor, courseId, current, revision);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@GetMapping("/api/courses/{courseId}/quizzes")
	ResponseEntity<List<QuizItem>> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		CourseRole role = this.access.role(actor.userId(), courseId);
		LocalDateTime now = this.access.now();
		List<Quiz> all = this.access.all(courseId);
		if (role == CourseRole.INSTRUCTOR) {
			Map<Long, Counts> counts = this.attempts.counts(all.stream().map(Quiz::quizId).toList());
			return ok(all.stream()
				.map((q) -> QuizItem.of(q, now, null, null, counts.getOrDefault(q.quizId(), new Counts(0, 0, 0))))
				.toList());
		}
		List<Quiz> visible = all.stream().filter((q) -> q.activity().openToStudents(now)).toList();
		Map<Long, List<Attempt>> mine = this.attempts.ofUser(visible.stream().map(Quiz::quizId).toList(),
				actor.userId());
		return ok(visible.stream().map((q) -> {
			List<Attempt> own = mine.getOrDefault(q.quizId(), List.of());
			return QuizItem.of(q, now, own.isEmpty() ? "not_started" : own.getLast().status(), own.size(), null);
		}).toList());
	}

	@GetMapping("/api/courses/{courseId}/quizzes/{quizId}")
	ResponseEntity<QuizDetail> get(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId) {
		CourseRole role = this.access.role(actor.userId(), courseId);
		LocalDateTime now = this.access.now();
		Quiz quiz = this.access.viewable(role, courseId, quizId, now);
		if (role == CourseRole.INSTRUCTOR) {
			return ok(instructorView(quiz, now));
		}
		return ok(studentView(quiz, actor.userId(), now));
	}

	private InstructorQuiz instructorView(Quiz quiz, LocalDateTime now) {
		List<Question> questions = this.records.questions(quiz.quizId());
		Counts counts = this.attempts.counts(List.of(quiz.quizId())).getOrDefault(quiz.quizId(), new Counts(0, 0, 0));
		return new InstructorQuiz(Info.of(quiz, questions.size()), quiz.settings().revision(), quiz.phase(now).code(), now,
				this.records.hasAttempts(quiz.quizId()), counts.inProgress(), counts.finished(), counts.awaitingGrading(),
				questions.stream().map(InstructorQuestion::of).toList());
	}

	/**
	 * 학생 본인의 퀴즈 상태. 시작할 수 있는지는 서버가 정한다. 총점과 성적 점수는 {@link Quiz#release}가 허락할 때만 넣는다.
	 */
	private StudentQuiz studentView(Quiz quiz, long userId, LocalDateTime now) {
		Release release = quiz.release(now);
		List<Attempt> own = this.attempts.ofUser(quiz.quizId(), userId);
		boolean canStart = quiz.canStart(own, now);
		// 공개 전에는 점수를 읽지도 않는다. 응답에 들어갈 길을 만들지 않는다(SEC-14).
		BigDecimal score = release.scores() ? this.attempts.grade(quiz.settings().gradeItemId(), userId).orElse(null)
				: null;
		return new StudentQuiz(Info.of(quiz, this.records.questionCounts(List.of(quiz.quizId())).getOrDefault(quiz.quizId(), 0)),
				quiz.phase(now).code(), now, canStart, own.size(),
				own.stream().map((a) -> AttemptSummary.of(a, release)).toList(), score);
	}

	private static QuizInput validated(boolean online, Integer weekNo, String rawTitle, String rawDescription,
			LocalDateTime rawOpenAt, LocalDateTime rawCloseAt, Boolean published, Integer timeLimitMinutes,
			Integer attemptLimit, Boolean shuffle, LocalDateTime rawReleaseAt, Boolean answersVisible,
			BigDecimal rawMaxScore) {
		if (weekNo == null || weekNo < 1 || weekNo > MAX_WEEK) {
			throw ApiException.invalid("주차는 1주 이상 " + MAX_WEEK + "주 이하여야 합니다.");
		}
		String title = PlainText.line(rawTitle, MAX_TITLE, "제목");
		String description = PlainText.block(rawDescription, MAX_DESCRIPTION, "설명");
		if (rawCloseAt == null) {
			throw ApiException.invalid("마감 일시를 정해야 합니다.");
		}
		// 활동의 기간 컬럼은 초 단위다. 반올림되지 않도록 초 아래를 버린다.
		LocalDateTime closeAt = rawCloseAt.truncatedTo(ChronoUnit.SECONDS);
		LocalDateTime openAt = rawOpenAt == null ? null : rawOpenAt.truncatedTo(ChronoUnit.SECONDS);
		if (openAt != null && !openAt.isBefore(closeAt)) {
			throw ApiException.invalid("시작 일시는 마감 일시보다 앞이어야 합니다.");
		}
		LocalDateTime releaseAt = rawReleaseAt == null ? null : rawReleaseAt.truncatedTo(ChronoUnit.SECONDS);
		BigDecimal maxScore = null;
		if (online) {
			if (timeLimitMinutes != null && (timeLimitMinutes < 1 || timeLimitMinutes > MAX_TIME_LIMIT)) {
				throw ApiException.invalid("제한 시간은 1분 이상 " + MAX_TIME_LIMIT + "분 이하여야 합니다.");
			}
			if (attemptLimit != null && (attemptLimit < 1 || attemptLimit > MAX_ATTEMPTS)) {
				throw ApiException.invalid("응시 횟수는 1회 이상 " + MAX_ATTEMPTS + "회 이하여야 합니다.");
			}
			if (rawMaxScore != null) {
				throw ApiException.invalid("온라인 시험의 배점은 문항 배점의 합입니다. maxScore를 보내지 않습니다.");
			}
		}
		else {
			if (timeLimitMinutes != null || attemptLimit != null || Boolean.TRUE.equals(shuffle)
					|| Boolean.TRUE.equals(answersVisible)) {
				throw ApiException.invalid("오프라인 시험에는 응시 설정을 보내지 않습니다.");
			}
			maxScore = PlainText.points(rawMaxScore, MAX_SCORE, "배점");
			if (maxScore.signum() == 0) {
				throw ApiException.invalid("배점은 0보다 커야 합니다.");
			}
		}
		return new QuizInput(
				new QuizActivityInput(weekNo, title, description, openAt, closeAt, published == null || published,
						!online),
				timeLimitMinutes, attemptLimit, Boolean.TRUE.equals(shuffle), releaseAt, Boolean.TRUE.equals(answersVisible),
				maxScore);
	}

	private static List<QuestionInput> noQuestions(List<QuestionRequest> questions) {
		if (questions != null && !questions.isEmpty()) {
			throw ApiException.invalid("오프라인 시험에는 문항이 없습니다.");
		}
		return List.of();
	}

	private static List<QuestionInput> questions(List<QuestionRequest> requests) {
		if (requests == null || requests.isEmpty() || requests.size() > MAX_QUESTIONS) {
			throw ApiException.invalid("questions는 1개 이상 " + MAX_QUESTIONS + "개 이하여야 합니다.");
		}
		List<QuestionInput> questions = new ArrayList<>();
		BigDecimal total = BigDecimal.ZERO;
		for (QuestionRequest request : requests) {
			if (request == null) {
				throw ApiException.invalid("questions가 올바르지 않습니다.");
			}
			String type = request.type();
			if (!Question.SINGLE.equals(type) && !Question.MULTIPLE.equals(type) && !Question.SHORT.equals(type)) {
				throw ApiException.invalid("문항의 type은 single, multiple, short 가운데 하나여야 합니다.");
			}
			String text = PlainText.block(request.text(), MAX_QUESTION_TEXT, "문항 내용");
			if (text == null) {
				throw ApiException.invalid("문항 내용을 입력해야 합니다.");
			}
			BigDecimal points = PlainText.points(request.points(), MAX_SCORE, "문항 배점");
			if (points.signum() == 0) {
				throw ApiException.invalid("문항 배점은 0보다 커야 합니다.");
			}
			total = total.add(points);
			String explanation = PlainText.block(request.explanation(), MAX_QUESTION_TEXT, "해설");
			questions.add(new QuestionInput(type, text, points, explanation, options(type, request.options())));
		}
		if (total.compareTo(MAX_SCORE) > 0) {
			throw ApiException.invalid("문항 배점의 합은 " + MAX_SCORE.toPlainString() + " 이하여야 합니다.");
		}
		return List.copyOf(questions);
	}

	private static List<OptionInput> options(String type, List<OptionRequest> requests) {
		if (Question.SHORT.equals(type)) {
			if (requests != null && !requests.isEmpty()) {
				throw ApiException.invalid("단답형 문항에는 보기가 없습니다.");
			}
			return List.of();
		}
		if (requests == null || requests.size() < 2 || requests.size() > MAX_OPTIONS) {
			throw ApiException.invalid("선택 문항의 보기는 2개 이상 " + MAX_OPTIONS + "개 이하여야 합니다.");
		}
		List<OptionInput> options = new ArrayList<>();
		for (OptionRequest request : requests) {
			if (request == null) {
				throw ApiException.invalid("options가 올바르지 않습니다.");
			}
			options.add(new OptionInput(PlainText.line(request.text(), MAX_OPTION_TEXT, "보기"),
					Boolean.TRUE.equals(request.correct())));
		}
		long correct = options.stream().filter(OptionInput::correct).count();
		if (Question.SINGLE.equals(type) ? correct != 1 : correct < 1) {
			throw ApiException.invalid("단일 선택은 정답 보기가 하나, 복수 선택은 하나 이상이어야 합니다.");
		}
		return List.copyOf(options);
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/**
	 * 퀴즈 등록. 작성자와 과목은 받지 않는다. session과 경로에서 정한다(SC-02).
	 *
	 * @param examType online(기본) 또는 offline. 등록한 뒤에는 바꾸지 않는다
	 * @param timeLimitMinutes 응시 제한 시간. 없으면 응시 마감까지
	 * @param attemptLimit 허용 응시 횟수. 없으면 제한 없음
	 * @param resultReleaseAt 총점 공개 일시. 없으면 비공개
	 * @param answersVisible 총점 공개 뒤, 응시 기간이 끝나면 정답·해설·문항별 점수도 보여 줄지
	 * @param maxScore 오프라인 시험의 배점. 온라인 시험은 문항 배점의 합이다
	 * @param questions 온라인 시험의 문항(출제 순서)
	 */
	record CreateQuizRequest(Integer weekNo, String title, String description, LocalDateTime openAt,
			LocalDateTime closeAt, Boolean published, String examType, Integer timeLimitMinutes, Integer attemptLimit,
			Boolean shuffleQuestions, LocalDateTime resultReleaseAt, Boolean answersVisible, BigDecimal maxScore,
			List<QuestionRequest> questions) {
	}

	/** @param revision 읽은 퀴즈 수정 번호 */
	record UpdateQuizRequest(Integer weekNo, String title, String description, LocalDateTime openAt,
			LocalDateTime closeAt, Boolean published, Integer timeLimitMinutes, Integer attemptLimit,
			Boolean shuffleQuestions, LocalDateTime resultReleaseAt, Boolean answersVisible, BigDecimal maxScore,
			Long revision) {
	}

	/** @param revision 읽은 퀴즈 수정 번호 */
	record QuestionsRequest(Long revision, List<QuestionRequest> questions) {
	}

	/**
	 * 재채점 요청. 지문과 보기 문구는 받지 않는다.
	 *
	 * @param revision 읽은 퀴즈 수정 번호
	 * @param points 새 문항 배점. 바꾸지 않으면 지금 값을 보낸다
	 * @param correctOptionIds 선택 문항의 새 정답 보기 전체. 단답형이면 보내지 않는다
	 */
	record AnswerKeyRequest(Long revision, BigDecimal points, List<Long> correctOptionIds) {
	}

	/**
	 * @param quiz 바뀐 정답과 배점, 새 수정 번호
	 * @param rescoredAttempts 다시 채점한 닫힌 응시 수
	 */
	record RegradeResult(InstructorQuiz quiz, int rescoredAttempts) {
	}

	/**
	 * @param type single(단일 선택), multiple(복수 선택), short(단답형, 담당자가 채점)
	 * @param points 문항 배점(소수 둘째 자리까지)
	 * @param explanation 해설. 정답을 공개할 때 학생에게 보인다
	 */
	record QuestionRequest(String type, String text, BigDecimal points, String explanation,
			List<OptionRequest> options) {
	}

	record OptionRequest(String text, Boolean correct) {
	}

	/** 담당자와 학생에게 같은 퀴즈 정보. 정답과 문항 내용은 들어 있지 않다. */
	record Info(long quizId, long activityId, int weekNo, String title, String description, LocalDateTime openAt,
			LocalDateTime closeAt, boolean published, String examType, Integer timeLimitMinutes, Integer attemptLimit,
			boolean shuffleQuestions, BigDecimal maxScore, int questionCount, LocalDateTime resultReleaseAt,
			boolean answersVisible) {

		static Info of(Quiz quiz, int questionCount) {
			QuizActivity activity = quiz.activity();
			return new Info(quiz.quizId(), activity.activityId(), activity.weekNo(), activity.title(),
					activity.description(), activity.openAt(), activity.closeAt(), activity.published(),
					quiz.settings().examType(), quiz.settings().durationMinutes(), quiz.settings().attemptLimit(),
					quiz.settings().shuffle(), quiz.settings().maxScore(), questionCount, quiz.settings().releaseAt(),
					quiz.settings().answersVisible());
		}

	}

	sealed interface QuizDetail permits InstructorQuiz, StudentQuiz {
	}

	/**
	 * @param revision 수정 요청에 그대로 보낼 수정 번호
	 * @param phase 지금 응시 구간: not_open, open, closed
	 * @param questionsLocked 응시가 있어 문항을 바꿀 수 없는지
	 */
	record InstructorQuiz(Info quiz, long revision, String phase, LocalDateTime serverTime, boolean questionsLocked,
			int inProgressCount, int finishedCount, int awaitingGradingCount, List<InstructorQuestion> questions)
			implements QuizDetail {
	}

	record InstructorQuestion(long questionId, int number, String type, String text, BigDecimal points,
			String explanation, List<InstructorOption> options) {

		static InstructorQuestion of(Question q) {
			return new InstructorQuestion(q.questionId(), q.questionNo(), q.type(), q.text(), q.points(),
					q.explanation(),
					q.options().stream().map((o) -> new InstructorOption(o.optionId(), o.text(), o.correct())).toList());
		}

	}

	record InstructorOption(long optionId, String text, boolean correct) {
	}

	/**
	 * @param canStart 지금 응시를 시작하거나 진행 중 응시를 이어갈 수 있는지. 서버가 정한다
	 * @param attemptsUsed 시작한 응시 수
	 * @param score 이 퀴즈의 성적 점수. 결과 공개 전에는 속성이 없다
	 */
	record StudentQuiz(Info quiz, String phase, LocalDateTime serverTime, boolean canStart, int attemptsUsed,
			List<AttemptSummary> attempts, @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal score)
			implements QuizDetail {
	}

	/** @param totalScore 결과 공개 뒤, 채점을 마친 응시에만 있다 */
	record AttemptSummary(long attemptId, int attemptNo, String status, LocalDateTime startedAt,
			LocalDateTime deadlineAt, LocalDateTime submittedAt, String finishReason,
			@JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal totalScore) {

		static AttemptSummary of(Attempt a, Release release) {
			return new AttemptSummary(a.attemptId(), a.attemptNo(), a.status(), a.startedAt(), a.deadlineAt(),
					a.submittedAt(), a.finishReason(), release.scores() ? a.totalScore() : null);
		}

	}

	/**
	 * 목록 항목. 담당자에게는 응시 수를, 학생에게는 본인 상태를 담는다.
	 *
	 * @param status 학생의 마지막 응시 상태: not_started, in_progress, submitted, graded. 담당자 응답에는 없다
	 */
	record QuizItem(long quizId, long activityId, int weekNo, String title, LocalDateTime openAt, LocalDateTime closeAt,
			boolean published, String examType, String phase, @JsonInclude(JsonInclude.Include.NON_NULL) String status,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer attemptsUsed,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer inProgressCount,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer finishedCount,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer awaitingGradingCount) {

		static QuizItem of(Quiz q, LocalDateTime now, String status, Integer attemptsUsed, Counts counts) {
			QuizActivity activity = q.activity();
			return new QuizItem(q.quizId(), activity.activityId(), activity.weekNo(), activity.title(), activity.openAt(),
					activity.closeAt(), activity.published(), q.settings().examType(), q.phase(now).code(), status,
					attemptsUsed,
					counts == null ? null : counts.inProgress(), counts == null ? null : counts.finished(),
					counts == null ? null : counts.awaitingGrading());
		}

	}

}
