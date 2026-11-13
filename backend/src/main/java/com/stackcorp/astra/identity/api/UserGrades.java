package com.stackcorp.astra.identity.api;

import java.util.OptionalInt;

/**
 * 학생의 학년(users.grade). 알림마당 설문의 학년 대상 판정처럼 학사 속성으로 대상을 고를 때만 쓴다. 권한 판정은 하지 않는다.
 */
public interface UserGrades {

	/** 학년이 없거나(학생이 아니거나 동기화 전) 사용자가 없으면 비어 있다. */
	OptionalInt gradeOf(long userId);

}
