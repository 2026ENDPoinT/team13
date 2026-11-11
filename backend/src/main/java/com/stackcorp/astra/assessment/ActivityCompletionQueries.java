package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.stackcorp.astra.assessment.AttemptRecords.Attempt;
import com.stackcorp.astra.assessment.Quiz.Settings;
import com.stackcorp.astra.assessment.api.ActivityCompletions;

import org.springframework.stereotype.Service;

/**
 * {@link ActivityCompletions} 구현. 과제 제출은 {@link SubmissionRecords#submittedActivities}, 설문 완료는 참여 원장
 * ({@link SurveyRecords#completedActivities}), 퀴즈는 {@link Settings#usedUpBy}로 판정한다. 퀴즈 판정은 학생 상세의 canStart와 같은
 * 규칙({@link Quiz#canStart})에서 나온다.
 */
@Service
class ActivityCompletionQueries implements ActivityCompletions {

	private final SubmissionRecords submissions;

	private final SurveyRecords surveys;

	private final QuizRecords quizzes;

	private final AttemptRecords attempts;

	ActivityCompletionQueries(SubmissionRecords submissions, SurveyRecords surveys, QuizRecords quizzes,
			AttemptRecords attempts) {
		this.submissions = submissions;
		this.surveys = surveys;
		this.quizzes = quizzes;
		this.attempts = attempts;
	}

	@Override
	public Set<Long> completedBy(long userId, Collection<Long> activityIds, LocalDateTime now) {
		if (activityIds.isEmpty()) {
			return Set.of();
		}
		Set<Long> done = new HashSet<>(this.submissions.submittedActivities(userId, activityIds));
		done.addAll(this.surveys.completedActivities(userId, activityIds));
		List<Settings> quizzes = this.quizzes.ofActivities(activityIds);
		Map<Long, List<Attempt>> own = this.attempts.ofUser(quizzes.stream().map(Settings::quizId).toList(), userId);
		quizzes.stream()
			.filter((quiz) -> quiz.usedUpBy(own.getOrDefault(quiz.quizId(), List.of()), now))
			.forEach((quiz) -> done.add(quiz.activityId()));
		return done;
	}

}
