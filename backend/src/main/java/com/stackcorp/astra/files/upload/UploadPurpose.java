package com.stackcorp.astra.files.upload;

import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;

/**
 * 업로드 용도. 값은 upload_policies.policy_code다. 용도마다 그 과목에서 필요한 역할이 정해져 있다.
 * client가 보낸 값은 이 목록에 있을 때만 받는다(FILES-11).
 */
enum UploadPurpose {

	LECTURE_MATERIAL("lecture_material", CourseRole.INSTRUCTOR),

	/** 과제 제출 첨부. 그 과목의 수강생만 올린다. */
	ASSIGNMENT_SUBMISSION("assignment_submission", CourseRole.STUDENT),

	/** 게시글(질의응답, 열린게시판) 첨부. 그 과목의 담당자와 수강생이 올린다. */
	POST_ATTACHMENT("post_attachment", null);

	private final String code;

	/** null이면 과목 구성원 누구나 */
	private final CourseRole requiredRole;

	UploadPurpose(String code, CourseRole requiredRole) {
		this.code = code;
		this.requiredRole = requiredRole;
	}

	String code() {
		return this.code;
	}

	/** 그 과목에서 이 역할이면 이 용도로 올릴 수 있는지. */
	boolean allows(CourseRole role) {
		return this.requiredRole == null || this.requiredRole == role;
	}

	static Optional<UploadPurpose> of(String code) {
		for (UploadPurpose purpose : values()) {
			if (purpose.code.equals(code)) {
				return Optional.of(purpose);
			}
		}
		return Optional.empty();
	}

}
