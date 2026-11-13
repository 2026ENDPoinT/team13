package com.stackcorp.astra.files.api;

import com.stackcorp.astra.identity.api.Actor;

/**
 * 첨부 그룹을 가진 부모 모듈이 구현하는 port(ADR-0001 4번). files는 부모 테이블을 읽지 않는다.
 * 자기가 모르는 첨부 그룹이면 false를 돌려준다.
 */
public interface AttachmentParentAccess {

	boolean allowed(long userId, long courseId, long attachmentGroupId, FileAction action);

	/**
	 * 과목이 없는 부모(알림마당 자료·1:1 문의, 쪽지)의 판정. 과목 경로가 없는 내려받기({@code GET /api/files/{fileId}/content})만
	 * 부른다. 과목에 속한 부모는 구현하지 않으므로 과목 파일은 이 경로로 열리지 않는다. 기본은 거부다.
	 */
	default boolean allowedOutsideCourse(Actor actor, long attachmentGroupId, FileAction action) {
		return false;
	}

}
