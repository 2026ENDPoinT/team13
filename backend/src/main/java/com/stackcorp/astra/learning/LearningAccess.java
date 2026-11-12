package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 학습활동 권한. 담당자는 언제나, 학생은 공개됐고 시작 일시가 지난 활동만 본다. 시각은 서버 시계로 정한다(SC-06).
 * 활동이 요청한 과목에 속하지 않으면 거부한다(SC-03). 판정할 Activity는 지우지 않은 활동(deleted_at IS NULL)에서만 만든다.
 * 지운 활동은 담당자에게도 없는 활동이다(ADR-0011).
 */
@Component
class LearningAccess {

	private final JdbcClient jdbc;

	private final CourseMemberships memberships;

	LearningAccess(JdbcClient jdbc, CourseMemberships memberships) {
		this.jdbc = jdbc;
		this.memberships = memberships;
	}

	Optional<CourseRole> roleIn(long userId, long courseId) {
		return this.memberships.roleOf(userId, courseId);
	}

	/** 판정에 쓰는 현재 시각. open_at과 같은 DB 시계다. */
	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP").query(LocalDateTime.class).single();
	}

	/** 학생에게 열린 활동인지. */
	static boolean open(Activity activity, LocalDateTime now) {
		return activity.published() && (activity.openAt() == null || !activity.openAt().isAfter(now));
	}

	boolean canView(long userId, Activity activity) {
		Optional<CourseRole> role = roleIn(userId, activity.courseId());
		if (role.isEmpty()) {
			return false;
		}
		return role.get() == CourseRole.INSTRUCTOR || open(activity, now());
	}

	/**
	 * 그 과목의 역할로 활동을 볼 수 있는지. {@link #canView}와 같은 규칙을, 역할과 시각을 이미 읽은 목록(강의실 파일모음)이 행마다 쓴다.
	 */
	static boolean visible(CourseRole role, Activity activity, LocalDateTime now) {
		return role == CourseRole.INSTRUCTOR || open(activity, now);
	}

	boolean canManage(long userId, Activity activity) {
		return canManage(userId, activity.courseId());
	}

	boolean canManage(long userId, long courseId) {
		return roleIn(userId, courseId).filter((r) -> r == CourseRole.INSTRUCTOR).isPresent();
	}

	record Activity(long activityId, long courseId, boolean published, LocalDateTime openAt) {
	}

}
