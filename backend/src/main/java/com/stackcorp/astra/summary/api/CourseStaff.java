package com.stackcorp.astra.summary.api;

/**
 * 과목 담당 여부를 묻는 port. summary는 catalog에 의존하지 않으므로 integration 모듈이 과목 담당 관계로 구현한다.
 * 외부 LLM 처리 동의는 과목 담당 교수자만 하고, 담당에서 빠진 교수자의 동의는 효력을 잃는다(ADR-0032).
 */
public interface CourseStaff {

	boolean isInstructor(long userId, long courseId);

}
