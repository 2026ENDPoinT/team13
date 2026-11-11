package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.learning.api.ReportActivities;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 과제 활동(activity_type = report)의 저장과 조회. 과제 설정과 같은 transaction에서 부른다(SC-05).
 */
@Service
class ReportActivityService implements ReportActivities {

	private final JdbcClient jdbc;

	private final CourseWeeks weeks;

	ReportActivityService(JdbcClient jdbc, CourseWeeks weeks) {
		this.jdbc = jdbc;
		this.weeks = weeks;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public long create(long courseId, long createdBy, ReportActivityInput input) {
		long week = this.weeks.weekId(courseId, input.weekNo());
		int order = this.jdbc.sql("SELECT COALESCE(MAX(sort_order), 0) + 1 FROM learning_activities WHERE course_week_id = :week")
			.param("week", week)
			.query(Integer.class)
			.single();
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, description, open_at, close_at,
				                                 published_yn, sort_order, created_by)
				VALUES (:course, :week, 'report', :title, :description, :openAt, :closeAt, :published, :order, :user)
				""")
			.param("course", courseId)
			.param("week", week)
			.param("title", input.title())
			.param("description", input.description())
			.param("openAt", input.openAt())
			.param("closeAt", input.closeAt())
			.param("published", input.published())
			.param("order", order)
			.param("user", createdBy)
			.update(key, "activity_id");
		return key.getKey().longValue();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean update(long courseId, long activityId, ReportActivityInput input) {
		long week = this.weeks.weekId(courseId, input.weekNo());
		return this.jdbc.sql("""
				UPDATE learning_activities
				   SET course_week_id = :week, title = :title, description = :description, open_at = :openAt,
				       close_at = :closeAt, published_yn = :published
				 WHERE activity_id = :activity AND course_id = :course AND activity_type = 'report' AND deleted_at IS NULL
				""")
			.param("week", week)
			.param("title", input.title())
			.param("description", input.description())
			.param("openAt", input.openAt())
			.param("closeAt", input.closeAt())
			.param("published", input.published())
			.param("activity", activityId)
			.param("course", courseId)
			.update() > 0;
	}

	@Override
	public Optional<ReportActivity> find(long courseId, long activityId) {
		return this.jdbc.sql(SELECT + " AND a.activity_id = :activity")
			.param("course", courseId)
			.param("activity", activityId)
			.query(ReportActivityService::row)
			.optional();
	}

	@Override
	public List<ReportActivity> list(long courseId) {
		return this.jdbc.sql(SELECT + " ORDER BY w.week_no, a.sort_order, a.activity_id")
			.param("course", courseId)
			.query(ReportActivityService::row)
			.list();
	}

	private static final String SELECT = """
			SELECT a.activity_id, a.course_id, w.week_no, a.title, a.description, a.open_at, a.close_at, a.published_yn
			  FROM learning_activities a JOIN course_weeks w ON w.course_week_id = a.course_week_id
			 WHERE a.course_id = :course AND a.activity_type = 'report' AND a.deleted_at IS NULL
			""";

	private static ReportActivity row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new ReportActivity(rs.getLong("activity_id"), rs.getLong("course_id"), rs.getInt("week_no"),
				rs.getString("title"), rs.getString("description"), rs.getObject("open_at", LocalDateTime.class),
				rs.getObject("close_at", LocalDateTime.class), rs.getBoolean("published_yn"));
	}

}
