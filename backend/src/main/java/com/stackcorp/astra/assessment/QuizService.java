package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.util.List;

import com.stackcorp.astra.assessment.QuizInput.QuestionInput;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.api.QuizActivities;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 등록과 수정. 활동(learning), 퀴즈 설정, 문항, 성적 항목을 한 transaction으로 저장한다(SC-05). 권한은 부르는 쪽이 먼저 판정한다.
 * <p>
 * 응시가 하나라도 생기면 문항과 보기는 바꾸지 않는다(ADR-0021). 응시 시작은 퀴즈 행에 공유 잠금을 걸고 문항 수정은 배타 잠금을
 * 건 뒤 응시 유무를 보므로, 첫 응시와 문항 수정이 겹쳐도 응시가 바뀐 문항을 보지 않는다.
 */
@Service
class QuizService {

	private final QuizActivities activities;

	private final QuizRecords records;

	private final AuditLog audit;

	QuizService(QuizActivities activities, QuizRecords records, AuditLog audit) {
		this.activities = activities;
		this.records = records;
		this.audit = audit;
	}

	/**
	 * @param questions 온라인 시험의 문항. 오프라인 시험이면 비어 있다
	 * @return 퀴즈 ID
	 */
	@Transactional
	long create(Actor actor, long courseId, String examType, QuizInput input, List<QuestionInput> questions) {
		long activityId = this.activities.create(courseId, actor.userId(), input.activity());
		long quizId = this.records.insert(activityId, examType, input);
		this.records.replaceQuestions(quizId, questions);
		BigDecimal maxScore = Quiz.ONLINE.equals(examType) ? total(questions) : input.maxScore();
		this.records.insertGradeItem(courseId, activityId, actor.userId(), input.activity().title(), maxScore,
				input.resultReleaseAt());
		this.audit.success(actor.userId(), actor.sessionId(), "quiz.create", "quiz", quizId, courseId);
		return quizId;
	}

	/**
	 * 설정 수정. 읽은 수정 번호가 지금 값과 같을 때만 바꾼다. 응시가 있어도 바꿀 수 있다. 진행 중 응시의 마감 일시는 시작할 때 고정했으므로
	 * 바뀌지 않고, 제한 시간과 응시 횟수는 다음 시작부터 적용한다.
	 */
	@Transactional
	void update(Actor actor, long courseId, Quiz current, long revision, QuizInput input) {
		if (!this.records.updateSettings(current.quizId(), revision, input)) {
			throw editConflict();
		}
		if (!this.activities.update(courseId, current.activity().activityId(), input.activity())) {
			throw ApiException.notFound();
		}
		BigDecimal maxScore = current.online() ? current.settings().maxScore() : input.maxScore();
		if (this.records.hasScoreAbove(current.settings().gradeItemId(), maxScore)) {
			throw new ApiException(HttpStatus.CONFLICT, "score_exceeds_max", "이미 준 점수보다 배점을 낮출 수 없습니다.");
		}
		this.records.updateGradeItem(current.settings().gradeItemId(), input.activity().title(), maxScore,
				input.resultReleaseAt());
		this.audit.success(actor.userId(), actor.sessionId(), "quiz.update", "quiz", current.quizId(), courseId);
	}

	/**
	 * 문항 전체를 바꾼다. 수정 번호를 먼저 올려 퀴즈 행을 배타 잠금한 뒤 응시 유무를 본다. 응시가 있으면 409다. 배점은 문항 배점의 합으로
	 * 다시 정한다.
	 */
	@Transactional
	void replaceQuestions(Actor actor, long courseId, Quiz current, long revision, List<QuestionInput> questions) {
		if (!this.records.bumpRevision(current.quizId(), revision)) {
			throw editConflict();
		}
		// 잠금을 얻은 뒤의 첫 일반 읽기라 먼저 commit한 응시가 보인다.
		if (this.records.hasAttempts(current.quizId())) {
			throw new ApiException(HttpStatus.CONFLICT, "questions_locked", "응시가 시작된 퀴즈의 문항은 바꿀 수 없습니다.");
		}
		this.records.replaceQuestions(current.quizId(), questions);
		this.records.updateMaxScore(current.settings().gradeItemId(), total(questions));
		this.audit.success(actor.userId(), actor.sessionId(), "quiz.questions.update", "quiz", current.quizId(), courseId);
	}

	private static BigDecimal total(List<QuestionInput> questions) {
		return questions.stream().map(QuestionInput::points).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static ApiException editConflict() {
		return new ApiException(HttpStatus.CONFLICT, "edit_conflict", "다른 수정이 먼저 저장되었습니다. 다시 불러온 뒤 수정해 주세요.");
	}

}
