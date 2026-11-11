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

import com.stackcorp.astra.assessment.Question.Option;
import com.stackcorp.astra.assessment.Quiz.Settings;
import com.stackcorp.astra.assessment.QuizInput.OptionInput;
import com.stackcorp.astra.assessment.QuizInput.QuestionInput;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * quizzes, 그 퀴즈의 grade_items 행, 문항(quiz_questions)과 보기(quiz_question_options). 퀴즈 하나에 성적 항목 하나다.
 */
@Repository
class QuizRecords {

	private final JdbcClient jdbc;

	QuizRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Settings> find(long quizId) {
		return this.jdbc.sql(SELECT + " WHERE q.quiz_id = :id").param("id", quizId).query(QuizRecords::settings).optional();
	}

	List<Settings> ofActivities(Collection<Long> activityIds) {
		if (activityIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql(SELECT + " WHERE q.activity_id IN (:ids)")
			.param("ids", activityIds)
			.query(QuizRecords::settings)
			.list();
	}

	/**
	 * 응시 시작이 퀴즈 행에 공유 잠금을 건다. 문항 수정은 같은 행에 배타 잠금을 걸므로, 문항을 바꾸는 transaction과 첫 응시를 만드는
	 * transaction은 차례로 지나간다.
	 */
	void lockShared(long quizId) {
		this.jdbc.sql("SELECT quiz_id FROM quizzes WHERE quiz_id = :id FOR SHARE").param("id", quizId).query(Long.class).single();
	}

	/** @return 퀴즈 ID */
	long insert(long activityId, String examType, QuizInput input) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO quizzes (activity_id, exam_type, duration_minutes, attempt_limit, shuffle_yn, answer_release_yn)
				VALUES (:activity, :type, :duration, :limit, :shuffle, :answers)
				""")
			.param("activity", activityId)
			.param("type", examType)
			.param("duration", input.timeLimitMinutes())
			.param("limit", input.attemptLimit())
			.param("shuffle", input.shuffle())
			.param("answers", input.answersVisible())
			.update(key, "quiz_id");
		return key.getKey().longValue();
	}

	/**
	 * 읽은 수정 번호가 지금 값과 같을 때만 설정을 바꾸고 번호를 올린다. 행을 배타 잠금하므로 같은 transaction의 나머지 변경도 직렬화된다.
	 *
	 * @return 바꿨는지. 다른 수정이 먼저 저장됐으면 false
	 */
	boolean updateSettings(long quizId, long revision, QuizInput input) {
		return this.jdbc.sql("""
				UPDATE quizzes
				   SET duration_minutes = :duration, attempt_limit = :limit, shuffle_yn = :shuffle,
				       answer_release_yn = :answers, revision_no = revision_no + 1
				 WHERE quiz_id = :id AND revision_no = :revision
				""")
			.param("duration", input.timeLimitMinutes())
			.param("limit", input.attemptLimit())
			.param("shuffle", input.shuffle())
			.param("answers", input.answersVisible())
			.param("id", quizId)
			.param("revision", revision)
			.update() > 0;
	}

	/** 문항을 바꿀 때 수정 번호만 올린다. 조건과 결과는 {@link #updateSettings}와 같다. */
	boolean bumpRevision(long quizId, long revision) {
		return this.jdbc.sql("UPDATE quizzes SET revision_no = revision_no + 1 WHERE quiz_id = :id AND revision_no = :revision")
			.param("id", quizId)
			.param("revision", revision)
			.update() > 0;
	}

	void insertGradeItem(long courseId, long activityId, long userId, String title, BigDecimal maxScore,
			LocalDateTime releaseAt) {
		this.jdbc.sql("""
				INSERT INTO grade_items (course_id, activity_id, item_name, item_type, max_score, score_visibility, release_at,
				                         created_by_user_id)
				VALUES (:course, :activity, :name, 'quiz', :max,
				        CASE WHEN :releaseAt IS NULL THEN 'hidden' ELSE 'after_release' END, :releaseAt, :user)
				""")
			.param("course", courseId)
			.param("activity", activityId)
			.param("name", title)
			.param("max", maxScore)
			.param("releaseAt", releaseAt)
			.param("user", userId)
			.update();
	}

	void updateGradeItem(long gradeItemId, String title, BigDecimal maxScore, LocalDateTime releaseAt) {
		this.jdbc.sql("""
				UPDATE grade_items
				   SET item_name = :name, max_score = :max,
				       score_visibility = CASE WHEN :releaseAt IS NULL THEN 'hidden' ELSE 'after_release' END,
				       release_at = :releaseAt
				 WHERE grade_item_id = :id
				""")
			.param("name", title)
			.param("max", maxScore)
			.param("releaseAt", releaseAt)
			.param("id", gradeItemId)
			.update();
	}

	void updateMaxScore(long gradeItemId, BigDecimal maxScore) {
		this.jdbc.sql("UPDATE grade_items SET max_score = :max WHERE grade_item_id = :id")
			.param("max", maxScore)
			.param("id", gradeItemId)
			.update();
	}

	/** 이미 준 점수 가운데 max를 넘는 것이 있는지. */
	boolean hasScoreAbove(long gradeItemId, BigDecimal max) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM grade_scores WHERE grade_item_id = :id AND score > :max)")
			.param("id", gradeItemId)
			.param("max", max)
			.query(Boolean.class)
			.single();
	}

	/** 응시가 하나라도 있는지. 있으면 문항을 바꾸지 않는다. */
	boolean hasAttempts(long quizId) {
		return this.jdbc.sql("SELECT EXISTS (SELECT 1 FROM quiz_attempts WHERE quiz_id = :id)")
			.param("id", quizId)
			.query(Boolean.class)
			.single();
	}

	/** 출제 순서대로의 문항과 보기. */
	List<Question> questions(long quizId) {
		Map<Long, List<Option>> options = new HashMap<>();
		this.jdbc.sql("""
				SELECT o.quiz_question_id, o.quiz_question_option_id, o.option_no, o.option_text, o.is_correct_yn
				  FROM quiz_question_options o JOIN quiz_questions q ON q.quiz_question_id = o.quiz_question_id
				 WHERE q.quiz_id = :quiz
				 ORDER BY o.quiz_question_id, o.option_no
				""").param("quiz", quizId).query((rs) -> {
			options.computeIfAbsent(rs.getLong(1), (k) -> new ArrayList<>())
				.add(new Option(rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getBoolean(5)));
		});
		return this.jdbc.sql("""
				SELECT quiz_question_id, question_no, question_type, question_text, score, explanation
				  FROM quiz_questions WHERE quiz_id = :quiz ORDER BY question_no
				""")
			.param("quiz", quizId)
			.query((rs, n) -> new Question(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
					rs.getBigDecimal(5), rs.getString(6), List.copyOf(options.getOrDefault(rs.getLong(1), List.of()))))
			.list();
	}

	/** 퀴즈별 문항 수. */
	Map<Long, Integer> questionCounts(Collection<Long> quizIds) {
		Map<Long, Integer> counts = new LinkedHashMap<>();
		if (quizIds.isEmpty()) {
			return counts;
		}
		this.jdbc.sql("SELECT quiz_id, COUNT(*) FROM quiz_questions WHERE quiz_id IN (:ids) GROUP BY quiz_id")
			.param("ids", quizIds)
			.query((rs) -> {
				counts.put(rs.getLong(1), rs.getInt(2));
			});
		return counts;
	}

	/**
	 * 문항과 보기를 모두 지우고 새로 쓴다. 응시가 없을 때만 부른다. 답안이 문항을 참조하지 않으므로 지워도 되는 행만 지운다.
	 */
	void replaceQuestions(long quizId, List<QuestionInput> questions) {
		this.jdbc.sql("""
				DELETE o FROM quiz_question_options o JOIN quiz_questions q ON q.quiz_question_id = o.quiz_question_id
				 WHERE q.quiz_id = :quiz
				""").param("quiz", quizId).update();
		this.jdbc.sql("DELETE FROM quiz_questions WHERE quiz_id = :quiz").param("quiz", quizId).update();
		int no = 0;
		for (QuestionInput question : questions) {
			no++;
			GeneratedKeyHolder key = new GeneratedKeyHolder();
			this.jdbc.sql("""
					INSERT INTO quiz_questions (quiz_id, question_no, question_type, question_text, score, explanation, sort_order)
					VALUES (:quiz, :no, :type, :text, :points, :explanation, :no)
					""")
				.param("quiz", quizId)
				.param("no", no)
				.param("type", question.type())
				.param("text", question.text())
				.param("points", question.points())
				.param("explanation", question.explanation())
				.update(key, "quiz_question_id");
			long questionId = key.getKey().longValue();
			int optionNo = 0;
			for (OptionInput option : question.options()) {
				optionNo++;
				this.jdbc.sql("""
						INSERT INTO quiz_question_options (quiz_question_id, option_no, option_text, is_correct_yn, sort_order)
						VALUES (:question, :no, :text, :correct, :no)
						""")
					.param("question", questionId)
					.param("no", optionNo)
					.param("text", option.text())
					.param("correct", option.correct())
					.update();
			}
		}
	}

	private static final String SELECT = """
			SELECT q.quiz_id, q.activity_id, g.course_id, q.exam_type, q.duration_minutes, q.attempt_limit, q.shuffle_yn,
			       q.answer_release_yn, q.revision_no, g.grade_item_id, g.max_score, g.score_visibility, g.release_at
			  FROM quizzes q JOIN grade_items g ON g.activity_id = q.activity_id
			""";

	private static Settings settings(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Settings(rs.getLong("quiz_id"), rs.getLong("activity_id"), rs.getLong("course_id"),
				rs.getString("exam_type"), rs.getObject("duration_minutes", Integer.class),
				rs.getObject("attempt_limit", Integer.class), rs.getBoolean("shuffle_yn"),
				rs.getBoolean("answer_release_yn"), rs.getLong("revision_no"), rs.getLong("grade_item_id"),
				rs.getBigDecimal("max_score"), rs.getString("score_visibility"),
				rs.getObject("release_at", LocalDateTime.class));
	}

}
