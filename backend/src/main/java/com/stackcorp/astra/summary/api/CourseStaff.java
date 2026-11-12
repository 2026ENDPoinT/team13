package com.stackcorp.astra.summary.api;

/**
 * 과목 담당 여부를 묻는 port. summary는 catalog에 의존하지 않으므로 integration 모듈이 과목 담당 관계로 구현한다.
 * 외부 LLM 처리 동의는 과목의 결정권자만 하고, 결정권자에서 빠진 사람의 동의는 효력을 잃는다(ADR-0032).
 */
public interface CourseStaff {

	/** 과목의 INSTRUCTOR다. 교수, 조교, 비정규 담당자, 교수·조교로 수강이 확정된 사람이다. */
	boolean isInstructor(long userId, long courseId);

	/**
	 * 과목의 결정권자다. 담당자 명단(course_instructors)에 교수나 비정규 담당자(MANAGER)로 올라 있고 과목이 폐강하지 않았다. 수강
	 * 신청을 처리하는 사람과 같다. 외부 LLM 처리 동의와 철회, 문의 책임 교수자 확인은 이것으로 한다. 조교와 수강만 확정된 교수·조교는
	 * 아니다.
	 */
	boolean isDecider(long userId, long courseId);

}
