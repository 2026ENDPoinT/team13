package com.stackcorp.astra.learning;

import com.stackcorp.astra.files.api.AttachmentParentAccess;
import com.stackcorp.astra.files.api.FileAction;

import org.springframework.stereotype.Component;

/**
 * 학교 e-Class에서 받아 둔 파일(ADR-0043)의 권한. 그 과목의 구성원이면 읽을 수 있다(학교 강의실에서 같은 과목 수강생이 함께 보는
 * 파일이다). 아무도 관리할 수 없다(학교 목록에서 사라질 때만 지운다).
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
		return this.files.courseOfGroup(attachmentGroupId)
			.filter((course) -> course == courseId)
			.flatMap((course) -> this.access.roleIn(userId, course))
			.isPresent();
	}

}
