package com.stackcorp.astra.communication;

import com.stackcorp.astra.files.api.AttachmentParentAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.identity.api.Actor;

import org.springframework.stereotype.Component;

/**
 * 알림마당 공지·자료와 1:1 문의·답변에 붙은 파일의 권한(ADR-0035). 공지와 자료 파일은 그 글을 볼 수 있는 사람(대상 범위 안, 게시 뒤, 지우기
 * 전. 운영자는 예약 공지도)만, 문의 파일은 문의 작성자와 운영자만 읽는다. 과목 경로로는 열리지 않는다(과목 판정은 늘 거부). 파생물을 만들지
 * 않으므로 MANAGE는 누구에게도 주지 않는다.
 */
@Component
class CommunityAttachmentAccess implements AttachmentParentAccess {

	private final CommunityRepository community;

	private final CommunityOperators operators;

	CommunityAttachmentAccess(CommunityRepository community, CommunityOperators operators) {
		this.community = community;
		this.operators = operators;
	}

	@Override
	public boolean allowed(long userId, long courseId, long attachmentGroupId, FileAction action) {
		return false;
	}

	@Override
	public boolean allowedOutsideCourse(Actor actor, long attachmentGroupId, FileAction action) {
		if (action != FileAction.READ) {
			return false;
		}
		boolean operator = this.operators.is(actor);
		return this.community.attachmentVisible(attachmentGroupId, actor.userId(),
				CommunityService.audiences(actor, operator), operator);
	}

}
