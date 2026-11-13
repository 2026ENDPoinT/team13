package com.stackcorp.astra.integration.membership;

import org.springframework.stereotype.Component;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.summary.api.CourseStaff;

/**
 * summary의 과목 담당 확인 port를 catalog의 담당·수강 관계로 구현한다. summary는 catalog에 의존하지 않는다(architecture.md 4.1).
 */
@Component
class CourseStaffMemberships implements CourseStaff {

	private final CourseMemberships memberships;

	CourseStaffMemberships(CourseMemberships memberships) {
		this.memberships = memberships;
	}

	@Override
	public boolean isInstructor(long userId, long courseId) {
		return this.memberships.roleOf(userId, courseId).filter(CourseRole.INSTRUCTOR::equals).isPresent();
	}

	@Override
	public boolean isDecider(long userId, long courseId) {
		return this.memberships.isDecider(userId, courseId);
	}

}
