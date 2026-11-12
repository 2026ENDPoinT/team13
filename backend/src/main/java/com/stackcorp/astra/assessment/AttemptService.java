package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.stackcorp.astra.assessment.AttemptRecords.Answer;
import com.stackcorp.astra.assessment.AttemptRecords.Attempt;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 퀴즈 응시 시작, 답안 저장, 제출, 담당자 채점(SC-05, SC-06, SEC-13, CHG-04, ADR-0021). 권한은 {@link QuizAccess}로 먼저 판정한다.
 * <ul>
 * <li>시작: 진행 중 응시는 학생당 하나다(유일 제약 {@code uq_quiz_attempts_active}). 진행 중 응시가 있으면 새로 만들지 않고 그
 * 응시를 돌려준다. 다른 session에서 시작하면 서버가 그 session으로 응시를 옮기고 감사 기록을 남긴다. 마감 일시는 바꾸지 않는다.
 * client가 보내는 재진입 표시는 받지 않는다. 마감 일시는 {@code min(시작 + 제한 시간, 응시 마감)}으로 시작할 때 고정한다.</li>
 * <li>저장: 응시 행을 잠근 뒤 DB 시계로 마감 전일 때만 받는다. 문항마다 학생이 읽은 개정 번호가 맞아야 한다.</li>
 * <li>제출: Idempotency-Key가 반드시 있어야 한다. 마감 뒤의 제출은 마지막으로 저장한 답안으로 닫는다. 이미 닫힌 응시면 그 상태를
 * receipt로 돌려준다.</li>
 * </ul>
 * 원장을 쓰지 않는 명령은 READ COMMITTED transaction에서 실행하고, lock 대기 초과나 deadlock이면 정해진 횟수만 다시 시도한다.
 */
@Service
class AttemptService {

	private static final int RETRIES = 3;

	private final QuizAccess access;

	private final QuizRecords quizzes;

	private final AttemptRecords attempts;

	private final AttemptScoring scoring;

	private final QuizClock clock;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final RateLimiter rateLimiter;

	private final TransactionTemplate readCommitted;

	AttemptService(QuizAccess access, QuizRecords quizzes, AttemptRecords attempts, AttemptScoring scoring,
			QuizClock clock, Idempotency idempotency, AuditLog audit, RateLimiter rateLimiter,
			PlatformTransactionManager transactionManager) {
		this.access = access;
		this.quizzes = quizzes;
		this.attempts = attempts;
		this.scoring = scoring;
		this.clock = clock;
		this.idempotency = idempotency;
		this.audit = audit;
		this.rateLimiter = rateLimiter;
		this.readCommitted = readCommitted(transactionManager);
	}

	static TransactionTemplate readCommitted(PlatformTransactionManager transactionManager) {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		return template;
	}

	/** lock 대기 초과나 deadlock으로 되돌아간 transaction을 정해진 횟수만 다시 실행한다. */
	static <T> T retrying(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return work.get();
			}
			catch (TransientDataAccessException ex) {
				if (attempt == RETRIES) {
					throw ex;
				}
			}
		}
	}

	/** 응시 시작. 진행 중 응시가 있으면 그 응시다. */
	Started start(Actor actor, long courseId, long quizId) {
		Quiz quiz = this.access.forStudent(actor.userId(), courseId, quizId, this.access.now());
		if (!quiz.online()) {
			throw new ApiException(HttpStatus.CONFLICT, "offline_quiz", "오프라인 시험은 온라인으로 응시할 수 없습니다.");
		}
		this.rateLimiter.check("quiz-start", actor.userId(), 30, Duration.ofMinutes(10));
		String session = sessionKey(actor);
		// 마감이 지난 진행 중 응시를 먼저 닫은 경우에만 다시 돈다. 닫은 결과는 새 응시를 만들 수 없더라도 commit해 둔다.
		for (int round = 0; round < 2; round++) {
			List<Long> known = ids(quizId, actor.userId());
			Started started = retrying(
					() -> this.readCommitted.execute((tx) -> startOnce(actor, courseId, quizId, known, session)));
			if (started != null) {
				return started;
			}
		}
		throw attemptConflict();
	}

	private Started startOnce(Actor actor, long courseId, long quizId, List<Long> known, String session) {
		this.quizzes.lockShared(quizId);
		List<Attempt> locked = this.attempts.lockAll(quizId, actor.userId(), known);
		LocalDateTime now = this.clock.now();
		Quiz quiz = this.access.find(courseId, quizId)
			.filter((q) -> q.activity().openToStudents(now))
			.orElseThrow(ApiException::notFound);
		Optional<Attempt> inFlight = locked.stream().filter(Attempt::inProgress).findFirst();
		if (inFlight.isPresent()) {
			Attempt current = inFlight.get();
			if (!current.accepting(now)) {
				this.scoring.finish(quiz.settings(), locked, current, now);
				this.audit.record(actor.userId(), actor.sessionId(), "quiz.attempt.finalize", "quiz_attempt",
						current.attemptId(), courseId, AttemptScoring.REASON_DEADLINE);
				return null;
			}
			if (!session.equals(current.sessionKey())) {
				this.attempts.takeOver(current.attemptId(), session, now);
				this.audit.record(actor.userId(), actor.sessionId(), "quiz.attempt.resume", "quiz_attempt",
						current.attemptId(), courseId, "taken_over");
			}
			return new Started(this.attempts.find(current.attemptId()).orElseThrow(), false);
		}
		if (quiz.phase(now) != Quiz.Phase.OPEN) {
			throw new ApiException(HttpStatus.CONFLICT, "deadline_passed", "응시 기간이 아닙니다.");
		}
		int attemptNo = locked.stream().mapToInt(Attempt::attemptNo).max().orElse(0) + 1;
		Integer limit = quiz.settings().attemptLimit();
		if (limit != null && attemptNo > limit) {
			throw new ApiException(HttpStatus.CONFLICT, "attempt_limit_reached", "응시 횟수를 모두 썼습니다.");
		}
		LocalDateTime closeAt = quiz.activity().closeAt();
		Integer minutes = quiz.settings().durationMinutes();
		LocalDateTime deadline = minutes == null || now.plusMinutes(minutes).isAfter(closeAt) ? closeAt
				: now.plusMinutes(minutes);
		long attemptId;
		try {
			attemptId = this.attempts.insert(quizId, actor.userId(), attemptNo, now, deadline, session);
		}
		catch (DuplicateKeyException ex) {
			// 같은 학생의 다른 시작 요청이 먼저 commit했다. 그 응시를 돌려준다.
			return this.attempts.inFlightShared(quizId, actor.userId())
				.map((a) -> new Started(a, false))
				.orElseThrow(AttemptService::attemptConflict);
		}
		this.audit.success(actor.userId(), actor.sessionId(), "quiz.attempt.start", "quiz_attempt", attemptId, courseId);
		return new Started(this.attempts.find(attemptId).orElseThrow(), true);
	}

	/**
	 * 답안 하나를 저장한다. 응시가 닫혔거나 DB 시계로 마감이 지났거나 다른 session으로 옮겨졌으면 409다.
	 *
	 * @param optionIds 선택 문항에서 고른 보기. 문항에 속하는지 부르는 쪽이 확인했다
	 * @param text 단답형 답. 선택 문항이면 null
	 */
	Saved save(Actor actor, long courseId, Attempt attempt, long questionId, int expectedRevision, List<Long> optionIds,
			String text) {
		this.rateLimiter.check("quiz-answer-save", actor.userId(), 600, Duration.ofMinutes(10));
		String session = sessionKey(actor);
		return retrying(() -> this.readCommitted.execute((tx) -> {
			Attempt locked = this.attempts.lock(attempt.attemptId()).orElseThrow(ApiException::notFound);
			LocalDateTime now = this.clock.now();
			if (!locked.inProgress()) {
				throw new ApiException(HttpStatus.CONFLICT, "attempt_closed", "이미 제출한 응시입니다.");
			}
			if (!locked.accepting(now)) {
				throw new ApiException(HttpStatus.CONFLICT, "deadline_passed", "응시 시간이 끝났습니다.");
			}
			if (!session.equals(locked.sessionKey())) {
				throw attemptMoved();
			}
			Answer current = this.attempts.answers(locked.attemptId()).get(questionId);
			int revision = current == null ? 0 : current.revision();
			if (revision != expectedRevision) {
				throw new ApiException(HttpStatus.CONFLICT, "answer_conflict", "다른 저장이 먼저 반영되었습니다. 다시 불러온 뒤 저장해 주세요.");
			}
			long answerId;
			if (current == null) {
				answerId = this.attempts.insertAnswer(locked.attemptId(), questionId, text, 1, now);
			}
			else {
				answerId = current.answerId();
				this.attempts.updateAnswer(answerId, text, now);
			}
			this.attempts.replaceOptions(answerId, optionIds);
			this.audit.success(actor.userId(), actor.sessionId(), "quiz.answer.save", "quiz_attempt", locked.attemptId(),
					courseId);
			return new Saved(questionId, revision + 1, now);
		}));
	}

	/**
	 * 제출. 원장의 transaction 안에서 그 학생의 응시를 먼저 잠근다. 이미 닫힌 응시면 닫힌 상태를 receipt로 돌려준다.
	 */
	Receipt submit(Actor actor, long courseId, Quiz quiz, Attempt attempt, String key) {
		this.rateLimiter.check("quiz-submit", actor.userId(), 30, Duration.ofMinutes(10));
		String session = sessionKey(actor);
		List<Long> known = ids(quiz.quizId(), actor.userId());
		return this.idempotency.execute(actor.userId(), "quiz.attempt.submit", key,
				List.of(courseId, quiz.quizId(), attempt.attemptId()), Receipt.class, () -> {
					List<Attempt> locked = this.attempts.lockAll(quiz.quizId(), actor.userId(), known);
					Attempt target = locked.stream()
						.filter((a) -> a.attemptId() == attempt.attemptId())
						.findFirst()
						.orElseThrow(ApiException::notFound);
					if (!target.inProgress()) {
						return Receipt.of(target);
					}
					LocalDateTime now = this.clock.now();
					if (target.accepting(now) && !session.equals(target.sessionKey())) {
						throw attemptMoved();
					}
					Attempt finished = this.scoring.finish(quiz.settings(), locked, target, now);
					this.audit.record(actor.userId(), actor.sessionId(), "quiz.attempt.submit", "quiz_attempt",
							finished.attemptId(), courseId, finished.finishReason());
					return Receipt.of(finished);
				});
	}

	/** 담당자가 단답형 답안 하나를 채점한다. 점수 범위는 부르는 쪽이 확인했다. */
	Attempt grade(Actor actor, long courseId, Quiz quiz, Attempt attempt, long questionId, BigDecimal score,
			String comment) {
		this.rateLimiter.check("quiz-grade", actor.userId(), 600, Duration.ofMinutes(10));
		List<Long> known = ids(quiz.quizId(), attempt.userId());
		return retrying(() -> this.readCommitted.execute((tx) -> {
			List<Attempt> locked = this.attempts.lockAll(quiz.quizId(), attempt.userId(), known);
			Attempt target = locked.stream()
				.filter((a) -> a.attemptId() == attempt.attemptId())
				.findFirst()
				.orElseThrow(ApiException::notFound);
			if (target.inProgress()) {
				throw new ApiException(HttpStatus.CONFLICT, "attempt_not_submitted", "제출하지 않은 응시는 채점할 수 없습니다.");
			}
			Answer answer = Optional.ofNullable(this.attempts.answers(target.attemptId()).get(questionId))
				.orElseThrow(ApiException::notFound);
			Attempt graded = this.scoring.gradeAnswer(quiz.settings(), locked, target, answer, score, comment,
					actor.userId(), this.clock.now());
			this.audit.success(actor.userId(), actor.sessionId(), "quiz.answer.grade", "quiz_attempt",
					graded.attemptId(), courseId);
			return graded;
		}));
	}

	private List<Long> ids(long quizId, long userId) {
		return this.attempts.ofUser(quizId, userId).stream().map(Attempt::attemptId).toList();
	}

	/** 응시를 이어 쓰는 session의 식별자. session 토큰이 아니라 session 행 ID다. */
	private static String sessionKey(Actor actor) {
		return Long.toString(actor.sessionId());
	}

	private static ApiException attemptMoved() {
		return new ApiException(HttpStatus.CONFLICT, "attempt_moved", "다른 기기나 브라우저에서 이어서 응시하고 있습니다.");
	}

	private static ApiException attemptConflict() {
		return new ApiException(HttpStatus.CONFLICT, "attempt_conflict", "응시를 시작하지 못했습니다. 다시 시도해 주세요.");
	}

	/** @param created 이 요청이 새 응시를 만들었는지. 아니면 진행 중 응시를 돌려줬다 */
	record Started(Attempt attempt, boolean created) {
	}

	/**
	 * @param revision 저장한 뒤의 개정 번호. 다음 저장에 보낸다
	 * @param savedAt 서버가 저장한 시각(DB 시계)
	 */
	record Saved(long questionId, int revision, LocalDateTime savedAt) {
	}

	/**
	 * 제출 receipt. 같은 Idempotency-Key로 다시 보내면 같은 값을 돌려준다. 점수는 담지 않는다. 점수 공개는 조회할 때마다 다시 판정한다.
	 *
	 * @param status submitted(수동 채점 대기) 또는 graded
	 * @param submittedAt 접수 시각. 마감 뒤에 닫았으면 마감 일시
	 * @param finishReason submitted(학생 제출) 또는 deadline(마감 일시에 닫음)
	 */
	record Receipt(long attemptId, int attemptNo, String status, LocalDateTime submittedAt, String finishReason) {

		static Receipt of(Attempt attempt) {
			return new Receipt(attempt.attemptId(), attempt.attemptNo(), attempt.status(), attempt.submittedAt(),
					attempt.finishReason());
		}

	}

}
