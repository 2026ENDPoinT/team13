package com.stackcorp.astra.communication;

import com.stackcorp.astra.files.api.UploadPermission;
import com.stackcorp.astra.identity.api.Actor;

import org.springframework.stereotype.Component;

/**
 * communication이 가진 과목 밖 업로드 용도의 올리기 권한(ADR-0035). 알림마당 공지와 자료실 첨부는 알림마당 운영자만 올린다. 1:1 문의와
 * 쪽지 첨부는 로그인한 사용자 누구나 올리되, 첨부를 확정하는 명령(문의 작성·답변, 쪽지 보내기)이 부모의 권한을 다시 판정한다.
 * break-glass 계정은 어느 용도로도 올리지 못한다.
 */
@Component
class CommunityUploads implements UploadPermission {

	private final CommunityOperators operators;

	CommunityUploads(CommunityOperators operators) {
		this.operators = operators;
	}

	@Override
	public boolean allows(Actor actor, String purpose) {
		if (actor.breakGlass()) {
			return false;
		}
		return switch (purpose) {
			case BoardFiles.COMMUNITY_NOTICE, BoardFiles.COMMUNITY_MATERIAL -> this.operators.is(actor);
			case BoardFiles.INQUIRY, BoardFiles.MESSAGE -> true;
			default -> false;
		};
	}

}
