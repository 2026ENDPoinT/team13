package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 성적 설정(grade_settings), 성적 항목(grade_items), 학생 성적(grade_scores). 항목 행을 바꾸거나 그 항목의 점수를 쓰는 transaction은
 * 먼저 항목 행을 잠근다({@link #lock}). 같은 항목의 점수 쓰기와 배점 변경은 이 잠금으로 차례로 처리된다.
 */
@Repository
class GradebookRecords {

	private final JdbcClient jdbc;

	GradebookRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 과목의 성적 설정. 행이 없으면 기본값(평가 활동 공개)과 수정 번호 0이다. */
	Settings settings(long courseId) {
		return this.jdbc.sql("SELECT activities_visible_yn, revision_no FROM grade_settings WHERE course_id = :course")
			.param("course", courseId)
			.query((rs, n) -> new Settings(rs.getBoolean("activities_visible_yn"), rs.getLong("revision_no")))
			.optional()
			.orElse(Settings.DEFAULT);
	}

	/** 과목들 가운데 평가 활동을 비공개로 둔 과목. */
	List<Long> hiddenActivityCourses(Collection<Long> courseIds) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc
			.sql("SELECT course_id FROM grade_settings WHERE course_id IN (:courses) AND activities_visible_yn = 0")
			.param("courses", courseIds)
			.query(Long.class)
			.list();
	}

	/** 처음 저장. 다른 요청이 먼저 만들었으면 유일 제약 uq_grade_settings_course로 실패한다. */
	void insertSettings(long courseId, boolean activitiesVisible) {
		this.jdbc.sql("INSERT INTO grade_settings (course_id, activities_visible_yn) VALUES (:course, :visible)")
			.param("course", courseId)
			.param("visible", activitiesVisible)
			.update();
	}

	/** @return 읽은 수정 번호가 지금 값과 같아 바꿨는지 */
	boolean updateSettings(long courseId, long revision, boolean activitiesVisible) {
		return this.jdbc.sql("""
				UPDATE grade_settings SET activities_visible_yn = :visible, revision_no = revision_no + 1
				 WHERE course_id = :course AND revision_no = :revision
				""")
			.param("visible", activitiesVisible)
			.param("course", courseId)
			.param("revision", revision)
			.update() > 0;
	}

	/** 과목의 성적 항목 전체. 정렬 순서, ID 순서다. */
	List<GradeItem> items(long courseId) {
		return this.jdbc.sql(ITEM + " WHERE course_id = :course ORDER BY sort_order, grade_item_id")
			.param("course", courseId)
			.query(GradebookRecords::item)
			.list();
	}

	/** 그 과목의 성적 항목. 다른 과목의 항목이면 비어 있다(SC-03). */
	Optional<GradeItem> find(long courseId, long gradeItemId) {
		return this.jdbc.sql(ITEM + " WHERE course_id = :course AND grade_item_id = :id")
			.param("course", courseId)
			.param("id", gradeItemId)
			.query(GradebookRecords::item)
			.optional();
	}

	/** 항목 행을 잠그고 지금 값을 읽는다. */
	GradeItem lock(long gradeItemId) {
		return this.jdbc.sql(ITEM + " WHERE grade_item_id = :id FOR UPDATE")
			.param("id", gradeItemId)
			.query(GradebookRecords::item)
			.single();
	}

	int countManual(long courseId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM grade_items WHERE course_id = :course AND activity_id IS NULL")
			.param("course", courseId)
			.query(Integer.class)
			.single();
	}

	/** @return 성적 항목 ID */
	long insertManual(long courseId, long userId, String name, BigDecimal maxScore, BigDecimal weight) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO grade_items (course_id, item_name, item_type, max_score, weight, score_visibility, created_by_user_id)
				VALUES (:course, :name, 'manual', :max, :weight, 'hidden', :user)
				""")
			.param("course", courseId)
			.param("name", name)
			.param("max", maxScore)
			.param("weight", weight)
			.param("user", userId)
			.update(key, "grade_item_id");
		return key.getKey().longValue();
	}

	/** 잠근 직접 입력 항목의 이름, 배점, 비율을 바꾸고 수정 번호를 올린다. */
	void updateManual(long gradeItemId, String name, BigDecimal maxScore, BigDecimal weight) {
		this.jdbc.sql("""
				UPDATE grade_items SET item_name = :name, max_score = :max, weight = :weight, revision_no = revision_no + 1
				 WHERE grade_item_id = :id
				""")
			.param("name", name)
			.param("max", maxScore)
			.param("weight", weight)
			.param("id", gradeItemId)
			.update();
	}

	/** 잠근 항목의 비율만 바꾸고 수정 번호를 올린다. 과제 항목도 비율은 성적부가 정한다. */
	void updateWeight(long gradeItemId, BigDecimal weight) {
		this.jdbc.sql("UPDATE grade_items SET weight = :weight, revision_no = revision_no + 1 WHERE grade_item_id = :id")
			.param("weight", weight)
			.param("id", gradeItemId)
			.update();
	}

	/**
	 * 잠근 직접 입력 항목을 공개하거나 거둔다. 공개는 즉시 공개(immediate)이고 이미 공개 중이면 처음 공개 일시를 그대로 둔다. 거두면 공개
	 * 일시를 지운다. 과제 항목의 {@code AssignmentRecords.updateGradeItem}과 같은 규칙이다.
	 */
	void setReleased(long gradeItemId, boolean released) {
		this.jdbc.sql("""
				UPDATE grade_items
				   SET score_visibility = CASE WHEN :released THEN 'immediate' ELSE 'hidden' END,
				       release_at = CASE WHEN :released THEN COALESCE(release_at, CURRENT_TIMESTAMP(3)) END,
				       revision_no = revision_no + 1
				 WHERE grade_item_id = :id
				""")
			.param("released", released)
			.param("id", gradeItemId)
			.update();
	}

	/** 점수나 의견이 남아 있는 학생 성적이 있는지. 비운 행(점수와 의견이 모두 없는 행)은 세지 않는다. */
	boolean hasRecordedScores(long gradeItemId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM grade_scores
				                WHERE grade_item_id = :id AND (score IS NOT NULL OR score_comment IS NOT NULL))
				""").param("id", gradeItemId).query(Boolean.class).single();
	}

	/** 이미 준 점수 가운데 max를 넘는 것이 있는지. */
	boolean hasScoreAbove(long gradeItemId, BigDecimal max) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM grade_scores WHERE grade_item_id = :id AND score > :max)")
			.param("id", gradeItemId)
			.param("max", max)
			.query(Boolean.class)
			.single();
	}

	/** 잠근 직접 입력 항목과 그 항목의 비운 학생 성적 행을 지운다. 점수나 의견이 남은 행이 없음을 먼저 확인한다. */
	void deleteManual(long gradeItemId) {
		this.jdbc.sql("DELETE FROM grade_scores WHERE grade_item_id = :id").param("id", gradeItemId).update();
		this.jdbc.sql("DELETE FROM grade_items WHERE grade_item_id = :id AND activity_id IS NULL")
			.param("id", gradeItemId)
			.update();
	}

	/** 항목들의 학생별 성적. 학생 ID → 항목 ID → 성적. */
	Map<Long, Map<Long, Score>> scores(Collection<Long> gradeItemIds, Collection<Long> userIds) {
		Map<Long, Map<Long, Score>> result = new HashMap<>();
		if (gradeItemIds.isEmpty() || userIds.isEmpty()) {
			return result;
		}
		this.jdbc.sql(SCORE + " WHERE grade_item_id IN (:items) AND user_id IN (:users)")
			.param("items", gradeItemIds)
			.param("users", userIds)
			.query(GradebookRecords::score)
			.list()
			.forEach((s) -> result.computeIfAbsent(s.userId(), (k) -> new HashMap<>()).put(s.gradeItemId(), s));
		return result;
	}

	/** 한 항목의 학생별 성적. 항목 행을 잠근 transaction에서 부르면 그 사이 다른 쓰기가 없다. */
	Map<Long, Score> scoresOf(long gradeItemId, Collection<Long> userIds) {
		Map<Long, Score> result = new HashMap<>();
		if (userIds.isEmpty()) {
			return result;
		}
		this.jdbc.sql(SCORE + " WHERE grade_item_id = :item AND user_id IN (:users)")
			.param("item", gradeItemId)
			.param("users", userIds)
			.query(GradebookRecords::score)
			.list()
			.forEach((s) -> result.put(s.userId(), s));
		return result;
	}

	/** 항목별 점수를 준 학생 수. 점수를 비운 행은 세지 않는다. */
	Map<Long, Integer> gradedCounts(Collection<Long> gradeItemIds, Collection<Long> userIds) {
		Map<Long, Integer> counts = new HashMap<>();
		if (gradeItemIds.isEmpty() || userIds.isEmpty()) {
			return counts;
		}
		this.jdbc.sql("""
				SELECT grade_item_id, COUNT(*) AS graded FROM grade_scores
				 WHERE grade_item_id IN (:items) AND user_id IN (:users) AND score IS NOT NULL
				 GROUP BY grade_item_id
				""").param("items", gradeItemIds).param("users", userIds).query((rs) -> {
			counts.put(rs.getLong("grade_item_id"), rs.getInt("graded"));
		});
		return counts;
	}

	/**
	 * 점수와 의견을 쓰고 수정 번호를 올린다. 행이 없으면 만든다(수정 번호 1). 부르는 쪽이 항목 행을 잠그고 읽은 번호를 확인한 뒤 부른다.
	 * 점수가 null이면 비운다(미채점).
	 */
	void putScore(long gradeItemId, long userId, BigDecimal score, String feedback, long graderId) {
		this.jdbc.sql("""
				INSERT INTO grade_scores (grade_item_id, user_id, score, score_comment, graded_at, graded_by_user_id)
				VALUES (:item, :user, :score, :feedback, CURRENT_TIMESTAMP(3), :grader) AS new
				ON DUPLICATE KEY UPDATE score = new.score, score_comment = new.score_comment, graded_at = new.graded_at,
				                        graded_by_user_id = new.graded_by_user_id,
				                        revision_no = grade_scores.revision_no + 1
				""")
			.param("item", gradeItemId)
			.param("user", userId)
			.param("score", score)
			.param("feedback", feedback)
			.param("grader", graderId)
			.update();
	}

	/** 공개 일시가 since 뒤이고 now 이전인 항목 가운데 그 학생의 점수가 있는 것. 최근 공개순이다. */
	List<ReleasedCandidate> releasedCandidates(long userId, Collection<Long> courseIds, LocalDateTime since,
			LocalDateTime now, int limit) {
		if (courseIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("""
				SELECT g.grade_item_id, g.course_id, g.activity_id, g.item_name, g.score_visibility, g.release_at
				  FROM grade_items g JOIN grade_scores s ON s.grade_item_id = g.grade_item_id
				 WHERE g.course_id IN (:courses) AND s.user_id = :user AND s.score IS NOT NULL
				   AND g.release_at > :since AND g.release_at <= :now
				 ORDER BY g.release_at DESC, g.grade_item_id DESC
				 LIMIT :limit
				""")
			.param("courses", courseIds)
			.param("user", userId)
			.param("since", since)
			.param("now", now)
			.param("limit", limit)
			.query((rs, n) -> new ReleasedCandidate(rs.getLong("grade_item_id"), rs.getLong("course_id"),
					rs.getObject("activity_id", Long.class), rs.getString("item_name"), rs.getString("score_visibility"),
					rs.getObject("release_at", LocalDateTime.class)))
			.list();
	}

	private static final String ITEM = """
			SELECT grade_item_id, course_id, activity_id, item_name, item_type, max_score, weight, score_visibility, release_at,
			       revision_no
			  FROM grade_items
			""";

	private static final String SCORE = """
			SELECT grade_score_id, grade_item_id, user_id, score, score_comment, graded_at, revision_no FROM grade_scores
			""";

	private static GradeItem item(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new GradeItem(rs.getLong("grade_item_id"), rs.getLong("course_id"), rs.getObject("activity_id", Long.class),
				rs.getString("item_name"), rs.getString("item_type"), rs.getBigDecimal("max_score"),
				rs.getBigDecimal("weight"), rs.getString("score_visibility"),
				rs.getObject("release_at", LocalDateTime.class), rs.getLong("revision_no"));
	}

	private static Score score(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Score(rs.getLong("grade_score_id"), rs.getLong("grade_item_id"), rs.getLong("user_id"),
				rs.getBigDecimal("score"), rs.getString("score_comment"), rs.getObject("graded_at", LocalDateTime.class),
				rs.getLong("revision_no"));
	}

	/**
	 * @param revision 수정 번호. 행이 없으면 0으로 본다
	 */
	record Settings(boolean activitiesVisible, long revision) {

		static final Settings DEFAULT = new Settings(true, 0);

	}

	/**
	 * @param score 점수. 비웠으면 null
	 * @param version 수정 번호. 다음 쓰기 요청에 그대로 보낸다
	 */
	record Score(long scoreId, long gradeItemId, long userId, BigDecimal score, String feedback, LocalDateTime gradedAt,
			long version) {
	}

	record ReleasedCandidate(long gradeItemId, long courseId, Long activityId, String name, String scoreVisibility,
			LocalDateTime releaseAt) {
	}

}
