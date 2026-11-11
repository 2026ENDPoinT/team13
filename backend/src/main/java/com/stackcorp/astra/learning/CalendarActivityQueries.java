package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

import com.stackcorp.astra.learning.api.CalendarActivities;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 학생에게 열린 활동의 판정은 {@link LearningAccess#open}과 같다. 이 클래스가 읽는 learning_activities 행의 범위는
 * {@link #ACTIVITIES} 한 곳에서 정한다.
 */
@Service
class CalendarActivityQueries implements CalendarActivities {

	/** 캘린더가 읽는 활동 행. 활동을 지우는 표시가 생기면 이 조건에 더한다. */
	private static final String ACTIVITIES = " FROM learning_activities a WHERE a.close_at >= :from AND a.close_at < :to";

	private static final String TAUGHT = "a.course_id IN (:taught)";

	private static final String ENROLLED = """
			(a.course_id IN (:enrolled) AND a.published_yn = 1 AND (a.open_at IS NULL OR a.open_at <= :now))""";

	private final JdbcClient jdbc;

	CalendarActivityQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<CalendarActivity> closingBetween(Collection<Long> taughtCourseIds, Collection<Long> enrolledCourseIds,
			LocalDateTime now, LocalDateTime from, LocalDateTime to, int limit) {
		if ((taughtCourseIds.isEmpty() && enrolledCourseIds.isEmpty()) || limit < 1) {
			return List.of();
		}
		String scope = taughtCourseIds.isEmpty() ? ENROLLED
				: enrolledCourseIds.isEmpty() ? TAUGHT : "(" + TAUGHT + " OR " + ENROLLED + ")";
		return this.jdbc.sql("SELECT a.activity_id, a.course_id, a.activity_type, a.title, a.open_at, a.close_at, a.published_yn"
				+ ACTIVITIES + " AND " + scope + " ORDER BY a.close_at, a.activity_id LIMIT :limit")
			.param("from", from)
			.param("to", to)
			.param("now", now)
			.param("taught", taughtCourseIds.isEmpty() ? List.of(0L) : taughtCourseIds)
			.param("enrolled", enrolledCourseIds.isEmpty() ? List.of(0L) : enrolledCourseIds)
			.param("limit", limit)
			.query((rs, n) -> new CalendarActivity(rs.getLong("activity_id"), rs.getLong("course_id"),
					rs.getString("activity_type"), rs.getString("title"), rs.getObject("open_at", LocalDateTime.class),
					rs.getObject("close_at", LocalDateTime.class), rs.getBoolean("published_yn")))
			.list();
	}

}
