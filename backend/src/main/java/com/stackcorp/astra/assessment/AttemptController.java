package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.assessment.AttemptRecords.Answer;
import com.stackcorp.astra.assessment.AttemptRecords.Attempt;
import com.stackcorp.astra.assessment.AttemptService.Receipt;
import com.stackcorp.astra.assessment.AttemptService.Saved;
import com.stackcorp.astra.assessment.AttemptService.Started;
import com.stackcorp.astra.assessment.Quiz.Release;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 퀴즈 응시와 채점(EP-0103, EP-0107, EP-0108의 대체 계약). 응시자는 session에서 정한다(SC-02). 학생은 자기 응시만 시작하고, 읽고,
 * 저장하고, 제출한다. 다른 학생의 응시는 어떤 경로로도 보이지 않는다. 문항은 시작한 응시의 조회로만 받고, 정답·해설·문항별 점수·총점은
 * {@link Quiz#release}가 허락할 때만 학생 응답에 들어간다. 허락하지 않으면 속성 자체가 없다(SEC-13, SEC-14).
 * 담당자는 그 퀴즈의 모든 응시와 답안을 보고 단답형을 채점한다.
 */
@RestController
class AttemptController {

	private static final int MAX_ANSWER = 2_000;

	private static final int MAX_FEEDBACK = 1_000;

	private static final int MAX_PAGE = 100;

	private final QuizAccess access;

	private final AttemptService service;

	private final QuizRecords quizzes;

	private final AttemptRecords attempts;

	private final UserNames userNames;

	AttemptController(QuizAccess access, AttemptService service, QuizRecords quizzes, AttemptRecords attempts,
			UserNames userNames) {
		this.access = access;
		this.service = service;
		this.quizzes = quizzes;
		this.attempts = attempts;
		this.userNames = userNames;
	}

	/**
	 * 응시 시작. 새 응시면 201, 진행 중 응시를 돌려주면 200이다. 본문은 비우거나 {@code {}}로 보낸다. 재진입 여부는 서버가 정하므로
	 * {@code force} 같은 필드는 받지 않는다(SEC-13).
	 */
	@PostMapping("/api/courses/{courseId}/quizzes/{quizId}/attempts")
	ResponseEntity<AttemptState> start(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @RequestBody(required = false) StartRequest body) {
		Started started = this.service.start(actor, courseId, quizId);
		return ResponseEntity.status(started.created() ? HttpStatus.CREATED : HttpStatus.OK)
			.cacheControl(CacheControl.noStore())
			.body(AttemptState.of(started.attempt(), this.access.now()));
	}

	/** 담당자의 응시 목록. 응시 ID 순서이고 {@code nextCursor}로 다음 쪽을 받는다. */
	@GetMapping("/api/courses/{courseId}/quizzes/{quizId}/attempts")
	ResponseEntity<AttemptPage> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "50") int size) {
		Quiz quiz = this.access.managed(actor.userId(), courseId, quizId);
		if (size < 1 || size > MAX_PAGE) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE + " 이하여야 합니다.");
		}
		long after = cursor(cursor);
		List<Attempt> page = this.attempts.page(quiz.quizId(), after, size + 1);
		boolean more = page.size() > size;
		List<Attempt> items = more ? page.subList(0, size) : page;
		Map<Long, String> names = this.userNames.namesOf(items.stream().map(Attempt::userId).distinct().toList());
		Map<Long, Integer> pending = this.attempts.pendingCounts(items.stream().map(Attempt::attemptId).toList());
		return ok(new AttemptPage(items.stream()
			.map((a) -> new AttemptRow(a.attemptId(), a.userId(), names.get(a.userId()), a.attemptNo(), a.status(),
					a.startedAt(), a.deadlineAt(), a.submittedAt(), a.finishReason(), a.totalScore(),
					pending.getOrDefault(a.attemptId(), 0)))
			.toList(), more ? Long.toString(items.getLast().attemptId()) : null));
	}

	/** 학생에게는 본인 응시와 문항, 담당자에게는 그 퀴즈의 응시와 채점 내용. */
	@GetMapping("/api/courses/{courseId}/quizzes/{quizId}/attempts/{attemptId}")
	ResponseEntity<AttemptDetail> get(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @PathVariable long attemptId) {
		CourseRole role = this.access.role(actor.userId(), courseId);
		LocalDateTime now = this.access.now();
		if (role == CourseRole.INSTRUCTOR) {
			Quiz quiz = this.access.managed(actor.userId(), courseId, quizId);
			return ok(instructorView(this.access.attemptOf(quiz, attemptId)));
		}
		Quiz quiz = this.access.forStudent(actor.userId(), courseId, quizId, now);
		Attempt attempt = this.access.ownAttempt(quiz, actor.userId(), attemptId);
		return ok(studentView(quiz, attempt, now));
	}

	/** 자동저장. 문항마다 읽은 {@code revision}을 보낸다. 처음이면 0이다. */
	@PutMapping("/api/courses/{courseId}/quizzes/{quizId}/attempts/{attemptId}/answers/{questionId}")
	ResponseEntity<Saved> save(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @PathVariable long attemptId, @PathVariable long questionId,
			@RequestBody SaveRequest body) {
		Quiz quiz = this.access.forStudent(actor.userId(), courseId, quizId, this.access.now());
		Attempt attempt = this.access.ownAttempt(quiz, actor.userId(), attemptId);
		Question question = question(quiz, questionId);
		if (body.revision() == null || body.revision() < 0) {
			throw ApiException.invalid("revision은 0 이상이어야 합니다.");
		}
		List<Long> optionIds = body.optionIds() == null ? List.of() : body.optionIds();
		String text = null;
		if (question.objective()) {
			if (body.text() != null) {
				throw ApiException.invalid("선택 문항에는 text를 보내지 않습니다.");
			}
			if (optionIds.stream().anyMatch((id) -> id == null || !question.hasOption(id))
					|| new LinkedHashSet<>(optionIds).size() != optionIds.size()
					|| (Question.SINGLE.equals(question.type()) && optionIds.size() > 1)) {
				throw ApiException.invalid("optionIds가 올바르지 않습니다.");
			}
		}
		else {
			if (!optionIds.isEmpty()) {
				throw ApiException.invalid("단답형 문항에는 optionIds를 보내지 않습니다.");
			}
			text = PlainText.block(body.text(), MAX_ANSWER, "text");
		}
		return ok(this.service.save(actor, courseId, attempt, question.questionId(), body.revision(),
				optionIds.stream().sorted().toList(), text));
	}

	/**
	 * 제출. {@code Idempotency-Key}가 반드시 있어야 한다. 같은 key의 재전송은 처음 receipt를 받는다. 마감 뒤에 보내면 마지막으로 저장한
	 * 답안으로 닫는다. 이미 닫힌 응시면 닫힌 상태를 돌려준다.
	 */
	@PostMapping("/api/courses/{courseId}/quizzes/{quizId}/attempts/{attemptId}/submit")
	ResponseEntity<Receipt> submit(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @PathVariable long attemptId,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		Quiz quiz = this.access.forStudent(actor.userId(), courseId, quizId, this.access.now());
		Attempt attempt = this.access.ownAttempt(quiz, actor.userId(), attemptId);
		Idempotency.requireKey(idempotencyKey);
		return ok(this.service.submit(actor, courseId, quiz, attempt, idempotencyKey));
	}

	/** 담당자의 단답형 채점. 점수는 0 이상 문항 배점 이하, 의견은 일반 텍스트다. 같은 값으로 다시 보내도 결과가 같다. */
	@PutMapping("/api/courses/{courseId}/quizzes/{quizId}/attempts/{attemptId}/answers/{questionId}/grade")
	ResponseEntity<InstructorAttempt> grade(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long quizId, @PathVariable long attemptId, @PathVariable long questionId,
			@RequestBody GradeRequest body) {
		Quiz quiz = this.access.managed(actor.userId(), courseId, quizId);
		Attempt attempt = this.access.attemptOf(quiz, attemptId);
		Question question = question(quiz, questionId);
		if (question.objective()) {
			throw ApiException.invalid("선택 문항은 서버가 채점합니다.");
		}
		BigDecimal score = PlainText.points(body.score(), question.points(), "score");
		String feedback = PlainText.block(body.feedback(), MAX_FEEDBACK, "feedback");
		this.service.grade(actor, courseId, quiz, attempt, question.questionId(), score, feedback);
		return ok(instructorView(this.access.attemptOf(quiz, attemptId)));
	}

	private Question question(Quiz quiz, long questionId) {
		return this.quizzes.questions(quiz.quizId())
			.stream()
			.filter((q) -> q.questionId() == questionId)
			.findFirst()
			.orElseThrow(ApiException::notFound);
	}

	private InstructorAttempt instructorView(Attempt attempt) {
		Map<Long, Answer> answers = this.attempts.answers(attempt.attemptId());
		String name = this.userNames.namesOf(List.of(attempt.userId())).get(attempt.userId());
		return new InstructorAttempt(attempt.attemptId(), attempt.userId(), name, attempt.attemptNo(), attempt.status(),
				attempt.startedAt(), attempt.deadlineAt(), attempt.submittedAt(), attempt.finishReason(),
				attempt.totalScore(), attempt.gradedAt(),
				this.quizzes.questions(attempt.quizId()).stream().map((q) -> {
					Answer a = answers.get(q.questionId());
					return new InstructorAnswer(q.questionId(), q.questionNo(), q.type(), q.text(), q.points(),
							a == null ? List.of() : a.optionIds(), a == null ? null : a.text(),
							a == null ? 0 : a.revision(), a == null ? null : a.savedAt(), a == null ? null : a.correct(),
							a == null ? null : a.awarded(), a == null ? null : a.gradingStatus(),
							a == null ? null : a.comment(), a == null ? null : a.gradedAt());
				}).toList());
	}

	/**
	 * 학생 본인의 응시. 진행 중이든 닫혔든 정답 표시, 해설, 문항별 점수, 채점 의견은 {@code release.answers()}일 때만, 총점은
	 * {@code release.scores()}일 때만 넣는다. 응시가 진행 중이면 둘 다 넣지 않는다.
	 */
	private StudentAttempt studentView(Quiz quiz, Attempt attempt, LocalDateTime now) {
		Release release = attempt.inProgress() ? new Release(false, false) : quiz.release(now);
		Map<Long, Answer> answers = this.attempts.answers(attempt.attemptId());
		List<Question> questions = this.quizzes.questions(quiz.quizId());
		if (quiz.settings().shuffle()) {
			questions = questions.stream()
				.sorted(Comparator.comparingLong((Question q) -> mix(attempt.attemptId(), q.questionId()))
					.thenComparingLong(Question::questionId))
				.toList();
		}
		int number = 0;
		List<StudentQuestion> views = new ArrayList<>();
		for (Question q : questions) {
			number++;
			Answer a = answers.get(q.questionId());
			StudentAnswer answer = new StudentAnswer(a == null ? List.of() : a.optionIds(), a == null ? null : a.text(),
					a == null ? 0 : a.revision(), a == null ? null : a.savedAt(),
					release.answers() && a != null ? a.correct() : null,
					release.answers() && a != null ? a.awarded() : null,
					release.answers() && a != null ? a.comment() : null);
			views.add(new StudentQuestion(q.questionId(), number, q.type(), q.text(), q.points(),
					q.options()
						.stream()
						.map((o) -> new StudentOption(o.optionId(), o.text(), release.answers() ? o.correct() : null))
						.toList(),
					answer, release.answers() ? q.explanation() : null));
		}
		return new StudentAttempt(attempt.attemptId(), attempt.attemptNo(), attempt.status(), attempt.accepting(now),
				attempt.startedAt(), attempt.deadlineAt(), attempt.submittedAt(), attempt.finishReason(), now,
				release.scores() ? attempt.totalScore() : null, List.copyOf(views));
	}

	/** 응시와 문항으로 정해지는 고정 순서. 다시 들어와도 같은 순서다. */
	private static long mix(long attemptId, long questionId) {
		long z = attemptId * 0x9E3779B97F4A7C15L + questionId;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		return z ^ (z >>> 31);
	}

	private static long cursor(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return 0;
		}
		try {
			long after = Long.parseLong(cursor);
			if (after < 0) {
				throw ApiException.invalid("cursor가 올바르지 않습니다.");
			}
			return after;
		}
		catch (NumberFormatException ex) {
			throw ApiException.invalid("cursor가 올바르지 않습니다.");
		}
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/** 응시 시작 본문. 받는 필드가 없다. 그 밖의 필드는 400이다. */
	record StartRequest() {
	}

	/**
	 * @param revision 학생이 읽은 그 문항 답안의 개정 번호. 저장한 적이 없으면 0
	 * @param optionIds 선택 문항에서 고른 보기. 비우면 선택을 지운다
	 * @param text 단답형 답(일반 텍스트 2000자)
	 */
	record SaveRequest(Integer revision, List<Long> optionIds, String text) {
	}

	record GradeRequest(BigDecimal score, String feedback) {
	}

	/**
	 * @param deadlineAt 서버가 시작할 때 고정한 마감 일시. 이 시각부터 저장을 받지 않는다
	 * @param serverTime 서버 시각. 남은 시간은 이 값과 deadlineAt으로 계산한다
	 */
	record AttemptState(long attemptId, int attemptNo, String status, LocalDateTime startedAt, LocalDateTime deadlineAt,
			LocalDateTime serverTime) {

		static AttemptState of(Attempt a, LocalDateTime now) {
			return new AttemptState(a.attemptId(), a.attemptNo(), a.status(), a.startedAt(), a.deadlineAt(), now);
		}

	}

	sealed interface AttemptDetail permits InstructorAttempt, StudentAttempt {
	}

	/**
	 * @param acceptingAnswers 지금 저장과 제출을 받는지(진행 중이고 DB 시계로 마감 전)
	 * @param totalScore 결과 공개 뒤, 채점을 마친 응시에만 있다
	 * @param questions 응시 순서의 문항. 섞기를 켠 퀴즈는 응시마다 순서가 다르다
	 */
	record StudentAttempt(long attemptId, int attemptNo, String status, boolean acceptingAnswers,
			LocalDateTime startedAt, LocalDateTime deadlineAt, LocalDateTime submittedAt, String finishReason,
			LocalDateTime serverTime, @JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal totalScore,
			List<StudentQuestion> questions) implements AttemptDetail {
	}

	/**
	 * @param number 이 응시에서의 문항 번호
	 * @param points 문항 배점
	 * @param explanation 해설. 정답을 공개한 뒤에만 있다
	 */
	record StudentQuestion(long questionId, int number, String type, String text, BigDecimal points,
			List<StudentOption> options, StudentAnswer answer,
			@JsonInclude(JsonInclude.Include.NON_NULL) String explanation) {
	}

	/** @param correct 정답 보기인지. 정답을 공개한 뒤에만 있다 */
	record StudentOption(long optionId, String text, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean correct) {
	}

	/**
	 * 저장한 답. 정답 여부, 문항 점수, 채점 의견은 정답을 공개한 뒤에만 있다.
	 *
	 * @param revision 다음 저장에 보낼 개정 번호
	 */
	record StudentAnswer(List<Long> optionIds, String text, int revision, LocalDateTime savedAt,
			@JsonInclude(JsonInclude.Include.NON_NULL) Boolean correct,
			@JsonInclude(JsonInclude.Include.NON_NULL) BigDecimal awardedScore,
			@JsonInclude(JsonInclude.Include.NON_NULL) String feedback) {
	}

	record InstructorAttempt(long attemptId, long studentId, String studentName, int attemptNo, String status,
			LocalDateTime startedAt, LocalDateTime deadlineAt, LocalDateTime submittedAt, String finishReason,
			BigDecimal totalScore, LocalDateTime gradedAt, List<InstructorAnswer> answers) implements AttemptDetail {
	}

	/**
	 * @param gradingStatus 응시 중이면 null, auto, pending(채점 대기), manual
	 */
	record InstructorAnswer(long questionId, int number, String type, String text, BigDecimal points,
			List<Long> optionIds, String answerText, int revision, LocalDateTime savedAt, Boolean correct,
			BigDecimal awardedScore, String gradingStatus, String feedback, LocalDateTime gradedAt) {
	}

	/** @param nextCursor 다음 쪽이 있으면 그 cursor, 없으면 null */
	record AttemptPage(List<AttemptRow> items, String nextCursor) {
	}

	/** @param pendingCount 수동 채점을 기다리는 답안 수 */
	record AttemptRow(long attemptId, long studentId, String studentName, int attemptNo, String status,
			LocalDateTime startedAt, LocalDateTime deadlineAt, LocalDateTime submittedAt, String finishReason,
			BigDecimal totalScore, int pendingCount) {
	}

}
