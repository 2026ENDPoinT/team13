package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 개인 제출물(assignment_submissions), 제출 버전(assignment_submission_versions), 점수(grade_scores).
 * 버전은 쓰고 나면 바꾸지 않는다. 제출물 행은 현재 버전 번호와 임시저장만 바뀐다.
 */
@Repository
class SubmissionRecords {

	private final JdbcClient jdbc;

	SubmissionRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Submission> find(long assignmentId, long userId) {
		return this.jdbc.sql(SUBMISSION + " WHERE assignment_id = :assignment AND user_id = :user AND owner_type = 'user'")
			.param("assignment", assignmentId)
			.param("user", userId)
			.query(SubmissionRecords::submission)
			.optional();
	}

	Optional<Submission> find(long submissionId) {
		return this.jdbc.sql(SUBMISSION + " WHERE assignment_submission_id = :id AND owner_type = 'user'")
			.param("id", submissionId)
			.query(SubmissionRecords::submission)
			.optional();
	}

	/** 최종 제출한 버전이 있는 제출물. 임시저장만 있는 행은 넣지 않는다. */
	List<Submission> submitted(long assignmentId) {
		return this.jdbc.sql(SUBMISSION + """
				 WHERE assignment_id = :assignment AND owner_type = 'user' AND current_version_no > 0
				 ORDER BY submitted_at, assignment_submission_id
				""").param("assignment", assignmentId).query(SubmissionRecords::submission).list();
	}

	/** 과제별 최종 제출 수와 그중 지각 수. */
	Map<Long, Counts> counts(Collection<Long> assignmentIds) {
		Map<Long, Counts> counts = new HashMap<>();
		if (assignmentIds.isEmpty()) {
			return counts;
		}
		this.jdbc.sql("""
				SELECT assignment_id, COUNT(*) AS submitted, SUM(is_late) AS late FROM assignment_submissions
				 WHERE assignment_id IN (:ids) AND owner_type = 'user' AND current_version_no > 0
				 GROUP BY assignment_id
				""").param("ids", assignmentIds).query((rs) -> {
			counts.put(rs.getLong("assignment_id"), new Counts(rs.getInt("submitted"), rs.getInt("late")));
		});
		return counts;
	}

	/** 한 학생의 과제별 제출물. */
	Map<Long, Submission> ofUser(long userId, Collection<Long> assignmentIds) {
		Map<Long, Submission> result = new HashMap<>();
		if (assignmentIds.isEmpty()) {
			return result;
		}
		this.jdbc.sql(SUBMISSION + " WHERE user_id = :user AND owner_type = 'user' AND assignment_id IN (:ids)")
			.param("user", userId)
			.param("ids", assignmentIds)
			.query(SubmissionRecords::submission)
			.list()
			.forEach((s) -> result.put(s.assignmentId(), s));
		return result;
	}

	/**
	 * 제출물 행이 없으면 만든다. 동시에 만들면 유일 제약 (assignment_id, user_id)으로 하나만 남는다(SC-05).
	 * 잠금을 오래 잡지 않도록 제출 transaction 밖에서 따로 commit한다.
	 */
	void ensure(long assignmentId, long userId) {
		if (find(assignmentId, userId).isPresent()) {
			return;
		}
		try {
			this.jdbc.sql("INSERT INTO assignment_submissions (assignment_id, owner_type, user_id) VALUES (:assignment, 'user', :user)")
				.param("assignment", assignmentId)
				.param("user", userId)
				.update();
		}
		catch (DuplicateKeyException ex) {
			// 다른 요청이 먼저 만들었다.
		}
	}

	/** 제출물 행을 잠근다. 같은 학생의 같은 과제 제출은 여기서 차례로 처리된다. */
	Submission lock(long assignmentId, long userId) {
		return this.jdbc
			.sql(SUBMISSION + " WHERE assignment_id = :assignment AND user_id = :user AND owner_type = 'user' FOR UPDATE")
			.param("assignment", assignmentId)
			.param("user", userId)
			.query(SubmissionRecords::submission)
			.single();
	}

	/** 그 사용자가 그 Idempotency-Key로 만든 버전. */
	Optional<Version> byKey(long userId, String key) {
		return this.jdbc.sql(VERSION + " WHERE submitted_by_user_id = :user AND idempotency_key = :key")
			.param("user", userId)
			.param("key", key)
			.query(SubmissionRecords::version)
			.optional();
	}

	Optional<Version> version(long submissionId, int versionNo) {
		return this.jdbc.sql(VERSION + " WHERE assignment_submission_id = :id AND version_no = :no")
			.param("id", submissionId)
			.param("no", versionNo)
			.query(SubmissionRecords::version)
			.optional();
	}

	/** 새 버전이 먼저 나온다. */
	List<Version> versions(long submissionId) {
		return this.jdbc.sql(VERSION + " WHERE assignment_submission_id = :id ORDER BY version_no DESC")
			.param("id", submissionId)
			.query(SubmissionRecords::version)
			.list();
	}

	/** @throws DuplicateKeyException 같은 사용자가 같은 Idempotency-Key를 이미 썼을 때 */
	void insertVersion(long submissionId, int versionNo, String body, Long attachmentGroupId, LocalDateTime submittedAt,
			long userId, boolean late, String key, String fingerprint) {
		this.jdbc.sql("""
				INSERT INTO assignment_submission_versions (assignment_submission_id, version_no, body_text, attachment_group_id,
				                                            submitted_at, submitted_by_user_id, is_late, idempotency_key,
				                                            request_fingerprint)
				VALUES (:submission, :no, :body, :group, :at, :user, :late, :key, :fingerprint)
				""")
			.param("submission", submissionId)
			.param("no", versionNo)
			.param("body", body)
			.param("group", attachmentGroupId)
			.param("at", submittedAt)
			.param("user", userId)
			.param("late", late)
			.param("key", key)
			.param("fingerprint", fingerprint)
			.update();
	}

	/** 새 버전을 현재 버전으로 삼고 임시저장을 비운다. */
	void advance(long submissionId, int versionNo, LocalDateTime submittedAt, boolean late) {
		this.jdbc.sql("""
				UPDATE assignment_submissions
				   SET current_version_no = :no, first_submitted_at = COALESCE(first_submitted_at, :at), submitted_at = :at,
				       is_late = :late, draft_body_text = NULL, draft_saved_at = NULL
				 WHERE assignment_submission_id = :id
				""")
			.param("no", versionNo)
			.param("at", submittedAt)
			.param("late", late)
			.param("id", submissionId)
			.update();
	}

	void saveDraft(long assignmentId, long userId, String body) {
		this.jdbc.sql("""
				UPDATE assignment_submissions SET draft_body_text = :body, draft_saved_at = CURRENT_TIMESTAMP(3)
				 WHERE assignment_id = :assignment AND user_id = :user AND owner_type = 'user'
				""").param("body", body).param("assignment", assignmentId).param("user", userId).update();
	}

	Optional<Grade> grade(long gradeItemId, long userId) {
		return this.jdbc.sql(GRADE + " WHERE grade_item_id = :item AND user_id = :user")
			.param("item", gradeItemId)
			.param("user", userId)
			.query(SubmissionRecords::grade)
			.optional();
	}

	/** 학생별 점수. */
	Map<Long, Grade> grades(long gradeItemId) {
		Map<Long, Grade> grades = new HashMap<>();
		this.jdbc.sql(GRADE + " WHERE grade_item_id = :item")
			.param("item", gradeItemId)
			.query((rs) -> {
				grades.put(rs.getLong("user_id"), grade(rs, 0));
			});
		return grades;
	}

	/** 첨부 그룹을 가진 제출 버전의 제출자와 과제 활동. 제출 버전의 첨부가 아니면 비어 있다. */
	List<GroupOwner> ownersOfGroup(long attachmentGroupId) {
		return this.jdbc.sql("""
				SELECT s.user_id, a.activity_id
				  FROM assignment_submission_versions v
				  JOIN assignment_submissions s ON s.assignment_submission_id = v.assignment_submission_id
				  JOIN assignments a ON a.assignment_id = s.assignment_id
				 WHERE v.attachment_group_id = :group AND s.owner_type = 'user'
				""")
			.param("group", attachmentGroupId)
			.query((rs, n) -> new GroupOwner(rs.getLong("user_id"), rs.getLong("activity_id")))
			.list();
	}

	private static final String SUBMISSION = """
			SELECT assignment_submission_id, assignment_id, user_id, current_version_no, first_submitted_at, submitted_at,
			       is_late, draft_body_text, draft_saved_at
			  FROM assignment_submissions
			""";

	private static final String VERSION = """
			SELECT assignment_submission_id, version_no, body_text, attachment_group_id, submitted_at, is_late,
			       request_fingerprint
			  FROM assignment_submission_versions
			""";

	private static final String GRADE = """
			SELECT user_id, score, score_comment, graded_at FROM grade_scores
			""";

	private static Submission submission(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Submission(rs.getLong("assignment_submission_id"), rs.getLong("assignment_id"), rs.getLong("user_id"),
				rs.getInt("current_version_no"), rs.getObject("first_submitted_at", LocalDateTime.class),
				rs.getObject("submitted_at", LocalDateTime.class), rs.getBoolean("is_late"), rs.getString("draft_body_text"),
				rs.getObject("draft_saved_at", LocalDateTime.class));
	}

	private static Version version(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Version(rs.getLong("assignment_submission_id"), rs.getInt("version_no"), rs.getString("body_text"),
				rs.getObject("attachment_group_id", Long.class), rs.getObject("submitted_at", LocalDateTime.class),
				rs.getBoolean("is_late"), rs.getString("request_fingerprint"));
	}

	private static Grade grade(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Grade(rs.getBigDecimal("score"), rs.getString("score_comment"),
				rs.getObject("graded_at", LocalDateTime.class));
	}

	/**
	 * @param currentVersion 최종 제출한 버전 수. 0이면 제출하지 않았다
	 */
	record Submission(long submissionId, long assignmentId, long userId, int currentVersion,
			LocalDateTime firstSubmittedAt, LocalDateTime submittedAt, boolean late, String draftBody,
			LocalDateTime draftSavedAt) {

		boolean submitted() {
			return this.currentVersion > 0;
		}

		/** 서버 접수 시각과 정책으로 정한 상태: not_submitted, submitted, late. 임시저장은 제출로 치지 않는다. */
		String status() {
			return !submitted() ? "not_submitted" : this.late ? "late" : "submitted";
		}

	}

	record Version(long submissionId, int versionNo, String body, Long attachmentGroupId, LocalDateTime submittedAt,
			boolean late, String fingerprint) {
	}

	record Grade(BigDecimal score, String feedback, LocalDateTime gradedAt) {
	}

	record Counts(int submitted, int late) {
	}

	record GroupOwner(long userId, long activityId) {
	}

}
