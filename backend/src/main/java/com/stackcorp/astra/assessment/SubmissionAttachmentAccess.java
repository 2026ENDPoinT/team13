package com.stackcorp.astra.assessment;

import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.assessment.SubmissionRecords.GroupOwner;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.files.api.AttachmentParentAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.learning.api.ReportActivities;

import org.springframework.stereotype.Component;

/**
 * 과제 제출 첨부의 권한(CHG-06). 제출한 학생 본인과 그 과목 담당자만 읽는다. 같은 과목의 다른 학생은 읽지 못한다(SC-02).
 * 제출물 파일로는 요약 같은 파생물을 만들지 않으므로 MANAGE는 누구에게도 주지 않는다.
 */
@Component
class SubmissionAttachmentAccess implements AttachmentParentAccess {

	private final SubmissionRecords records;

	private final CourseMemberships memberships;

	private final ReportActivities activities;

	SubmissionAttachmentAccess(SubmissionRecords records, CourseMemberships memberships, ReportActivities activities) {
		this.records = records;
		this.memberships = memberships;
		this.activities = activities;
	}

	@Override
	public boolean allowed(long userId, long courseId, long attachmentGroupId, FileAction action) {
		if (action != FileAction.READ) {
			return false;
		}
		List<GroupOwner> owners = this.records.ownersOfGroup(attachmentGroupId);
		if (owners.isEmpty()) {
			return false;
		}
		Optional<CourseRole> role = this.memberships.roleOf(userId, courseId);
		if (role.isEmpty()) {
			return false;
		}
		return owners.stream()
			.filter((owner) -> this.activities.find(courseId, owner.activityId()).isPresent())
			.anyMatch((owner) -> role.get() == CourseRole.INSTRUCTOR || owner.userId() == userId);
	}

}
