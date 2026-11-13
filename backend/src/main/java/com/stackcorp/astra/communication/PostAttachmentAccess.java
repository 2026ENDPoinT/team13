package com.stackcorp.astra.communication;

import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.files.api.AttachmentParentAccess;
import com.stackcorp.astra.files.api.FileAction;

import org.springframework.stereotype.Component;

/**
 * 질의응답·열린게시판 글과 답글에 붙은 파일의 권한(ADR-0024). 파일은 글을 볼 수 있는 사람만 읽는다. 비공개 질문과 그 답글의 파일은 질문
 * 작성자와 담당자만 읽고, 지운 글이나 지운 질문의 답글에 붙은 파일은 아무도 읽지 못한다. 게시글 파일로는 요약 같은 파생물을 만들지 않으므로
 * MANAGE는 누구에게도 주지 않는다.
 */
@Component
class PostAttachmentAccess implements AttachmentParentAccess {

	private final BoardPostRepository posts;

	private final CourseMemberships memberships;

	PostAttachmentAccess(BoardPostRepository posts, CourseMemberships memberships) {
		this.posts = posts;
		this.memberships = memberships;
	}

	@Override
	public boolean allowed(long userId, long courseId, long attachmentGroupId, FileAction action) {
		if (action != FileAction.READ) {
			return false;
		}
		Optional<CourseRole> role = this.memberships.roleOf(userId, courseId);
		return role.isPresent()
				&& this.posts.attachmentVisible(courseId, attachmentGroupId, userId, role.get() == CourseRole.INSTRUCTOR);
	}

}
