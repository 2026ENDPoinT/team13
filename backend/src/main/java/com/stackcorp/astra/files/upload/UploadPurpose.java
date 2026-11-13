package com.stackcorp.astra.files.upload;

import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;

/**
 * 업로드 용도. 값은 upload_policies.policy_code다. 과목 용도는 그 과목에서 필요한 역할이 정해져 있고, 과목 밖 용도는 부모를 가진 모듈의
 * {@link com.stackcorp.astra.files.api.UploadPermission}이 판정한다(ADR-0035). client가 보낸 값은 이 목록에 있을 때만
 * 받는다(FILES-11).
 */
enum UploadPurpose {

	LECTURE_MATERIAL("lecture_material", true, CourseRole.INSTRUCTOR),

	/** 과제 제출 첨부. 그 과목의 수강생만 올린다. */
	ASSIGNMENT_SUBMISSION("assignment_submission", true, CourseRole.STUDENT),

	/** 게시글(질의응답, 열린게시판) 첨부. 그 과목의 담당자와 수강생이 올린다. */
	POST_ATTACHMENT("post_attachment", true, null),

	/** 강의 영상. 본문을 흘려 보내는 업로드(POST /api/uploads/stream)로만 받는다(ADR-0016). */
	LECTURE_VIDEO("lecture_video", true, CourseRole.INSTRUCTOR),

	/** 알림마당 공지 첨부. 과목이 없다. 알림마당 운영자만 올린다(communication). */
	COMMUNITY_NOTICE("community_notice", false, null),

	/** 알림마당 자료실 첨부. 과목이 없다. 알림마당 운영자만 올린다(communication). */
	COMMUNITY_MATERIAL("community_material", false, null),

	/** 1:1 문의와 그 답변의 첨부. 과목이 없다. 판정은 communication이 한다. */
	INQUIRY_ATTACHMENT("inquiry_attachment", false, null),

	/** 쪽지 첨부. 과목이 없다. 판정은 communication이 한다. */
	MESSAGE_ATTACHMENT("message_attachment", false, null);

	private final String code;

	private final boolean courseScoped;

	/** 과목 용도에서 null이면 과목 구성원 누구나 */
	private final CourseRole requiredRole;

	UploadPurpose(String code, boolean courseScoped, CourseRole requiredRole) {
		this.code = code;
		this.courseScoped = courseScoped;
		this.requiredRole = requiredRole;
	}

	String code() {
		return this.code;
	}

	/** 과목에 속한 용도인지. 과목 밖 용도의 업로드와 파일은 course_id가 NULL이다. */
	boolean courseScoped() {
		return this.courseScoped;
	}

	/** 그 과목에서 이 역할이면 이 용도로 올릴 수 있는지. 과목 밖 용도는 언제나 false다. */
	boolean allows(CourseRole role) {
		return this.courseScoped && (this.requiredRole == null || this.requiredRole == role);
	}

	/** multipart가 아니라 본문 스트림으로 받는 용도인지. */
	boolean streamed() {
		return this == LECTURE_VIDEO;
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
