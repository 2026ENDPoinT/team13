package com.stackcorp.astra.assessment;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.api.ReportActivities;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 과제 등록과 수정. 활동(learning), 과제 설정, 성적 항목, 수강생 알림(catalog)을 한 transaction으로 저장한다(SC-05, ADR-0027).
 * 권한은 부르는 쪽이 먼저 판정한다.
 * <p>
 * 감사 기록(SC-09): 등록은 {@code assignment.create}, 수정은 {@code assignment.update}다. 점수 공개가 바뀌면 성적부의 공개 명령과 같은
 * 이름({@code grade_item.release}, {@code grade_item.withhold})으로 성적 항목을 대상으로 한 줄을 더 남긴다. 제목과 설명은 남기지 않는다.
 */
@Service
class AssignmentService {

	private final ReportActivities activities;

	private final AssignmentRecords records;

	private final GradebookRecords gradebook;

	private final AssignmentNotifications alerts;

	private final AuditLog audit;

	AssignmentService(ReportActivities activities, AssignmentRecords records, GradebookRecords gradebook,
			AssignmentNotifications alerts, AuditLog audit) {
		this.activities = activities;
		this.records = records;
		this.gradebook = gradebook;
		this.alerts = alerts;
		this.audit = audit;
	}

	/** @return 과제 ID */
	@Transactional
	long create(Actor actor, long courseId, AssignmentInput input) {
		long activityId = this.activities.create(courseId, actor.userId(), input.activity());
		long assignmentId = this.records.insert(activityId, input);
		long gradeItemId = this.records.insertGradeItem(courseId, activityId, actor.userId(), input.activity().title(),
				input);
		this.alerts.sync(courseId, activityId, input);
		this.audit.success(actor.userId(), actor.sessionId(), "assignment.create", "assignment", assignmentId, courseId);
		if (input.scoresReleased()) {
			this.audit.success(actor.userId(), actor.sessionId(), "grade_item.release", "grade_item", gradeItemId,
					courseId);
		}
		return assignmentId;
	}

	/**
	 * 읽은 수정 번호가 지금 값과 같을 때만 바꾼다. 다르면 다른 수정을 덮어쓰지 않도록 409로 거부한다. 배점 비교 전에 성적 항목 행을 잠가
	 * 그 사이에 더 높은 점수가 들어오지 못하게 한다(점수 쓰기도 같은 행을 먼저 잠근다). 공개 여부가 바뀌었는지는 잠근 항목 행으로 정한다.
	 */
	@Transactional
	void update(Actor actor, long courseId, Assignment current, long revision, AssignmentInput input) {
		if (!this.records.update(current.assignmentId(), revision, input)) {
			throw new ApiException(HttpStatus.CONFLICT, "edit_conflict", "다른 수정이 먼저 저장되었습니다. 다시 불러온 뒤 수정해 주세요.");
		}
		if (!this.activities.update(courseId, current.activity().activityId(), input.activity())) {
			throw ApiException.notFound();
		}
		long gradeItemId = current.settings().gradeItemId();
		boolean wasReleased = !"hidden".equals(this.gradebook.lock(gradeItemId).scoreVisibility());
		if (this.gradebook.hasScoreAbove(gradeItemId, input.maxScore())) {
			throw new ApiException(HttpStatus.CONFLICT, "score_exceeds_max", "이미 준 점수보다 배점을 낮출 수 없습니다.");
		}
		this.records.updateGradeItem(gradeItemId, input.activity().title(), input);
		this.alerts.sync(courseId, current.activity().activityId(), input);
		this.audit.success(actor.userId(), actor.sessionId(), "assignment.update", "assignment", current.assignmentId(),
				courseId);
		if (wasReleased != input.scoresReleased()) {
			this.audit.success(actor.userId(), actor.sessionId(),
					input.scoresReleased() ? "grade_item.release" : "grade_item.withhold", "grade_item", gradeItemId,
					courseId);
		}
	}

}
