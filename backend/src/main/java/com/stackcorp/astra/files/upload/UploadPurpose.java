package com.stackcorp.astra.files.upload;

import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;

/**
 * 업로드 용도. 값은 upload_policies.policy_code다. 용도마다 그 과목에서 필요한 역할이 정해져 있다.
 * client가 보낸 값은 이 목록에 있을 때만 받는다(FILES-11).
 */
enum UploadPurpose {

	LECTURE_MATERIAL("lecture_material", CourseRole.INSTRUCTOR);

	private final String code;

	private final CourseRole requiredRole;

	UploadPurpose(String code, CourseRole requiredRole) {
		this.code = code;
		this.requiredRole = requiredRole;
	}

	String code() {
		return this.code;
	}

	CourseRole requiredRole() {
		return this.requiredRole;
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
