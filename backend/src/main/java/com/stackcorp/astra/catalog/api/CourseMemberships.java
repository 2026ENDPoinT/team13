package com.stackcorp.astra.catalog.api;

import java.util.Optional;

/**
 * 과목 안에서 사용자가 맡은 역할. users.user_role이 아니라 담당·수강 관계로 정한다.
 */
public interface CourseMemberships {

	Optional<CourseRole> roleOf(long userId, long courseId);

	enum CourseRole {
		/** 교수, 조교, 비정규 과정 담당자 */
		INSTRUCTOR,
		/** 수강이 확정된 학생과 청강생 */
		STUDENT
	}

}
