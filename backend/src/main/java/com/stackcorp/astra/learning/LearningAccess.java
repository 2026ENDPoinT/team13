package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;

/**
 * 학습활동 권한. 담당자는 언제나, 학생은 공개됐고 시작 일시가 지난 활동만 본다. 시각은 서버 시계로 정한다(SC-06).
 * 활동이 요청한 과목에 속하지 않으면 거부한다(SC-03).
 */
@Component
class LearningAccess {

	private final JdbcClient jdbc;

	private final CourseMemberships memberships;

	LearningAccess(JdbcClient jdbc, CourseMemberships memberships) {
		this.jdbc = jdbc;
		this.memberships = memberships;
	}

	boolean canView(long userId, Activity activity) {
		Optional<CourseRole> role = this.memberships.roleOf(userId, activity.courseId());
		if (role.isEmpty()) {
			return false;
		}
		if (role.get() == CourseRole.INSTRUCTOR) {
			return true;
		}
		LocalDateTime now = this.jdbc.sql("SELECT CURRENT_TIMESTAMP").query(LocalDateTime.class).single();
		return activity.published() && (activity.openAt() == null || !activity.openAt().isAfter(now));
	}

	boolean canManage(long userId, Activity activity) {
		return this.memberships.roleOf(userId, activity.courseId()).filter((r) -> r == CourseRole.INSTRUCTOR).isPresent();
	}

	record Activity(long activityId, long courseId, boolean published, LocalDateTime openAt) {
	}

}
