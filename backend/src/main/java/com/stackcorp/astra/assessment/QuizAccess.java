package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stackcorp.astra.assessment.AttemptRecords.Attempt;
import com.stackcorp.astra.assessment.Quiz.Settings;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.learning.api.QuizActivities;
import com.stackcorp.astra.learning.api.QuizActivities.QuizActivity;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.stereotype.Component;

/**
 * 퀴즈 권한(SC-02, SC-03, SEC-13). 과목 관계는 catalog, 퀴즈가 그 과목에 속하는지는 learning의 활동으로 정한다.
 * 담당자는 그 과목의 퀴즈와 모든 응시를 언제나 보고 관리한다. 학생은 공개됐고 시작 일시가 지난 퀴즈만 보고 자기 응시만 다룬다.
 * 권한이 없거나 경로의 과목, 퀴즈, 응시, 문항이 서로 맞지 않으면 존재를 드러내지 않도록 404로 응답한다.
 */
@Component
class QuizAccess {

	private final CourseMemberships memberships;

	private final QuizActivities activities;

	private final QuizRecords records;

	private final AttemptRecords attempts;

	private final QuizClock clock;

	QuizAccess(CourseMemberships memberships, QuizActivities activities, QuizRecords records, AttemptRecords attempts,
			QuizClock clock) {
		this.memberships = memberships;
		this.activities = activities;
		this.records = records;
		this.attempts = attempts;
		this.clock = clock;
	}

	LocalDateTime now() {
		return this.clock.now();
	}

	CourseRole role(long userId, long courseId) {
		return this.memberships.roleOf(userId, courseId).orElseThrow(ApiException::notFound);
	}

	/** 그 과목에 속한 퀴즈. 다른 과목의 퀴즈면 비어 있다. */
	Optional<Quiz> find(long courseId, long quizId) {
		return this.records.find(quizId)
			.flatMap((settings) -> this.activities.find(courseId, settings.activityId())
				.map((activity) -> new Quiz(activity, settings)));
	}

	/** 담당자가 관리하는 퀴즈. */
	Quiz managed(long userId, long courseId, long quizId) {
		if (role(userId, courseId) != CourseRole.INSTRUCTOR) {
			throw ApiException.notFound();
		}
		return find(courseId, quizId).orElseThrow(ApiException::notFound);
	}

	/** 그 과목의 퀴즈 전체. 주차 순서대로. 볼 수 있는지는 부르는 쪽이 거른다. */
	List<Quiz> all(long courseId) {
		return join(this.activities.list(courseId));
	}

	/**
	 * 여러 과목에 걸친 그 활동들의 퀴즈. 지운 활동이나 퀴즈가 아닌 활동은 없다. 쿼리는 활동 수와 관계없이 둘이다. 과목 권한과 볼 수 있는지는
	 * 부르는 쪽이 판정한다.
	 */
	List<Quiz> ofActivities(Collection<Long> activityIds) {
		return activityIds.isEmpty() ? List.of() : join(this.activities.findAll(activityIds));
	}

	private List<Quiz> join(List<QuizActivity> list) {
		Map<Long, Settings> settings = this.records
			.ofActivities(list.stream().map(QuizActivity::activityId).toList())
			.stream()
			.collect(Collectors.toMap(Settings::activityId, Function.identity()));
		return list.stream()
			.filter((activity) -> settings.containsKey(activity.activityId()))
			.map((activity) -> new Quiz(activity, settings.get(activity.activityId())))
			.toList();
	}

	/** 역할에 따라 볼 수 있는 퀴즈. */
	Quiz viewable(CourseRole role, long courseId, long quizId, LocalDateTime now) {
		return find(courseId, quizId)
			.filter((q) -> role == CourseRole.INSTRUCTOR || q.activity().openToStudents(now))
			.orElseThrow(ApiException::notFound);
	}

	/** 수강생이 지금 볼 수 있는 퀴즈. 응시와 답안 저장은 이것으로 시작한다. */
	Quiz forStudent(long userId, long courseId, long quizId, LocalDateTime now) {
		if (role(userId, courseId) != CourseRole.STUDENT) {
			throw ApiException.notFound();
		}
		return find(courseId, quizId).filter((q) -> q.activity().openToStudents(now)).orElseThrow(ApiException::notFound);
	}

	/** 학생 본인의 그 퀴즈 응시. 다른 학생의 응시이거나 다른 퀴즈의 응시면 404다. */
	Attempt ownAttempt(Quiz quiz, long userId, long attemptId) {
		return this.attempts.find(attemptId)
			.filter((a) -> a.quizId() == quiz.quizId() && a.userId() == userId)
			.orElseThrow(ApiException::notFound);
	}

	/** 담당자가 보는 그 퀴즈의 응시. */
	Attempt attemptOf(Quiz quiz, long attemptId) {
		return this.attempts.find(attemptId).filter((a) -> a.quizId() == quiz.quizId()).orElseThrow(ApiException::notFound);
	}

}
