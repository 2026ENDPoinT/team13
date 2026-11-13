package com.stackcorp.astra.learning;

import com.stackcorp.astra.files.api.AttachmentParentAccess;
import com.stackcorp.astra.files.api.FileAction;

import org.springframework.stereotype.Component;

/**
 * 학교 e-Class에서 받아 둔 파일(ADR-0043)의 권한. 과목 공용 파일은 그 과목의 구성원이면 읽을 수 있다(학교 강의실에서 같은 과목
 * 수강생이 함께 보는 파일이다). 학생 본인이 과제에 낸 파일(주인 있는 파일)은 그 학생만 읽는다. 아무도 관리할 수 없다(학교 목록에서
 * 사라질 때만 지운다).
 */
@Component
class SchoolFileAttachmentAccess implements AttachmentParentAccess {

	private final SchoolFileStore files;

	private final LearningAccess access;

	SchoolFileAttachmentAccess(SchoolFileStore files, LearningAccess access) {
		this.files = files;
		this.access = access;
	}

	@Override
	public boolean allowed(long userId, long courseId, long attachmentGroupId, FileAction action) {
		if (action != FileAction.READ) {
			return false;
		}
		return this.files.group(attachmentGroupId)
			.filter((group) -> group.courseId() == courseId && group.visibleTo(userId))
			.flatMap((group) -> this.access.roleIn(userId, group.courseId()))
			.isPresent();
	}

}
