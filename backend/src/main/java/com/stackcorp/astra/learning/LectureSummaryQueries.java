package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.learning.api.LectureProgressSummaries;
import com.stackcorp.astra.learning.api.PendingVideos;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 여러 과목에 걸친 영상 콘텐츠 요약. 아무것도 바꾸지 않는다(SC-04). 학생에게 열린 활동의 판정은 {@link LearningAccess#open}과 같고,
 * 진도의 충족은 {@link ItemProgress}의 met(충족 일시가 있음)과 같다.
 */
@Component
class LectureSummaryQueries implements LectureProgressSummaries, PendingVideos {

	private final JdbcClient jdbc;

	LectureSummaryQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<Long, Integer> percentOf(long userId, Collection<Long> courseIds, LocalDateTime now) {
		Map<Long, Integer> percent = new HashMap<>();
		if (courseIds.isEmpty()) {
			return percent;
		}
		Map<Long, long[]> counts = new HashMap<>();
		// 서비스 영상 콘텐츠와, 학교 강의실에서 가져온 영상 활동(ADR-0042, 학교 화면의 본인 완료 표시)을 함께 센다.
		this.jdbc.sql("""
				SELECT a.course_id, COUNT(*), COUNT(p.met_at)
				  FROM learning_activities a
				  JOIN course_weeks w ON w.course_week_id = a.course_week_id
				  JOIN online_lecture_items i ON i.activity_id = a.activity_id AND i.deleted_at IS NULL
				  LEFT JOIN online_lecture_item_progress p
				         ON p.online_lecture_item_id = i.online_lecture_item_id AND p.user_id = :user
				 WHERE a.course_id IN (:courses) AND a.deleted_at IS NULL AND a.published_yn = 1
				   AND (a.open_at IS NULL OR a.open_at <= :now)
				 GROUP BY a.course_id
				UNION ALL
				SELECT a.course_id, COUNT(*), COUNT(CASE WHEN s.done_yn = 1 THEN 1 END)
				  FROM school_activities a
				  LEFT JOIN school_activity_states s ON s.school_activity_id = a.school_activity_id AND s.user_id = :user
				 WHERE a.course_id IN (:courses) AND a.school_type = 'lecture_weeks'
				   AND (a.open_at IS NULL OR a.open_at <= :now)
				 GROUP BY a.course_id
				""").param("user", userId).param("courses", courseIds).param("now", now).query((rs) -> {
			long[] count = counts.computeIfAbsent(rs.getLong(1), (id) -> new long[2]);
			count[0] += rs.getLong(2);
			count[1] += rs.getLong(3);
		});
		counts.forEach((course, count) -> percent.put(course, (int) (count[1] * 100L / count[0])));
		return percent;
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
