package com.stackcorp.astra.catalog.api;

import java.util.Optional;

/**
 * 과목 안에서 사용자가 맡은 역할. users.user_role이 아니라 담당·수강 관계로 정한다. 폐강(CANCELLED)한 과목에서는 누구도 역할이
 * 없으므로 그 과목의 조회와 명령은 모두 404다. 명단({@link CourseRosters}), 함께 듣는 사람({@link CourseMates}), 내
 * 과목({@link MemberCourses})도 같은 규칙이다.
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
