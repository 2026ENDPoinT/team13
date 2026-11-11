package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import com.stackcorp.astra.learning.api.DueActivities;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 공개·시작 판정은 {@link LearningAccess#open}과 같다. 여기에 마감 전({@code now < close_at}) 조건을 더한다.
 */
@Service
class DueActivityQueries implements DueActivities {

	private static final String DUE = """
			  FROM learning_activities a
			 WHERE a.course_id IN (:courses) AND a.published_yn = 1 AND a.deleted_at IS NULL
			   AND (a.open_at IS NULL OR a.open_at <= :now)
			   AND a.close_at IS NOT NULL AND a.close_at > :now
			""";

	private final JdbcClient jdbc;

	DueActivityQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<DueActivity> due(Collection<Long> courseIds, LocalDateTime now, Position after, int limit) {
		if (courseIds.isEmpty() || limit < 1) {
			return List.of();
		}
		String keyset = after == null ? ""
				: " AND (a.close_at > :afterClose OR (a.close_at = :afterClose AND a.activity_id > :afterId))";
		var sql = this.jdbc.sql("SELECT a.activity_id, a.course_id, a.activity_type, a.title, a.open_at, a.close_at" + DUE
				+ keyset + " ORDER BY a.close_at, a.activity_id LIMIT :limit")
			.param("courses", courseIds)
			.param("now", now)
			.param("limit", limit);
		if (after != null) {
			sql = sql.param("afterClose", after.closeAt()).param("afterId", after.activityId());
		}
		return sql.query((rs, n) -> new DueActivity(rs.getLong("activity_id"), rs.getLong("course_id"),
				rs.getString("activity_type"), rs.getString("title"), rs.getObject("open_at", LocalDateTime.class),
				rs.getObject("close_at", LocalDateTime.class)))
			.list();
	}

	@Override
	public int count(Collection<Long> courseIds, LocalDateTime now) {
		if (courseIds.isEmpty()) {
			return 0;
		}
		return this.jdbc.sql("SELECT COUNT(*)" + DUE)
			.param("courses", courseIds)
			.param("now", now)
			.query(Integer.class)
			.single();
	}

}
