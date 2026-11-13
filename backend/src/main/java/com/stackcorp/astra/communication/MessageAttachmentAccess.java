package com.stackcorp.astra.communication;

import com.stackcorp.astra.files.api.AttachmentParentAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.identity.api.Actor;

import org.springframework.stereotype.Component;

/**
 * 쪽지 첨부의 권한(ADR-0035). 쪽지를 아직 가진 사람만 읽는다. 발신자는 보낸함에서 지우기 전까지, 수신자는 회수되지 않았고 받은함에서 지우기
 * 전까지다. 쪽지 상세가 보이는 조건과 같아 회수하거나 지우면 그 사람의 파일 권한도 함께 사라진다. 과목 경로로는 열리지 않는다.
 */
@Component
class MessageAttachmentAccess implements AttachmentParentAccess {

	private final MessageRepository messages;

	MessageAttachmentAccess(MessageRepository messages) {
		this.messages = messages;
	}

	@Override
	public boolean allowed(long userId, long courseId, long attachmentGroupId, FileAction action) {
		return false;
	}

	@Override
	public boolean allowedOutsideCourse(Actor actor, long attachmentGroupId, FileAction action) {
		return action == FileAction.READ && this.messages.attachmentVisible(actor.userId(), attachmentGroupId);
	}

}
