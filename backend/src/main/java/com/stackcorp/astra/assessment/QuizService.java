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

	private final GradebookRecords gradebook;

	private final AuditLog audit;

	QuizService(QuizActivities activities, QuizRecords records, GradebookRecords gradebook, AuditLog audit) {
		this.activities = activities;
		this.records = records;
		this.gradebook = gradebook;
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
	 * 바뀌지 않고, 제한 시간과 응시 횟수는 다음 시작부터 적용한다. 배점 비교 전에 성적 항목 행을 잠근다. 오프라인 시험의 점수 입력도 같은
	 * 행을 먼저 잠그므로, 배점을 낮추는 수정과 더 높은 점수 쓰기가 겹쳐도 점수가 배점을 넘지 않는다(ADR-0023 6번).
	 */
	@Transactional
	void update(Actor actor, long courseId, Quiz current, long revision, QuizInput input) {
		if (!this.records.updateSettings(current.quizId(), revision, input)) {
			throw editConflict();
		}
		BigDecimal maxScore = current.online() ? current.settings().maxScore() : input.maxScore();
		this.gradebook.lock(current.settings().gradeItemId());
		// 잠금을 얻은 뒤의 첫 일반 읽기라 먼저 commit한 점수가 보인다.
		if (this.records.hasScoreAbove(current.settings().gradeItemId(), maxScore)) {
			throw new ApiException(HttpStatus.CONFLICT, "score_exceeds_max", "이미 준 점수보다 배점을 낮출 수 없습니다.");
		}
		if (!this.activities.update(courseId, current.activity().activityId(), input.activity())) {
			throw ApiException.notFound();
		}
		this.records.updateGradeItem(current.settings().gradeItemId(), input.activity().title(), maxScore,
				input.resultReleaseAt());
		this.audit.success(actor.userId(), actor.sessionId(), "quiz.update", "quiz", current.quizId(), courseId);
	}

	/**
	 * 퀴즈를 지운다(ADR-0011, ADR-0037). 활동에 삭제 표시만 남기고 퀴즈 설정, 문항, 응시, 답안, 성적 항목과 점수는 지우지 않는다. 그
	 * commit부터 담당자와 학생 모두에게 퀴즈, 응시, 성적 항목이 없고, 성적부와 학생 합계, 할 일, 캘린더에서 빠진다. 수정 번호를 먼저 올려
	 * 퀴즈 행을 배타 잠금하므로 진행 중인 응시 시작과 겹치지 않는다. 진행 중 응시는 더는 저장과 제출을 받지 않고, worker가 고정된 마감
	 * 일시에 마지막 저장 답안으로 닫는다. 퀴즈는 알림을 만들지 않으므로 거둘 알림이 없다.
	 */
	@Transactional
	void delete(Actor actor, long courseId, Quiz current, long revision) {
		if (!this.records.bumpRevision(current.quizId(), revision)) {
			throw editConflict();
		}
		if (!this.activities.delete(courseId, current.activity().activityId(), actor.userId())) {
			throw ApiException.notFound();
		}
		this.audit.success(actor.userId(), actor.sessionId(), "quiz.delete", "quiz", current.quizId(), courseId);
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
