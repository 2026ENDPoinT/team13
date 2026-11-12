package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.stackcorp.astra.assessment.AttemptRecords.Answer;
import com.stackcorp.astra.assessment.AttemptRecords.Attempt;
import com.stackcorp.astra.assessment.Quiz.Settings;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 응시 종료, 채점, 성적 반영(SC-05, CHG-04, ADR-0021). 학생 제출, 응시 시작 때 지난 응시 정리, worker의 마감 처리, 담당자 채점이 모두
 * 이 클래스를 거친다. 부르는 쪽은 transaction 안에서 그 학생의 응시를 {@link AttemptRecords#lockAll}로 먼저 잠근다.
 * <ul>
 * <li>종료: 마지막으로 저장한 답안으로 닫는다. 답이 없는 문항도 답안 행을 만들어 0점을 준다. 닫은 뒤에는 답안을 바꾸지 않는다.</li>
 * <li>자동 채점: 선택 문항은 고른 보기 집합이 정답 보기 집합과 같을 때만 배점을 준다. 단답형은 답이 비었으면 0점, 아니면 수동 채점
 * 대기다.</li>
 * <li>성적: 채점을 마친 응시 가운데 가장 높은 총점을 그 퀴즈 성적 항목의 점수로 같은 transaction에서 쓴다.</li>
 * </ul>
 */
@Component
class AttemptScoring {

	static final String REASON_SUBMITTED = "submitted";

	static final String REASON_DEADLINE = "deadline";

	private final QuizRecords quizzes;

	private final AttemptRecords attempts;

	AttemptScoring(QuizRecords quizzes, AttemptRecords attempts) {
		this.quizzes = quizzes;
		this.attempts = attempts;
	}

	/**
	 * 진행 중 응시를 닫는다. DB 시계로 마감 전이면 학생 제출이고 접수 시각은 now다. 마감 뒤면 마감 일시에 닫은 것이고 접수 시각은 마감
	 * 일시다. 마감 뒤의 저장은 받지 않았으므로 마감 시점의 답안으로 확정된다.
	 *
	 * @param locked {@link AttemptRecords#lockAll}로 잠근 그 학생의 응시 전체
	 * @return 닫은 응시
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Attempt finish(Settings quiz, List<Attempt> locked, Attempt target, LocalDateTime now) {
		if (!target.inProgress()) {
			throw new IllegalStateException("진행 중이 아닌 응시를 닫으려 했습니다: " + target.attemptId());
		}
		boolean beforeDeadline = now.isBefore(target.deadlineAt());
		String reason = beforeDeadline ? REASON_SUBMITTED : REASON_DEADLINE;
		LocalDateTime submittedAt = beforeDeadline ? now : target.deadlineAt();
		Map<Long, Answer> answers = this.attempts.answers(target.attemptId());
		BigDecimal total = BigDecimal.ZERO;
		int pending = 0;
		for (Question question : this.quizzes.questions(quiz.quizId())) {
			Answer answer = answers.get(question.questionId());
			long answerId = answer != null ? answer.answerId()
					: this.attempts.insertAnswer(target.attemptId(), question.questionId(), null, 0, null);
			if (question.objective()) {
				boolean correct = answer != null && question.isCorrect(new HashSet<>(answer.optionIds()));
				BigDecimal awarded = correct ? question.points() : BigDecimal.ZERO;
				this.attempts.scoreAnswer(answerId, correct, awarded, "auto", null, now, null);
				total = total.add(awarded);
			}
			else if (answer == null || answer.text() == null || answer.text().isBlank()) {
				this.attempts.scoreAnswer(answerId, null, BigDecimal.ZERO, "auto", null, now, null);
			}
			else {
				this.attempts.scoreAnswer(answerId, null, null, "pending", null, null, null);
				pending++;
			}
		}
		boolean graded = pending == 0;
		if (!this.attempts.finish(target.attemptId(), graded ? AttemptRecords.GRADED : AttemptRecords.SUBMITTED,
				submittedAt, reason, graded ? total : null, graded ? now : null)) {
			throw new IllegalStateException("잠근 응시의 상태가 바뀌었습니다: " + target.attemptId());
		}
		Attempt finished = this.attempts.find(target.attemptId()).orElseThrow();
		if (graded) {
			putBest(quiz, locked, finished, now, null);
		}
		return finished;
	}

	/**
	 * 담당자가 단답형 답안 하나에 점수와 의견을 준다. 대기 답안이 남지 않으면 응시 총점을 확정하고 성적을 다시 쓴다. 이미 채점을 마친
	 * 응시의 점수를 고쳐도 같다.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	Attempt gradeAnswer(Settings quiz, List<Attempt> locked, Attempt target, Answer answer, BigDecimal score,
			String comment, long graderId, LocalDateTime now) {
		this.attempts.scoreAnswer(answer.answerId(), null, score, "manual", comment, now, graderId);
		Map<Long, Answer> answers = this.attempts.answers(target.attemptId());
		if (answers.values().stream().anyMatch((a) -> "pending".equals(a.gradingStatus()))) {
			return this.attempts.find(target.attemptId()).orElseThrow();
		}
		BigDecimal total = answers.values()
			.stream()
			.map(Answer::awarded)
			.filter(Objects::nonNull)
			.reduce(BigDecimal.ZERO, BigDecimal::add);
		this.attempts.grade(target.attemptId(), total, now);
		Attempt graded = this.attempts.find(target.attemptId()).orElseThrow();
		putBest(quiz, locked, graded, now, graderId);
		return graded;
	}

	private void putBest(Settings quiz, List<Attempt> locked, Attempt changed, LocalDateTime now, Long graderId) {
		BigDecimal best = locked.stream()
			.map((a) -> a.attemptId() == changed.attemptId() ? changed : a)
			.filter((a) -> AttemptRecords.GRADED.equals(a.status()) && a.totalScore() != null)
			.map(Attempt::totalScore)
			.reduce(changed.totalScore(), BigDecimal::max);
		this.attempts.putGrade(quiz.gradeItemId(), changed.userId(), best, now, graderId);
	}

}
