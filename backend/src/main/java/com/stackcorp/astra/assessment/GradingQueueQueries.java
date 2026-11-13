package com.stackcorp.astra.assessment;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stackcorp.astra.assessment.api.GradingQueue;
import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.learning.api.QuizActivities;
import com.stackcorp.astra.learning.api.QuizActivities.QuizActivity;
import com.stackcorp.astra.learning.api.ReportActivities;
import com.stackcorp.astra.learning.api.ReportActivities.ReportActivity;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@link GradingQueue} 구현. 과제의 제출 판정은 {@link SubmissionRecords#counts}(현재 버전이 있는 개인 제출), 채점 판정은
 * {@link GradebookRecords#gradedCounts}(점수가 비어 있지 않음), 퀴즈는 {@link AttemptRecords#counts}의 awaitingGrading(status =
 * submitted)과 같다. 활동이 살아 있는지와 제목은 learning이 정한다.
 */
@Service
class GradingQueueQueries implements GradingQueue {

	private static final String ASSIGNMENT = "assignment";

	private static final String QUIZ = "quiz";

	private final JdbcClient jdbc;

	private final CourseRosters rosters;

	private final ReportActivities reports;

	private final QuizActivities quizzes;

	GradingQueueQueries(JdbcClient jdbc, CourseRosters rosters, ReportActivities reports, QuizActivities quizzes) {
		this.jdbc = jdbc;
		this.rosters = rosters;
		this.quizzes = quizzes;
		this.reports = reports;
	}

	@Override
	public List<PendingGrading> of(Collection<Long> courseIds) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		Map<Key, Integer> pending = new LinkedHashMap<>();
		Map<Long, Set<Long>> roster = this.rosters.students(courseIds);
		Set<Long> students = new HashSet<>();
		roster.values().forEach(students::addAll);
		if (!students.isEmpty()) {
			// 행은 (과제, 학생)마다 하나다. 명단은 과목마다 다르므로 과목의 명단에 있는 학생만 센다.
			this.jdbc.sql("""
					SELECT g.course_id, a.activity_id, a.assignment_id, s.user_id
					  FROM grade_items g
					  JOIN assignments a ON a.activity_id = g.activity_id
					  JOIN assignment_submissions s
					    ON s.assignment_id = a.assignment_id AND s.owner_type = 'user' AND s.current_version_no > 0
					  LEFT JOIN grade_scores sc
					    ON sc.grade_item_id = g.grade_item_id AND sc.user_id = s.user_id AND sc.score IS NOT NULL
					 WHERE g.course_id IN (:courses) AND g.item_type = 'assignment' AND s.user_id IN (:users)
					   AND sc.grade_score_id IS NULL
					""").param("courses", courseIds).param("users", students).query((rs) -> {
				long courseId = rs.getLong(1);
				if (roster.getOrDefault(courseId, Set.of()).contains(rs.getLong(4))) {
					pending.merge(new Key(ASSIGNMENT, courseId, rs.getLong(2), rs.getLong(3)), 1, Integer::sum);
				}
			});
		}
		this.jdbc.sql("""
				SELECT g.course_id, q.activity_id, q.quiz_id, COUNT(*)
				  FROM grade_items g
				  JOIN quizzes q ON q.activity_id = g.activity_id
				  JOIN quiz_attempts t ON t.quiz_id = q.quiz_id AND t.status = 'submitted'
				 WHERE g.course_id IN (:courses) AND g.item_type = 'quiz'
				 GROUP BY g.course_id, q.activity_id, q.quiz_id
				""").param("courses", courseIds).query((rs) -> {
			pending.put(new Key(QUIZ, rs.getLong(1), rs.getLong(2), rs.getLong(3)), rs.getInt(4));
		});
		Map<Long, ReportActivity> reportActivities = activities(pending, ASSIGNMENT).isEmpty() ? Map.of()
				: this.reports.findAll(activities(pending, ASSIGNMENT))
					.stream()
					.collect(Collectors.toMap(ReportActivity::activityId, Function.identity()));
		Map<Long, QuizActivity> quizActivities = activities(pending, QUIZ).isEmpty() ? Map.of()
				: this.quizzes.findAll(activities(pending, QUIZ))
					.stream()
					.collect(Collectors.toMap(QuizActivity::activityId, Function.identity()));
		List<PendingGrading> result = new ArrayList<>();
		pending.forEach((key, count) -> {
			// 지운 활동과, 활동의 과목이 성적 항목의 과목과 다른 행은 넣지 않는다.
			String title = null;
			if (key.kind().equals(ASSIGNMENT)) {
				ReportActivity activity = reportActivities.get(key.activityId());
				title = activity != null && activity.courseId() == key.courseId() ? activity.title() : null;
			}
			else {
				QuizActivity activity = quizActivities.get(key.activityId());
				title = activity != null && activity.courseId() == key.courseId() ? activity.title() : null;
			}
			if (title != null) {
				result.add(new PendingGrading(key.kind(), key.courseId(), key.activityId(), key.targetId(), title, count));
			}
		});
		result.sort(Comparator.comparingLong(PendingGrading::courseId)
			.thenComparing((p) -> p.kind().equals(ASSIGNMENT) ? 0 : 1)
			.thenComparingLong(PendingGrading::activityId));
		return result;
	}

	private static List<Long> activities(Map<Key, Integer> pending, String kind) {
		return pending.keySet().stream().filter((k) -> k.kind().equals(kind)).map(Key::activityId).distinct().toList();
	}

	private record Key(String kind, long courseId, long activityId, long targetId) {
	}

}
