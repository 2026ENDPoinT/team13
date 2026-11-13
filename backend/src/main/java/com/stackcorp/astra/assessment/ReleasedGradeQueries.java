package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stackcorp.astra.assessment.GradebookRecords.ReleasedCandidate;
import com.stackcorp.astra.assessment.api.ReleasedGrades;
import com.stackcorp.astra.learning.api.ReportActivities.ReportActivity;

import org.springframework.stereotype.Service;

/**
 * {@link ReleasedGrades} 구현. 성적 화면(GradebookService.mine)과 같은 규칙을 쓴다. 공개 판정은 과제·직접 입력 항목이
 * {@link GradeItem#released(String, LocalDateTime, LocalDateTime)}, 퀴즈 항목이 {@link Quiz#release}다. 활동이 살아 있고 학생에게
 * 보이는지는 후보의 활동만 한 번에 읽어({@link AssignmentAccess#activitiesOf}, {@link QuizAccess#ofActivities}) 정한다. 과목마다
 * 조회하지 않는다.
 */
@Service
class ReleasedGradeQueries implements ReleasedGrades {

	/** 거르기 전에 읽는 후보 수의 상한. */
	private static final int CANDIDATES = 100;

	private final GradebookRecords records;

	private final AssignmentAccess assignments;

	private final QuizAccess quizzes;

	ReleasedGradeQueries(GradebookRecords records, AssignmentAccess assignments, QuizAccess quizzes) {
		this.records = records;
		this.assignments = assignments;
		this.quizzes = quizzes;
	}

	@Override
	public List<ReleasedGrade> releasedSince(long studentId, Collection<Long> courseIds, LocalDateTime since,
			LocalDateTime now, int limit) {
		if (limit < 1 || limit > MAX_LIMIT) {
			throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
		}
		Set<Long> courses = new HashSet<>(courseIds);
		courses.removeAll(this.records.hiddenActivityCourses(courses));
		List<ReleasedCandidate> candidates = this.records.releasedCandidates(studentId, courses, since, now, CANDIDATES);
		Map<Long, ReportActivity> reports = this.assignments.activitiesOf(activitiesOfType(candidates, GradeItem.ASSIGNMENT))
			.stream()
			.collect(Collectors.toMap(ReportActivity::activityId, Function.identity()));
		Map<Long, Quiz> quizzes = this.quizzes.ofActivities(activitiesOfType(candidates, GradeItem.QUIZ))
			.stream()
			.collect(Collectors.toMap((quiz) -> quiz.activity().activityId(), Function.identity()));
		return candidates.stream()
			.filter((c) -> visibleAndReleased(c, reports, quizzes, now))
			.limit(limit)
			.map((c) -> new ReleasedGrade(c.courseId(), c.gradeItemId(), c.type(), c.activityId(), c.name(),
					c.releaseAt()))
			.toList();
	}

	/** 학생이 그 항목을 볼 수 있고 점수를 공개했는지. 활동이 있는데 과제도 퀴즈도 아니면 넣지 않는다. */
	private static boolean visibleAndReleased(ReleasedCandidate c, Map<Long, ReportActivity> reports,
			Map<Long, Quiz> quizzes, LocalDateTime now) {
		if (c.activityId() == null) {
			return GradeItem.released(c.scoreVisibility(), c.releaseAt(), now);
		}
		if (GradeItem.QUIZ.equals(c.type())) {
			Quiz quiz = quizzes.get(c.activityId());
			return quiz != null && quiz.activity().courseId() == c.courseId() && quiz.activity().openToStudents(now)
					&& quiz.release(now).scores();
		}
		ReportActivity report = GradeItem.ASSIGNMENT.equals(c.type()) ? reports.get(c.activityId()) : null;
		return report != null && report.courseId() == c.courseId() && report.openToStudents(now)
				&& GradeItem.released(c.scoreVisibility(), c.releaseAt(), now);
	}

	private static List<Long> activitiesOfType(List<ReleasedCandidate> candidates, String type) {
		return candidates.stream()
			.filter((c) -> c.activityId() != null && type.equals(c.type()))
			.map(ReleasedCandidate::activityId)
			.distinct()
			.toList();
	}

}
