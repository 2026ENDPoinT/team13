package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.learning.api.SchoolCourseItems;

/**
 * {@link SchoolCourseItems}. 가져온 학교 행({@code school_activities}, {@code school_course_weeks}, {@code school_notices})만 읽는다.
 */
@Service
class SchoolCourseItemQueries implements SchoolCourseItems {

	private final JdbcClient jdbc;

	SchoolCourseItemQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<SchoolActivity> activities(long userId, long courseId, LocalDateTime from, LocalDateTime to,
			int limit) {
		if (limit < 1) {
			return List.of();
		}
		return this.jdbc.sql("""
				SELECT a.school_activity_id, a.course_id, a.school_type, a.title, a.open_at, a.close_at, a.school_url,
				       a.expired_yn = 1 OR (a.close_at IS NOT NULL AND a.close_at <= CURRENT_TIMESTAMP(3)) AS closed,
				       COALESCE(s.done_yn, 0) AS done
				  FROM school_activities a
				  LEFT JOIN school_course_weeks w ON w.course_id = a.course_id AND w.week_no = a.week_no
				  LEFT JOIN school_activity_states s ON s.school_activity_id = a.school_activity_id AND s.user_id = :user
				 WHERE a.course_id = :course
				   AND ((w.starts_on IS NOT NULL AND w.starts_on < DATE(:to) AND (w.ends_on IS NULL OR w.ends_on >= DATE(:from)))
				        OR (a.close_at >= :from AND a.close_at < :to) OR (a.open_at >= :from AND a.open_at < :to))
				 ORDER BY a.week_no, a.sort_order, a.school_activity_id
				 LIMIT :limit
				""")
			.param("user", userId)
			.param("course", courseId)
			.param("from", from)
			.param("to", to)
			.param("limit", limit)
			.query((rs, n) -> new SchoolActivity(rs.getLong("school_activity_id"), rs.getLong("course_id"),
					rs.getString("school_type"), rs.getString("title"), rs.getObject("open_at", LocalDateTime.class),
					rs.getObject("close_at", LocalDateTime.class), rs.getBoolean("done"), rs.getBoolean("closed"),
					rs.getString("school_url")))
			.list();
	}

	@Override
	public List<SchoolNotice> notices(Collection<Long> courseIds, int limit) {
		if (courseIds.isEmpty() || limit < 1) {
			return List.of();
		}
		return this.jdbc.sql("""
				SELECT school_notice_id, course_id, title, posted_at, school_url
				  FROM school_notices
				 WHERE course_id IN (:courses)
				 ORDER BY posted_at IS NULL, posted_at DESC, school_notice_id DESC
				 LIMIT :limit
				""")
			.param("courses", new ArrayList<>(courseIds))
			.param("limit", limit)
			.query((rs, n) -> new SchoolNotice(rs.getLong("school_notice_id"), rs.getLong("course_id"),
					rs.getString("title"), rs.getObject("posted_at", LocalDateTime.class), rs.getString("school_url")))
			.list();
	}

}
