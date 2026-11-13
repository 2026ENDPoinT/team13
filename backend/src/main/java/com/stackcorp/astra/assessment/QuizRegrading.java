package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.stackcorp.astra.assessment.AttemptRecords.Attempt;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 응시가 생긴 뒤의 정답·배점 정정과 재채점(ADR-0037). 문항 하나의 정답 보기와 배점만 바꾼다. 지문, 보기 문구, 보기 순서는 응시가 생기면
 * 바꾸지 않는다(ADR-0021 3번). 바꾼 정답으로 닫힌 응시를 모두 다시 채점하고, 응시 총점, 학생 성적, 성적 항목 배점을 같은 transaction에서
 * 바꾼다. 감사 기록은 명령 한 줄({@code quiz.regrade})과 다시 채점한 응시마다 한 줄({@code quiz.attempt.regrade})이다.
 * <p>
 * 잠금 순서: 퀴즈 행(수정 번호 비교) → 그 퀴즈의 응시 전체(기본 키 오름차순) → 성적 항목 행 → 문항·보기 → 답안 → 학생 성적. 응시를
 * 문항, 보기, 성적 항목보다 먼저 잠그는 이유는 제출과 답안 저장이 응시를 잠근 채 답안·성적 행을 쓰면서 FK 검사로 그 행들에 공유 잠금을
 * 잡기 때문이다. 응시 시작은 퀴즈 행을 공유 잠금하므로 재채점 중에는 새 응시가 생기지 않는다. 제출, worker 마감, 수동 채점은 한 학생의
 * 응시를 같은 오름차순으로 잠근 뒤 문항을 읽는다. 그래서 재채점과 겹친 제출은 둘 중 하나로 끝난다. 제출이 먼저 잠갔으면 재채점이 그
 * commit을 기다린 뒤 그 응시도 다시 채점하고, 재채점이 먼저 잠갔으면 제출이 그 commit 뒤에 바뀐 정답을 읽는다. 어느 쪽이든 결과는
 * 바뀐 정답의 점수다. 진행 중 응시는 다시 채점하지 않는다. 끝날 때 그때의 정답으로 채점한다.
 */
@Service
class QuizRegrading {

	private final QuizRecords quizzes;

	private final AttemptRecords attempts;

	private final GradebookRecords gradebook;

	private final QuizClock clock;

	private final AuditLog audit;

	private final TransactionTemplate readCommitted;

	QuizRegrading(QuizRecords quizzes, AttemptRecords attempts, GradebookRecords gradebook, QuizClock clock,
			AuditLog audit, PlatformTransactionManager transactionManager) {
		this.quizzes = quizzes;
		this.attempts = attempts;
		this.gradebook = gradebook;
		this.clock = clock;
		this.audit = audit;
		this.readCommitted = AttemptService.readCommitted(transactionManager);
	}

	/**
	 * @param revision 담당자가 읽은 퀴즈 수정 번호
	 * @param points 새 문항 배점(0 초과)
	 * @param correctOptionIds 선택 문항의 새 정답 보기. 단답형이면 비어 있다. 문항에 속하는지와 개수는 여기서 확인한다
	 * @return 다시 채점한 닫힌 응시 수
	 */
	int regrade(Actor actor, long courseId, Quiz quiz, long revision, long questionId, BigDecimal points,
			Set<Long> correctOptionIds) {
		return AttemptService.retrying(() -> this.readCommitted
			.execute((tx) -> regradeLocked(actor, courseId, quiz, revision, questionId, points, correctOptionIds)));
	}

	private int regradeLocked(Actor actor, long courseId, Quiz quiz, long revision, long questionId,
			BigDecimal points, Set<Long> correct) {
		if (!this.quizzes.bumpRevision(quiz.quizId(), revision)) {
			throw new ApiException(HttpStatus.CONFLICT, "edit_conflict", "다른 수정이 먼저 저장되었습니다. 다시 불러온 뒤 수정해 주세요.");
		}
		List<Question> questions = this.quizzes.questions(quiz.quizId());
		Question question = questions.stream()
			.filter((q) -> q.questionId() == questionId)
			.findFirst()
			.orElseThrow(ApiException::notFound);
		validateKey(question, correct);
		BigDecimal total = questions.stream()
			.map((q) -> q.questionId() == questionId ? points : q.points())
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		if (total.compareTo(GradebookService.MAX_SCORE) > 0) {
			throw ApiException.invalid("문항 배점의 합은 " + GradebookService.MAX_SCORE.toPlainString() + " 이하여야 합니다.");
		}
		List<Attempt> locked = this.attempts.lockQuiz(quiz.quizId());
		List<Long> closed = locked.stream().filter((a) -> !a.inProgress()).map(Attempt::attemptId).toList();
		long itemId = quiz.settings().gradeItemId();
		this.gradebook.lock(itemId);
		LocalDateTime now = this.clock.now();
		if (question.objective()) {
			List<Long> right = this.attempts.selections(questionId, closed)
				.entrySet()
				.stream()
				.filter((e) -> !e.getValue().isEmpty() && e.getValue().equals(correct))
				.map(Map.Entry::getKey)
				.toList();
			this.quizzes.updateAnswerKey(questionId, points, correct);
			this.attempts.rescoreChoices(questionId, closed, right, points, now);
		}
		else {
			if (this.attempts.awardedAbove(questionId, points)) {
				throw new ApiException(HttpStatus.CONFLICT, "awarded_exceeds_points",
						"이미 준 점수보다 문항 배점을 낮출 수 없습니다. 먼저 그 답안을 다시 채점해 주세요.");
			}
			this.quizzes.updateAnswerKey(questionId, points, Set.of());
		}
		this.attempts.retotal(locked.stream().filter((a) -> AttemptRecords.GRADED.equals(a.status()))
			.map(Attempt::attemptId)
			.toList());
		Map<Long, BigDecimal> current = this.attempts.grades(itemId);
		this.attempts.bestTotals(quiz.quizId()).forEach((userId, best) -> {
			BigDecimal before = current.get(userId);
			if (before == null || before.compareTo(best) != 0) {
				this.attempts.putGrade(itemId, userId, best, now, actor.userId());
			}
		});
		this.quizzes.updateMaxScore(itemId, total);
		this.audit.success(actor.userId(), actor.sessionId(), "quiz.regrade", "quiz", quiz.quizId(), courseId);
		for (long attemptId : closed) {
			this.audit.success(actor.userId(), actor.sessionId(), "quiz.attempt.regrade", "quiz_attempt", attemptId,
					courseId);
		}
		return closed.size();
	}

	/** 단일 선택은 정답 보기가 하나, 복수 선택은 하나 이상이고 모두 그 문항의 보기다. 단답형에는 정답 보기가 없다. */
	private static void validateKey(Question question, Set<Long> correct) {
		if (!question.objective()) {
			if (!correct.isEmpty()) {
				throw ApiException.invalid("단답형 문항에는 correctOptionIds를 보내지 않습니다.");
			}
			return;
		}
		if (correct.stream().anyMatch((id) -> Objects.isNull(id) || !question.hasOption(id))) {
			throw ApiException.invalid("correctOptionIds가 올바르지 않습니다.");
		}
		if (Question.SINGLE.equals(question.type()) ? correct.size() != 1 : correct.isEmpty()) {
			throw ApiException.invalid("단일 선택은 정답 보기가 하나, 복수 선택은 하나 이상이어야 합니다.");
		}
	}

}
