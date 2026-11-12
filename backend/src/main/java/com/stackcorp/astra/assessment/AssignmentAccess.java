package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stackcorp.astra.assessment.Assignment.Settings;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.learning.api.ReportActivities;
import com.stackcorp.astra.learning.api.ReportActivities.ReportActivity;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 과제 권한(SC-02, SC-03). 과목 관계는 catalog, 과제가 그 과목에 속하는지는 learning의 활동으로 정한다.
 * 담당자는 그 과목의 과제를 언제나 보고 관리한다. 학생은 공개됐고 시작 일시가 지난 과제만 보고 자기 제출물만 다룬다.
 * 권한이 없거나 경로의 과목과 과제가 맞지 않으면 존재를 드러내지 않도록 404로 응답한다.
 */
@Component
class AssignmentAccess {

	private final JdbcClient jdbc;

	private final CourseMemberships memberships;

	private final ReportActivities activities;

	private final AssignmentRecords records;

	AssignmentAccess(JdbcClient jdbc, CourseMemberships memberships, ReportActivities activities,
			AssignmentRecords records) {
		this.jdbc = jdbc;
		this.memberships = memberships;
		this.activities = activities;
		this.records = records;
	}

	/** 판정과 제출 접수에 쓰는 현재 시각. 활동의 open_at, close_at과 같은 DB 시계다(SC-06). */
	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	CourseRole role(long userId, long courseId) {
		return this.memberships.roleOf(userId, courseId).orElseThrow(ApiException::notFound);
	}

	/** 그 과목에 속한 과제. 다른 과목의 과제면 비어 있다. */
	Optional<Assignment> find(long courseId, long assignmentId) {
		return this.records.find(assignmentId)
			.flatMap((settings) -> this.activities.find(courseId, settings.activityId())
				.map((activity) -> new Assignment(activity, settings)));
	}

	/** 담당자가 관리하는 과제. */
	Assignment managed(long userId, long courseId, long assignmentId) {
		if (role(userId, courseId) != CourseRole.INSTRUCTOR) {
			throw ApiException.notFound();
		}
		return find(courseId, assignmentId).orElseThrow(ApiException::notFound);
	}

	/** 그 과목의 과제 전체. 주차 순서대로. 볼 수 있는지는 부르는 쪽이 거른다. */
	List<Assignment> all(long courseId) {
		List<ReportActivity> activities = this.activities.list(courseId);
		Map<Long, Settings> settings = this.records
			.ofActivities(activities.stream().map(ReportActivity::activityId).toList())
			.stream()
			.collect(Collectors.toMap(Settings::activityId, Function.identity()));
		return activities.stream()
			.filter((activity) -> settings.containsKey(activity.activityId()))
			.map((activity) -> new Assignment(activity, settings.get(activity.activityId())))
			.toList();
	}

	/**
	 * 여러 과목에 걸친 그 활동들 가운데 지우지 않은 과제 활동. 쿼리 한 번이다. 성적 항목이 가리키는 과제가 살아 있고 학생에게 보이는지만 볼 때
	 * 쓴다. 과목 권한과 볼 수 있는지는 부르는 쪽이 판정한다.
	 */
	List<ReportActivity> activitiesOf(Collection<Long> activityIds) {
		return activityIds.isEmpty() ? List.of() : this.activities.findAll(activityIds);
	}

	/** 학생이 제출하는 과제. 지금 볼 수 있어야 한다. */
	Assignment submittable(long userId, long courseId, long assignmentId, LocalDateTime now) {
		if (role(userId, courseId) != CourseRole.STUDENT) {
			throw ApiException.notFound();
		}
		return find(courseId, assignmentId).filter((a) -> a.activity().openToStudents(now))
			.orElseThrow(ApiException::notFound);
	}

	/** 역할에 따라 볼 수 있는 과제. */
	Assignment viewable(CourseRole role, long courseId, long assignmentId, LocalDateTime now) {
		return find(courseId, assignmentId)
			.filter((a) -> role == CourseRole.INSTRUCTOR || a.activity().openToStudents(now))
			.orElseThrow(ApiException::notFound);
	}

}
