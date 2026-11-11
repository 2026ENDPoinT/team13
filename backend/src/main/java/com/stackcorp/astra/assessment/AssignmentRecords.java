package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.assessment.Assignment.Settings;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * assignments와 그 과제의 grade_items 행. 과제 하나에 성적 항목 하나다(db/design/40_assessment.md).
 */
@Repository
class AssignmentRecords {

	private final JdbcClient jdbc;

	AssignmentRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Settings> find(long assignmentId) {
		return this.jdbc.sql(SELECT + " WHERE a.assignment_id = :id")
			.param("id", assignmentId)
			.query(AssignmentRecords::settings)
			.optional();
	}

	List<Settings> ofActivities(Collection<Long> activityIds) {
		if (activityIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql(SELECT + " WHERE a.activity_id IN (:ids)")
			.param("ids", activityIds)
			.query(AssignmentRecords::settings)
			.list();
	}

	/** @return 과제 ID */
	long insert(long activityId, AssignmentInput input) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO assignments (activity_id, allow_text_yn, allow_file_yn, late_policy, late_until)
				VALUES (:activity, :text, :file, :latePolicy, :lateUntil)
				""")
			.param("activity", activityId)
			.param("text", input.allowText())
			.param("file", input.allowFile())
			.param("latePolicy", input.latePolicy())
			.param("lateUntil", input.lateUntil())
			.update(key, "assignment_id");
		return key.getKey().longValue();
	}

	/**
	 * 읽은 수정 번호가 지금 값과 같을 때만 바꾸고 번호를 올린다. 행을 잠그므로 같은 transaction의 나머지 변경도 직렬화된다.
	 *
	 * @return 바꿨는지. 다른 수정이 먼저 저장됐으면 false
	 */
	boolean update(long assignmentId, long revision, AssignmentInput input) {
		return this.jdbc.sql("""
				UPDATE assignments
				   SET allow_text_yn = :text, allow_file_yn = :file, late_policy = :latePolicy, late_until = :lateUntil,
				       revision_no = revision_no + 1
				 WHERE assignment_id = :id AND revision_no = :revision
				""")
			.param("text", input.allowText())
			.param("file", input.allowFile())
			.param("latePolicy", input.latePolicy())
			.param("lateUntil", input.lateUntil())
			.param("id", assignmentId)
			.param("revision", revision)
			.update() > 0;
	}

	/** @return 성적 항목 ID */
	long insertGradeItem(long courseId, long activityId, long userId, String title, AssignmentInput input) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO grade_items (course_id, activity_id, item_name, item_type, max_score, score_visibility, release_at,
				                         created_by_user_id)
				VALUES (:course, :activity, :name, 'assignment', :max,
				        CASE WHEN :released THEN 'immediate' ELSE 'hidden' END,
				        CASE WHEN :released THEN CURRENT_TIMESTAMP(3) END, :user)
				""")
			.param("course", courseId)
			.param("activity", activityId)
			.param("name", title)
			.param("max", input.maxScore())
			.param("released", input.scoresReleased())
			.param("user", userId)
			.update(key, "grade_item_id");
		return key.getKey().longValue();
	}

	/** 과제 항목의 이름, 배점, 공개를 바꾸고 수정 번호를 올린다. 부르는 쪽이 항목 행을 먼저 잠근다({@link GradebookRecords#lock}). */
	void updateGradeItem(long gradeItemId, String title, AssignmentInput input) {
		this.jdbc.sql("""
				UPDATE grade_items
				   SET item_name = :name, max_score = :max,
				       score_visibility = CASE WHEN :released THEN 'immediate' ELSE 'hidden' END,
				       release_at = CASE WHEN :released THEN COALESCE(release_at, CURRENT_TIMESTAMP(3)) END,
				       revision_no = revision_no + 1
				 WHERE grade_item_id = :id
				""")
			.param("name", title)
			.param("max", input.maxScore())
			.param("released", input.scoresReleased())
			.param("id", gradeItemId)
			.update();
	}

	private static final String SELECT = """
			SELECT a.assignment_id, a.activity_id, a.allow_text_yn, a.allow_file_yn, a.late_policy, a.late_until, a.revision_no,
			       g.grade_item_id, g.max_score, g.score_visibility, g.release_at
			  FROM assignments a JOIN grade_items g ON g.activity_id = a.activity_id
			""";

	private static Settings settings(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Settings(rs.getLong("assignment_id"), rs.getLong("activity_id"), rs.getBoolean("allow_text_yn"),
				rs.getBoolean("allow_file_yn"), rs.getString("late_policy"), rs.getObject("late_until", LocalDateTime.class),
				rs.getLong("revision_no"), rs.getLong("grade_item_id"), rs.getBigDecimal("max_score"),
				rs.getString("score_visibility"), rs.getObject("release_at", LocalDateTime.class));
	}

}
