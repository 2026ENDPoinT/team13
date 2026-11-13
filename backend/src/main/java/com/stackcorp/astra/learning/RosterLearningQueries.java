package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import com.stackcorp.astra.catalog.api.RosterLearning;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 담당자 수강생 화면의 학습 열. 아무것도 바꾸지 않는다(SC-04). 최근 학습은 이어보기 포인터(영상 학습을 시작할 때 남긴다)의 기록 일시다.
 * 진도율은 학생 진도 화면과 같은 기준으로 학생에게 열린(공개됐고 시작한) 활동의 지우지 않은 영상 콘텐츠 가운데 출석인정을 채운 비율이다.
 * 쿼리는 사용자 수와 관계없이 넷(시각, 열린 콘텐츠 수, 사용자별 충족 수, 최근 학습)이다.
 */
@Component
class RosterLearningQueries implements RosterLearning {

	private static final String OPEN = """
			 WHERE a.course_id = :course AND a.deleted_at IS NULL AND a.published_yn = 1
			   AND (a.open_at IS NULL OR a.open_at <= :now)
			""";

	private final JdbcClient jdbc;

	private final LearningClock clock;

	RosterLearningQueries(JdbcClient jdbc, LearningClock clock) {
		this.jdbc = jdbc;
		this.clock = clock;
	}

	@Override
	public Map<Long, Learning> of(long courseId, Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return Map.of();
		}
		LocalDateTime now = this.clock.now();
		int open = this.jdbc.sql("""
				SELECT COUNT(*) FROM learning_activities a
				  JOIN course_weeks w ON w.course_week_id = a.course_week_id
				  JOIN online_lecture_items i ON i.activity_id = a.activity_id AND i.deleted_at IS NULL
				""" + OPEN).param("course", courseId).param("now", now).query(Integer.class).single();
		Map<Long, Integer> met = new HashMap<>();
		if (open > 0) {
			this.jdbc.sql("""
					SELECT p.user_id, COUNT(*) FROM online_lecture_item_progress p
					  JOIN online_lecture_items i ON i.online_lecture_item_id = p.online_lecture_item_id AND i.deleted_at IS NULL
					  JOIN learning_activities a ON a.activity_id = i.activity_id
					  JOIN course_weeks w ON w.course_week_id = a.course_week_id
					""" + OPEN + """
					   AND p.user_id IN (:users) AND p.met_at IS NOT NULL
					 GROUP BY p.user_id
					""")
				.param("course", courseId)
				.param("now", now)
				.param("users", userIds)
				.query((rs) -> {
					met.put(rs.getLong(1), rs.getInt(2));
				});
		}
		Map<Long, LocalDateTime> lastAccess = new HashMap<>();
		this.jdbc.sql("""
				SELECT user_id, recorded_at FROM course_continue_pointers
				 WHERE course_id = :course AND user_id IN (:users)
				""").param("course", courseId).param("users", userIds).query((rs) -> {
			lastAccess.put(rs.getLong(1), rs.getObject(2, LocalDateTime.class));
		});
		Map<Long, Learning> result = new LinkedHashMap<>();
		for (long userId : userIds) {
			Integer percent = open == 0 ? null : (int) (met.getOrDefault(userId, 0) * 100L / open);
			result.put(userId, new Learning(lastAccess.get(userId), percent));
		}
		return result;
	}

}
