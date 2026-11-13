package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stackcorp.astra.assessment.Survey.Settings;
import com.stackcorp.astra.assessment.SurveyRecords.Audience;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserGrades;
import com.stackcorp.astra.identity.api.UserRole;
import com.stackcorp.astra.learning.api.SurveyActivities;
import com.stackcorp.astra.learning.api.SurveyActivities.SurveyActivity;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 설문 권한(SC-02, SC-03). 강의실 설문은 과목 관계(catalog)와 활동 소속(learning)으로, 알림마당 설문은 대상 행(survey_targets)과
 * session의 역할, identity의 학년으로 정한다. 담당자는 그 과목의 설문을 언제나 보고 관리한다. 학생과 알림마당 대상자는 공개됐고
 * 시작 일시가 지난 설문만 본다. 권한이 없거나 경로의 과목과 설문이 맞지 않으면 존재를 드러내지 않도록 404로 응답한다.
 */
@Component
class SurveyAccess {

	private final JdbcClient jdbc;

	private final CourseMemberships memberships;

	private final SurveyActivities activities;

	private final SurveyRecords records;

	private final UserGrades grades;

	SurveyAccess(JdbcClient jdbc, CourseMemberships memberships, SurveyActivities activities, SurveyRecords records,
			UserGrades grades) {
		this.jdbc = jdbc;
		this.memberships = memberships;
		this.activities = activities;
		this.records = records;
		this.grades = grades;
	}

	/** 판정과 접수에 쓰는 현재 시각. 활동의 open_at, close_at과 같은 DB 시계다(SC-06). */
	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	CourseRole role(long userId, long courseId) {
		return this.memberships.roleOf(userId, courseId).orElseThrow(ApiException::notFound);
	}

	/** 그 과목에 속한 설문. 다른 과목이거나 알림마당 설문이면 비어 있다. */
	Optional<Survey> find(long courseId, long surveyId) {
		return this.records.find(surveyId)
			.filter((settings) -> settings.activityId() != null)
			.flatMap((settings) -> this.activities.find(courseId, settings.activityId())
				.map((activity) -> new Survey(settings, activity)));
	}

	/** 담당자가 관리하는 설문. */
	Survey managed(long userId, long courseId, long surveyId) {
		if (role(userId, courseId) != CourseRole.INSTRUCTOR) {
			throw ApiException.notFound();
		}
		return find(courseId, surveyId).orElseThrow(ApiException::notFound);
	}

	/** 역할에 따라 볼 수 있는 설문. */
	Survey viewable(CourseRole role, long courseId, long surveyId, LocalDateTime now) {
		return find(courseId, surveyId).filter((s) -> role == CourseRole.INSTRUCTOR || s.openToRespondents(now))
			.orElseThrow(ApiException::notFound);
	}

	/** 학생이 응답하는 설문. 지금 볼 수 있어야 한다. */
	Survey answerable(long userId, long courseId, long surveyId, LocalDateTime now) {
		if (role(userId, courseId) != CourseRole.STUDENT) {
			throw ApiException.notFound();
		}
		return find(courseId, surveyId).filter((s) -> s.openToRespondents(now)).orElseThrow(ApiException::notFound);
	}

	/** 그 과목의 설문 전체. 주차 순서대로. 볼 수 있는지는 부르는 쪽이 거른다. */
	List<Survey> all(long courseId) {
		List<SurveyActivity> list = this.activities.list(courseId);
		Map<Long, Settings> settings = this.records
			.ofActivities(list.stream().map(SurveyActivity::activityId).toList())
			.stream()
			.collect(Collectors.toMap(Settings::activityId, Function.identity()));
		return list.stream()
			.filter((activity) -> settings.containsKey(activity.activityId()))
			.map((activity) -> new Survey(settings.get(activity.activityId()), activity))
			.toList();
	}

	/**
	 * 알림마당 설문의 대상 판정 속성. break-glass 계정은 어떤 설문의 대상도 아니다(ADR-0004 6번).
	 *
	 * @return 대상이 될 수 없는 사용자면 비어 있다
	 */
	Optional<Audience> audience(Actor actor) {
		if (actor.breakGlass()) {
			return Optional.empty();
		}
		boolean student = actor.role() == UserRole.STUDENT;
		Integer grade = student ? this.grades.gradeOf(actor.userId()).stream().boxed().findFirst().orElse(null) : null;
		return Optional.of(new Audience(student, !student, grade));
	}

	/** 그 사용자가 대상이고 시작한 알림마당 설문. */
	Survey community(Actor actor, long surveyId, LocalDateTime now) {
		Audience audience = audience(actor).orElseThrow(ApiException::notFound);
		return this.records.find(surveyId)
			.filter((settings) -> settings.activityId() == null)
			.map((settings) -> new Survey(settings, null))
			.filter((survey) -> survey.openToRespondents(now) && this.records.targets(surveyId, audience))
			.orElseThrow(ApiException::notFound);
	}

}
