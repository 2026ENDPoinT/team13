package com.stackcorp.astra.files.api;

import com.stackcorp.astra.identity.api.Actor;

/**
 * 파일 권한. 파일 자체는 권한을 갖지 않고 그 파일을 첨부한 부모 객체의 권한을 따른다(CHG-06).
 * 부모는 {@link AttachmentParentAccess}를 구현한 모듈이 판정한다. 기본은 거부다.
 */
public interface FileAccess {

	/**
	 * @param courseId 요청이 지정한 과목. 파일의 부모가 이 과목에 속하지 않으면 거부한다(SC-03)
	 */
	boolean allowed(long userId, long courseId, long fileId, FileAction action);

	/**
	 * 과목 밖 파일(files.course_id가 NULL)의 권한. 부모의 {@link AttachmentParentAccess#allowedOutsideCourse}가 판정한다.
	 * 과목 파일은 언제나 거부한다.
	 */
	boolean allowedOutsideCourse(Actor actor, long fileId, FileAction action);

}
