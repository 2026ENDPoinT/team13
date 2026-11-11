package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 응시(quiz_attempts), 답안(quiz_attempt_answers, quiz_attempt_answer_options), 퀴즈 성적(grade_scores).
 * <p>
 * 잠금 순서는 하나다. 응시 행은 기본 키 오름차순으로 잠그고({@link #lockAll}, {@link #lock}), 성적 행은 그 뒤에 쓴다. 기본 키로만 잠가
 * 이웃 행에 gap 잠금을 걸지 않는다.
 */
@Repository
class AttemptRecords {

	static final String IN_PROGRESS = "in_progress";

	static final String SUBMITTED = "submitted";

	static final String GRADED = "graded";

	private final JdbcClient jdbc;

	AttemptRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Attempt> find(long attemptId) {
		return this.jdbc.sql(SELECT + " WHERE quiz_attempt_id = :id").param("id", attemptId).query(AttemptRecords::attempt).optional();
	}

	/** 그 학생의 응시 전체. 회차 순서. 잠그지 않는다. */
	List<Attempt> ofUser(long quizId, long userId) {
		return this.jdbc.sql(SELECT + " WHERE quiz_id = :quiz AND user_id = :user ORDER BY attempt_no")
			.param("quiz", quizId)
			.param("user", userId)
			.query(AttemptRecords::attempt)
			.list();
	}

	/** 여러 퀴즈에 걸친 그 학생의 응시. 회차 순서. */
	Map<Long, List<Attempt>> ofUser(Collection<Long> quizIds, long userId) {
		Map<Long, List<Attempt>> result = new HashMap<>();
		if (quizIds.isEmpty()) {
			return result;
		}
		this.jdbc.sql(SELECT + " WHERE quiz_id IN (:quizzes) AND user_id = :user ORDER BY attempt_no")
			.param("quizzes", quizIds)
			.param("user", userId)
			.query(AttemptRecords::attempt)
			.list()
			.forEach((a) -> result.computeIfAbsent(a.quizId(), (k) -> new ArrayList<>()).add(a));
		return result;
	}

	/**
	 * 진행 중 응시를 공유 잠금으로 읽는다. 방금 commit된 응시는 snapshot 밖이라 잠금 읽기로 본다. 동시에 시작한 요청끼리 같은 행을 공유
	 * 잠금으로만 읽으므로 서로 기다리지 않는다.
	 */
	Optional<Attempt> inFlightShared(long quizId, long userId) {
		return this.jdbc.sql(SELECT + " WHERE quiz_id = :quiz AND active_attempt_user_id = :user FOR SHARE")
			.param("quiz", quizId)
			.param("user", userId)
			.query(AttemptRecords::attempt)
			.optional();
	}

	/** 응시 하나를 배타 잠금으로 읽는다. 답안 저장이 쓴다. */
	Optional<Attempt> lock(long attemptId) {
		return this.jdbc.sql(SELECT + " WHERE quiz_attempt_id = :id FOR UPDATE")
			.param("id", attemptId)
			.query(AttemptRecords::attempt)
			.optional();
	}

	/**
	 * 한 학생의 응시를 기본 키 오름차순으로 모두 배타 잠금한다. 종료, 채점, 성적 반영은 모두 이것으로 시작한다. 같은 학생의 다른 회차
	 * 채점과 종료가 같은 순서로 잠그므로 서로 deadlock 되지 않고, 가장 높은 점수를 잠근 값으로 계산한다. 기본 키로만 잠가 gap 잠금을 걸지
	 * 않는다.
	 * <p>
	 * known은 transaction 밖에서 {@link #ofUser(long, long)}로 읽은 ID다. REPEATABLE READ transaction에서는 첫 일반 읽기가
	 * snapshot 시점이 되므로, 이 잠금이 transaction의 첫 읽기여야 뒤의 답안 읽기가 잠금 이후의 값을 본다. 잠근 뒤 ID를 다시 읽어 그 사이에
	 * 생긴 회차도 잠근다. 새 회차의 ID는 언제나 더 크므로 오름차순이 유지된다.
	 */
	List<Attempt> lockAll(long quizId, long userId, Collection<Long> known) {
		List<Attempt> locked = new ArrayList<>();
		List<Long> pending = known.stream().sorted().toList();
		if (pending.isEmpty()) {
			return locked;
		}
		while (!pending.isEmpty()) {
			locked.addAll(this.jdbc.sql(SELECT + " WHERE quiz_attempt_id IN (:ids) ORDER BY quiz_attempt_id FOR UPDATE")
				.param("ids", pending)
				.query(AttemptRecords::attempt)
				.list());
			long last = locked.isEmpty() ? 0 : locked.getLast().attemptId();
			pending = this.jdbc.sql("""
					SELECT quiz_attempt_id FROM quiz_attempts
					 WHERE quiz_id = :quiz AND user_id = :user AND quiz_attempt_id > :last ORDER BY quiz_attempt_id
					""").param("quiz", quizId).param("user", userId).param("last", last).query(Long.class).list();
		}
		return locked;
	}

	/**
	 * 새 응시. 같은 학생의 진행 중 응시가 있거나 같은 회차가 있으면 유일 제약으로 실패한다.
	 *
	 * @return 응시 ID
	 */
	long insert(long quizId, long userId, int attemptNo, LocalDateTime startedAt, LocalDateTime deadlineAt,
			String sessionKey) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO quiz_attempts (quiz_id, user_id, attempt_no, status, started_at, deadline_at, session_lock_key,
				                           lock_acquired_at)
				VALUES (:quiz, :user, :no, 'in_progress', :startedAt, :deadline, :session, :startedAt)
				""")
			.param("quiz", quizId)
			.param("user", userId)
			.param("no", attemptNo)
			.param("startedAt", startedAt)
			.param("deadline", deadlineAt)
			.param("session", sessionKey)
			.update(key, "quiz_attempt_id");
		return key.getKey().longValue();
	}

	/** 다른 session이 진행 중 응시를 이어받는다. 마감 일시는 바꾸지 않는다. */
	void takeOver(long attemptId, String sessionKey, LocalDateTime now) {
		this.jdbc.sql("""
				UPDATE quiz_attempts SET session_lock_key = :session, lock_acquired_at = :now
				 WHERE quiz_attempt_id = :id AND status = 'in_progress'
				""").param("session", sessionKey).param("now", now).param("id", attemptId).update();
	}

	/**
	 * 진행 중 응시를 닫는다. 상태가 이미 바뀌었으면 아무것도 하지 않는다.
	 *
	 * @param totalScore 수동 채점할 답안이 없으면 총점, 있으면 null
	 * @return 닫았는지
	 */
	boolean finish(long attemptId, String status, LocalDateTime submittedAt, String reason, BigDecimal totalScore,
			LocalDateTime gradedAt) {
		return this.jdbc.sql("""
				UPDATE quiz_attempts
				   SET status = :status, submitted_at = :submittedAt, finish_reason = :reason, total_score = :total,
				       graded_at = :gradedAt
				 WHERE quiz_attempt_id = :id AND status = 'in_progress'
				""")
			.param("status", status)
			.param("submittedAt", submittedAt)
			.param("reason", reason)
			.param("total", totalScore)
			.param("gradedAt", gradedAt)
			.param("id", attemptId)
			.update() > 0;
	}

	/** 수동 채점을 마친 응시의 총점. 제출한 응시에만 쓴다. */
	void grade(long attemptId, BigDecimal totalScore, LocalDateTime gradedAt) {
		this.jdbc.sql("""
				UPDATE quiz_attempts SET status = 'graded', total_score = :total, graded_at = :gradedAt
				 WHERE quiz_attempt_id = :id AND status IN ('submitted', 'graded')
				""").param("total", totalScore).param("gradedAt", gradedAt).param("id", attemptId).update();
	}

	/** 응시의 답안. 문항 ID로. */
	Map<Long, Answer> answers(long attemptId) {
		Map<Long, List<Long>> options = new HashMap<>();
		this.jdbc.sql("""
				SELECT o.quiz_attempt_answer_id, o.quiz_question_option_id
				  FROM quiz_attempt_answer_options o
				  JOIN quiz_attempt_answers a ON a.quiz_attempt_answer_id = o.quiz_attempt_answer_id
				 WHERE a.quiz_attempt_id = :attempt
				 ORDER BY o.quiz_question_option_id
				""").param("attempt", attemptId).query((rs) -> {
			options.computeIfAbsent(rs.getLong(1), (k) -> new ArrayList<>()).add(rs.getLong(2));
		});
		Map<Long, Answer> answers = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT quiz_attempt_answer_id, quiz_question_id, answer_text, revision, saved_at, is_correct_yn, awarded_score,
				       grader_comment, grading_status, graded_at
				  FROM quiz_attempt_answers WHERE quiz_attempt_id = :attempt
				""").param("attempt", attemptId).query((rs) -> {
			long id = rs.getLong(1);
			answers.put(rs.getLong(2),
					new Answer(id, rs.getLong(2), rs.getString(3), rs.getInt(4), rs.getObject(5, LocalDateTime.class),
							rs.getObject(6, Boolean.class), rs.getBigDecimal(7), rs.getString(8), rs.getString(9),
							rs.getObject(10, LocalDateTime.class), List.copyOf(options.getOrDefault(id, List.of()))));
		});
		return answers;
	}

	/** @return 답안 ID */
	long insertAnswer(long attemptId, long questionId, String text, int revision, LocalDateTime savedAt) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO quiz_attempt_answers (quiz_attempt_id, quiz_question_id, answer_text, revision, saved_at)
				VALUES (:attempt, :question, :text, :revision, :savedAt)
				""")
			.param("attempt", attemptId)
			.param("question", questionId)
			.param("text", text)
			.param("revision", revision)
			.param("savedAt", savedAt)
			.update(key, "quiz_attempt_answer_id");
		return key.getKey().longValue();
	}

	void updateAnswer(long answerId, String text, LocalDateTime savedAt) {
		this.jdbc.sql("""
				UPDATE quiz_attempt_answers SET answer_text = :text, revision = revision + 1, saved_at = :savedAt
				 WHERE quiz_attempt_answer_id = :id
				""").param("text", text).param("savedAt", savedAt).param("id", answerId).update();
	}

	void replaceOptions(long answerId, Collection<Long> optionIds) {
		this.jdbc.sql("DELETE FROM quiz_attempt_answer_options WHERE quiz_attempt_answer_id = :id").param("id", answerId).update();
		for (long optionId : optionIds) {
			this.jdbc.sql("""
					INSERT INTO quiz_attempt_answer_options (quiz_attempt_answer_id, quiz_question_option_id)
					VALUES (:answer, :option)
					""").param("answer", answerId).param("option", optionId).update();
		}
	}

	/** 종료할 때의 채점 결과와 담당자의 수동 채점. */
	void scoreAnswer(long answerId, Boolean correct, BigDecimal awarded, String gradingStatus, String comment,
			LocalDateTime gradedAt, Long graderId) {
		this.jdbc.sql("""
				UPDATE quiz_attempt_answers
				   SET is_correct_yn = :correct, awarded_score = :awarded, grading_status = :status, grader_comment = :comment,
				       graded_at = :gradedAt, graded_by_user_id = :grader
				 WHERE quiz_attempt_answer_id = :id
				""")
			.param("correct", correct)
			.param("awarded", awarded)
			.param("status", gradingStatus)
			.param("comment", comment)
			.param("gradedAt", gradedAt)
			.param("grader", graderId)
			.param("id", answerId)
			.update();
	}

	/**
	 * 퀴즈 성적 항목의 학생 점수. 채점을 마친 응시 가운데 가장 높은 총점이다. 같은 학생의 응시를 모두 잠근 뒤에 부른다.
	 *
	 * @param graderId 수동 채점으로 바뀌었으면 그 담당자, 자동 채점이면 null
	 */
	void putGrade(long gradeItemId, long userId, BigDecimal score, LocalDateTime gradedAt, Long graderId) {
		this.jdbc.sql("""
				INSERT INTO grade_scores (grade_item_id, user_id, score, graded_at, graded_by_user_id)
				VALUES (:item, :user, :score, :gradedAt, :grader) AS new
				ON DUPLICATE KEY UPDATE score = new.score, graded_at = new.graded_at, graded_by_user_id = new.graded_by_user_id
				""")
			.param("item", gradeItemId)
			.param("user", userId)
			.param("score", score)
			.param("gradedAt", gradedAt)
			.param("grader", graderId)
			.update();
	}

	Optional<BigDecimal> grade(long gradeItemId, long userId) {
		return this.jdbc.sql("SELECT score FROM grade_scores WHERE grade_item_id = :item AND user_id = :user AND score IS NOT NULL")
			.param("item", gradeItemId)
			.param("user", userId)
			.query(BigDecimal.class)
			.optional();
	}

	/** 퀴즈의 응시. 응시 ID 순서이고 after 다음부터 limit개다(keyset). */
	List<Attempt> page(long quizId, long after, int limit) {
		return this.jdbc.sql(SELECT + " WHERE quiz_id = :quiz AND quiz_attempt_id > :after ORDER BY quiz_attempt_id LIMIT :limit")
			.param("quiz", quizId)
			.param("after", after)
			.param("limit", limit)
			.query(AttemptRecords::attempt)
			.list();
	}

	/** 응시별 수동 채점 대기 답안 수. */
	Map<Long, Integer> pendingCounts(Collection<Long> attemptIds) {
		Map<Long, Integer> counts = new HashMap<>();
		if (attemptIds.isEmpty()) {
			return counts;
		}
		this.jdbc.sql("""
				SELECT quiz_attempt_id, COUNT(*) FROM quiz_attempt_answers
				 WHERE quiz_attempt_id IN (:ids) AND grading_status = 'pending' GROUP BY quiz_attempt_id
				""").param("ids", attemptIds).query((rs) -> {
			counts.put(rs.getLong(1), rs.getInt(2));
		});
		return counts;
	}

	/** 퀴즈별 응시 수. */
	Map<Long, Counts> counts(Collection<Long> quizIds) {
		Map<Long, Counts> counts = new HashMap<>();
		if (quizIds.isEmpty()) {
			return counts;
		}
		this.jdbc.sql("""
				SELECT quiz_id, SUM(status = 'in_progress'), SUM(status <> 'in_progress'), SUM(status = 'submitted')
				  FROM quiz_attempts WHERE quiz_id IN (:ids) GROUP BY quiz_id
				""").param("ids", quizIds).query((rs) -> {
			counts.put(rs.getLong(1), new Counts(rs.getInt(2), rs.getInt(3), rs.getInt(4)));
		});
		return counts;
	}

	/**
	 * 마감 일시가 지났는데 아직 진행 중인 응시. 잠그지 않는다. worker가 하나씩 잠그고 상태를 다시 확인한다.
	 */
	List<Expired> expired(LocalDateTime now, int limit) {
		return this.jdbc.sql("""
				SELECT quiz_attempt_id, quiz_id, user_id FROM quiz_attempts
				 WHERE status = 'in_progress' AND deadline_at <= :now
				 ORDER BY deadline_at, quiz_attempt_id LIMIT :limit
				""")
			.param("now", now)
			.param("limit", limit)
			.query((rs, n) -> new Expired(rs.getLong(1), rs.getLong(2), rs.getLong(3)))
			.list();
	}

	private static final String SELECT = """
			SELECT quiz_attempt_id, quiz_id, user_id, attempt_no, status, started_at, deadline_at, submitted_at, finish_reason,
			       total_score, graded_at, session_lock_key
			  FROM quiz_attempts
			""";

	private static Attempt attempt(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Attempt(rs.getLong("quiz_attempt_id"), rs.getLong("quiz_id"), rs.getLong("user_id"),
				rs.getInt("attempt_no"), rs.getString("status"), rs.getObject("started_at", LocalDateTime.class),
				rs.getObject("deadline_at", LocalDateTime.class), rs.getObject("submitted_at", LocalDateTime.class),
				rs.getString("finish_reason"), rs.getBigDecimal("total_score"),
				rs.getObject("graded_at", LocalDateTime.class), rs.getString("session_lock_key"));
	}

	/**
	 * @param status in_progress, submitted(수동 채점 대기), graded
	 * @param finishReason submitted(학생 제출), deadline(마감 일시에 닫음). 진행 중이면 null
	 * @param sessionKey 지금 이 응시를 이어 쓰는 session
	 */
	record Attempt(long attemptId, long quizId, long userId, int attemptNo, String status, LocalDateTime startedAt,
			LocalDateTime deadlineAt, LocalDateTime submittedAt, String finishReason, BigDecimal totalScore,
			LocalDateTime gradedAt, String sessionKey) {

		boolean inProgress() {
			return IN_PROGRESS.equals(this.status);
		}

		/** 지금 답안을 받는지. 진행 중이고 DB 시계로 마감 전이어야 한다. */
		boolean accepting(LocalDateTime now) {
			return inProgress() && now.isBefore(this.deadlineAt);
		}

	}

	/**
	 * @param correct 자동 채점 결과. 단답형이나 종료 전이면 null
	 * @param gradingStatus 종료 전이면 null, auto, pending, manual
	 * @param optionIds 고른 보기
	 */
	record Answer(long answerId, long questionId, String text, int revision, LocalDateTime savedAt, Boolean correct,
			BigDecimal awarded, String comment, String gradingStatus, LocalDateTime gradedAt, List<Long> optionIds) {
	}

	/**
	 * @param inProgress 진행 중 응시 수
	 * @param finished 종료한 응시 수
	 * @param awaitingGrading 수동 채점을 기다리는 응시 수
	 */
	record Counts(int inProgress, int finished, int awaitingGrading) {
	}

	record Expired(long attemptId, long quizId, long userId) {
	}

}
