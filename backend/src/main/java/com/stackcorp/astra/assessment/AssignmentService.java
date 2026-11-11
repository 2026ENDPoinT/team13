package com.stackcorp.astra.assessment;

import com.stackcorp.astra.learning.api.ReportActivities;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 과제 등록과 수정. 활동(learning), 과제 설정, 성적 항목을 한 transaction으로 저장한다(SC-05). 권한은 부르는 쪽이 먼저 판정한다.
 */
@Service
class AssignmentService {

	private final ReportActivities activities;

	private final AssignmentRecords records;

	private final GradebookRecords gradebook;

	AssignmentService(ReportActivities activities, AssignmentRecords records, GradebookRecords gradebook) {
		this.activities = activities;
		this.records = records;
		this.gradebook = gradebook;
	}

	/** @return 과제 ID */
	@Transactional
	long create(long userId, long courseId, AssignmentInput input) {
		long activityId = this.activities.create(courseId, userId, input.activity());
		long assignmentId = this.records.insert(activityId, input);
		this.records.insertGradeItem(courseId, activityId, userId, input.activity().title(), input);
		return assignmentId;
	}

	/**
	 * 읽은 수정 번호가 지금 값과 같을 때만 바꾼다. 다르면 다른 수정을 덮어쓰지 않도록 409로 거부한다. 배점 비교 전에 성적 항목 행을 잠가
	 * 그 사이에 더 높은 점수가 들어오지 못하게 한다(점수 쓰기도 같은 행을 먼저 잠근다).
	 */
	@Transactional
	void update(long courseId, Assignment current, long revision, AssignmentInput input) {
		if (!this.records.update(current.assignmentId(), revision, input)) {
			throw new ApiException(HttpStatus.CONFLICT, "edit_conflict", "다른 수정이 먼저 저장되었습니다. 다시 불러온 뒤 수정해 주세요.");
		}
		if (!this.activities.update(courseId, current.activity().activityId(), input.activity())) {
			throw ApiException.notFound();
		}
		this.gradebook.lock(current.settings().gradeItemId());
		if (this.gradebook.hasScoreAbove(current.settings().gradeItemId(), input.maxScore())) {
			throw new ApiException(HttpStatus.CONFLICT, "score_exceeds_max", "이미 준 점수보다 배점을 낮출 수 없습니다.");
		}
		this.records.updateGradeItem(current.settings().gradeItemId(), input.activity().title(), input);
	}

}
