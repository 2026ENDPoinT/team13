package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.learning.api.QuizActivities;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 퀴즈 활동(activity_type = test)의 저장과 조회. 퀴즈 설정과 같은 transaction에서 부른다(SC-05).
 * 퀴즈 활동을 읽는 SQL은 {@link #SELECT} 하나다.
 */
@Service
class QuizActivityService implements QuizActivities {

	private final JdbcClient jdbc;

	private final CourseWeeks weeks;

	QuizActivityService(JdbcClient jdbc, CourseWeeks weeks) {
		this.jdbc = jdbc;
		this.weeks = weeks;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public long create(long courseId, long createdBy, QuizActivityInput input) {
		long week = this.weeks.weekId(courseId, input.weekNo());
		int order = this.jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM learning_activities WHERE course_week_id = :week")
			.param("week", week)
			.query(Integer.class)
			.single();
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, description, open_at, close_at,
				                                 offline_yn, published_yn, sort_order, created_by)
				VALUES (:course, :week, 'test', :title, :description, :openAt, :closeAt, :offline, :published, :order, :user)
				""")
			.param("course", courseId)
			.param("week", week)
			.param("title", input.title())
			.param("description", input.description())
			.param("openAt", input.openAt())
			.param("closeAt", input.closeAt())
			.param("offline", input.offline())
			.param("published", input.published())
			.param("order", order)
			.param("user", createdBy)
			.update(key, "activity_id");
		return key.getKey().longValue();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean update(long courseId, long activityId, QuizActivityInput input) {
		long week = this.weeks.weekId(courseId, input.weekNo());
		return this.jdbc.sql("""
				UPDATE learning_activities
				   SET course_week_id = :week, title = :title, description = :description, open_at = :openAt,
				       close_at = :closeAt, offline_yn = :offline, published_yn = :published
				 WHERE activity_id = :activity AND course_id = :course AND activity_type = 'test'
				""")
			.param("week", week)
			.param("title", input.title())
			.param("description", input.description())
			.param("openAt", input.openAt())
			.param("closeAt", input.closeAt())
			.param("offline", input.offline())
			.param("published", input.published())
			.param("activity", activityId)
			.param("course", courseId)
			.update() > 0;
	}

	@Override
	public Optional<QuizActivity> find(long courseId, long activityId) {
		return this.jdbc.sql(SELECT + " AND a.activity_id = :activity")
			.param("course", courseId)
			.param("activity", activityId)
			.query(QuizActivityService::row)
			.optional();
	}

	@Override
	public List<QuizActivity> list(long courseId) {
		return this.jdbc.sql(SELECT + " ORDER BY w.week_no, a.sort_order, a.activity_id")
			.param("course", courseId)
			.query(QuizActivityService::row)
			.list();
	}

	private static final String SELECT = """
			SELECT a.activity_id, a.course_id, w.week_no, a.title, a.description, a.open_at, a.close_at, a.published_yn,
			       a.offline_yn
			  FROM learning_activities a JOIN course_weeks w ON w.course_week_id = a.course_week_id
			 WHERE a.course_id = :course AND a.activity_type = 'test'
			""";

	private static QuizActivity row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new QuizActivity(rs.getLong("activity_id"), rs.getLong("course_id"), rs.getInt("week_no"),
				rs.getString("title"), rs.getString("description"), rs.getObject("open_at", LocalDateTime.class),
				rs.getObject("close_at", LocalDateTime.class), rs.getBoolean("published_yn"),
				rs.getBoolean("offline_yn"));
	}

}
