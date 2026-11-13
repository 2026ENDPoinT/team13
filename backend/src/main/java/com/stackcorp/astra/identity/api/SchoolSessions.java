package com.stackcorp.astra.identity.api;

import java.util.Optional;

/**
 * STack session마다 붙여 둔 학교 e-Class session({@link SchoolCourseRooms}). 학번·학교 비밀번호로 로그인한 session에만 있다.
 * <ul>
 * <li>학교 session 쿠키는 이 서버 process의 메모리에만 있다. 저장하지 않고 로그에 남기지 않는다. 서버를 다시 띄우면 없어진다.</li>
 * <li>STack session이 끝나면(로그아웃, 폐기, 유휴·절대 만료) 닫고 버린다.</li>
 * <li>학교가 그 session을 끝냈으면({@link SchoolLoginException.Kind#EXPIRED}) 부른 쪽이 {@link #forget}으로 버리고 학생에게 다시
 * 로그인하라고 답한다.</li>
 * </ul>
 */
public interface SchoolSessions {

	/** @return 이 STack session의 학교 session. 없거나 이미 버렸으면 비어 있다 */
	Optional<SchoolCourseRooms> of(long sessionId);

	/** 이 STack session의 학교 session을 닫고 버린다. 없으면 아무 일도 하지 않는다. */
	void forget(long sessionId);

}
