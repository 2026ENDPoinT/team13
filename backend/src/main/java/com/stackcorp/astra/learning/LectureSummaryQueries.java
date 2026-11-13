package com.stackcorp.astra.learning;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import com.stackcorp.astra.learning.api.PendingVideos;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 여러 과목에 걸친 영상 콘텐츠 요약. 아무것도 바꾸지 않는다(SC-04).
 */
@Component
class LectureSummaryQueries implements PendingVideos {

	private final JdbcClient jdbc;

	LectureSummaryQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<PendingVideo> of(Collection<Long> courseIds) {
		List<PendingVideo> videos = new ArrayList<>();
		if (courseIds.isEmpty()) {
			return videos;
		}
		this.jdbc.sql("""
				SELECT a.course_id, a.activity_id, a.title,
				       SUM(i.video_state = 'processing'), SUM(i.video_state = 'failed')
				  FROM learning_activities a
				  JOIN online_lecture_items i ON i.activity_id = a.activity_id AND i.deleted_at IS NULL
				 WHERE a.course_id IN (:courses) AND a.deleted_at IS NULL AND i.video_state IN ('processing', 'failed')
				 GROUP BY a.course_id, a.activity_id, a.title
				 ORDER BY a.course_id, a.activity_id
				""").param("courses", courseIds).query((rs) -> {
			videos.add(new PendingVideo(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getInt(5)));
		});
		return videos;
	}

}
