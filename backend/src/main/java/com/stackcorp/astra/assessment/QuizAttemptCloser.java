package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.List;

import com.stackcorp.astra.assessment.AttemptRecords.Attempt;
import com.stackcorp.astra.assessment.AttemptRecords.Expired;
import com.stackcorp.astra.assessment.Quiz.Settings;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * worker 프로필에서 마감 일시가 지난 진행 중 응시를 마지막으로 저장한 답안으로 닫는다(SC-05, CHG-04). 응시마다 READ COMMITTED
 * transaction 하나로 그 학생의 응시를 기본 키 순서로 잠그고 상태를 다시 본다. worker가 둘이어도 먼저 잠근 쪽만 닫고 다른 쪽은 닫힌
 * 상태를 보고 지나간다. lock 대기 초과나 deadlock이면 정해진 횟수만 다시 시도하고, 그래도 실패한 응시는 진행 중으로 남아 다음 주기에
 * 다시 잡힌다. 마감 뒤의 저장은 받지 않으므로 늦게 닫혀도 답안은 바뀌지 않는다. 로그에는 응시 ID와 수만 남긴다.
 */
@Component
@Profile("worker")
class QuizAttemptCloser {

	private static final Logger log = LoggerFactory.getLogger(QuizAttemptCloser.class);

	private static final int BATCH = 100;

	private static final int MAX_ROUNDS = 20;

	private final QuizRecords quizzes;

	private final AttemptRecords attempts;

	private final AttemptScoring scoring;

	private final QuizClock clock;

	private final AuditLog audit;

	private final TransactionTemplate transaction;

	QuizAttemptCloser(QuizRecords quizzes, AttemptRecords attempts, AttemptScoring scoring, QuizClock clock,
			AuditLog audit, PlatformTransactionManager transactionManager) {
		this.quizzes = quizzes;
		this.attempts = attempts;
		this.scoring = scoring;
		this.clock = clock;
		this.audit = audit;
		this.transaction = AttemptService.readCommitted(transactionManager);
	}

	@Scheduled(fixedDelayString = "${astra.quiz.close-interval:30s}", initialDelayString = "${astra.quiz.close-interval:30s}")
	void scheduled() {
		int closed = closeExpired();
		if (closed > 0) {
			log.info("quiz attempts closed at deadline count={}", closed);
		}
	}

	/** @return 이 호출이 닫은 응시 수 */
	int closeExpired() {
		int total = 0;
		for (int round = 0; round < MAX_ROUNDS; round++) {
			List<Expired> batch = this.attempts.expired(this.clock.now(), BATCH);
			int closed = 0;
			for (Expired expired : batch) {
				if (closeOne(expired)) {
					closed++;
				}
			}
			total += closed;
			if (batch.size() < BATCH || closed == 0) {
				break;
			}
		}
		return total;
	}

	private boolean closeOne(Expired expired) {
		try {
			return AttemptService.retrying(() -> Boolean.TRUE.equals(this.transaction.execute((tx) -> close(expired))));
		}
		catch (TransientDataAccessException ex) {
			log.warn("quiz attempt close failed attempt={} error={}", expired.attemptId(), ex.getClass().getSimpleName());
			return false;
		}
	}

	private boolean close(Expired expired) {
		List<Long> known = this.attempts.ofUser(expired.quizId(), expired.userId())
			.stream()
			.map(Attempt::attemptId)
			.toList();
		List<Attempt> locked = this.attempts.lockAll(expired.quizId(), expired.userId(), known);
		Attempt target = locked.stream().filter((a) -> a.attemptId() == expired.attemptId()).findFirst().orElse(null);
		LocalDateTime now = this.clock.now();
		if (target == null || !target.inProgress() || target.accepting(now)) {
			return false;
		}
		Settings quiz = this.quizzes.find(expired.quizId()).orElseThrow();
		Attempt finished = this.scoring.finish(quiz, locked, target, now);
		this.audit.record(null, null, "quiz.attempt.finalize", "quiz_attempt", finished.attemptId(), quiz.courseId(),
				AttemptScoring.REASON_DEADLINE);
		return true;
	}

}
