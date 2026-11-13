package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.stackcorp.astra.learning.api.ActivityTargets;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * {@link ActivityTargets} 구현. 과제, 퀴즈, 설문 설정 행을 쿼리 한 번으로 읽는다. 지각 마감은 과제 제출 구간({@link Assignment#phase})이
 * 지각을 받는 경우({@value Assignment#LATE_ALLOW_UNTIL})에만 돌려준다.
 */
@Service
class ActivityTargetQueries implements ActivityTargets {

	private final JdbcClient jdbc;

	ActivityTargetQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<Long, Target> of(Collection<Long> activityIds) {
		Map<Long, Target> targets = new HashMap<>();
		if (activityIds.isEmpty()) {
			return targets;
		}
		this.jdbc.sql("""
				SELECT activity_id, 'assignment' AS kind, assignment_id AS id,
				       CASE WHEN late_policy = :late THEN late_until END AS late_until
				  FROM assignments WHERE activity_id IN (:ids)
				UNION ALL
				SELECT activity_id, 'quiz', quiz_id, NULL FROM quizzes WHERE activity_id IN (:ids)
				UNION ALL
				SELECT activity_id, 'survey', survey_id, NULL FROM surveys WHERE activity_id IN (:ids)
				""")
			.param("ids", activityIds)
			.param("late", Assignment.LATE_ALLOW_UNTIL)
			.query((rs) -> {
				targets.put(rs.getLong("activity_id"), new Target(rs.getString("kind"), rs.getLong("id"),
						rs.getObject("late_until", LocalDateTime.class)));
			});
		return targets;
	}

}
