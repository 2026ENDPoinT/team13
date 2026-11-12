package com.stackcorp.astra.files.api;

/**
 * 첨부 그룹을 가진 부모 모듈이 구현하는 port(ADR-0001 4번). files는 부모 테이블을 읽지 않는다.
 * 자기가 모르는 첨부 그룹이면 false를 돌려준다.
 */
public interface AttachmentParentAccess {

	boolean allowed(long userId, long courseId, long attachmentGroupId, FileAction action);

}
