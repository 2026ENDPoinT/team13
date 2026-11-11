package com.stackcorp.astra.assessment;

import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stackcorp.astra.assessment.Survey.Settings;
import com.stackcorp.astra.assessment.SurveyInput.QuestionInput;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 설문 정의, 참여 원장, 응답 저장부의 SQL.
 * <p>
 * 참여 원장({@code survey_participations})과 응답 저장부({@code survey_responses}, {@code survey_answers},
 * {@code survey_answer_options})는 서로를 가리키지 않는다. 익명 설문의 응답 행에는 사용자 ID가 없고 시각은 일 단위다(SC-05-A,
 * ADR-0022). 응답 쪽을 읽는 메서드는 집계와 답변 문구만 돌려주고, 응답 세트 ID나 행 순서를 밖으로 내보내지 않는다.
 */
@Repository
class SurveyRecords {

	private static final String SETTINGS = """
			SELECT survey_id, activity_id, title, description_text, response_mode, open_at, close_at, result_visible_yn,
			       result_release_at, result_min_cell_k, revision_no
			  FROM surveys
			""";

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	SurveyRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<Settings> find(long surveyId) {
		return this.jdbc.sql(SETTINGS + " WHERE survey_id = :id")
			.param("id", surveyId)
			.query(SurveyRecords::settings)
			.optional();
	}

	List<Settings> ofActivities(Collection<Long> activityIds) {
		if (activityIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql(SETTINGS + " WHERE activity_id IN (:ids)")
			.param("ids", activityIds)
			.query(SurveyRecords::settings)
			.list();
	}

	/**
	 * 알림마당 설문 중 그 사용자가 대상이고 시작한 설문. 마감 오름차순, 같으면 ID 순서다(EP-0163).
	 * 대상 행이 없는 설문은 아무에게도 보이지 않는다.
	 */
	List<Settings> community(Audience audience, LocalDateTime now, int offset, int limit) {
		return this.jdbc.sql(SETTINGS + " s" + COMMUNITY + " ORDER BY s.close_at, s.survey_id LIMIT :limit OFFSET :offset")
			.params(audience.params())
			.param("now", now)
			.param("limit", limit)
			.param("offset", offset)
			.query(SurveyRecords::settings)
			.list();
	}

	int communityCount(Audience audience, LocalDateTime now) {
		return this.jdbc.sql("SELECT COUNT(*) FROM surveys s" + COMMUNITY)
			.params(audience.params())
			.param("now", now)
			.query(Integer.class)
			.single();
	}

	boolean targets(long surveyId, Audience audience) {
		return this.jdbc.sql("SELECT COUNT(*) FROM survey_targets t WHERE t.survey_id = :survey AND " + TARGET_MATCH)
			.params(audience.params())
			.param("survey", surveyId)
			.query(Integer.class)
			.single() > 0;
	}

	// 대상 구분: all(로그인한 모든 사용자), student(학생), staff(교수, 조교, 직원), grade(그 학년의 학생). 그 밖의 값은 아무와도 맞지 않는다.
	private static final String TARGET_MATCH = """
			(t.target_type = 'all'
			   OR (t.target_type = 'student' AND :student)
			   OR (t.target_type = 'staff' AND :staff)
			   OR (t.target_type = 'grade' AND :student AND t.target_value = :grade))
			""";

	private static final String COMMUNITY = """
			 WHERE s.activity_id IS NULL AND s.open_at <= :now
			   AND EXISTS (SELECT 1 FROM survey_targets t WHERE t.survey_id = s.survey_id AND
			""" + TARGET_MATCH + ")";

	/** @return 설문 ID */
	long insert(long activityId, long createdBy, SurveyInput input) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO surveys (activity_id, title, response_mode, allow_retake_yn, result_visible_yn, result_release_at,
				                     created_by_user_id)
				VALUES (:activity, :title, :mode, 0, :visible, :releaseAt, :user)
				""")
			.param("activity", activityId)
			.param("title", input.activity().title())
			.param("mode", input.anonymous() == null || input.anonymous() ? Survey.ANONYMOUS : Survey.NAMED)
			.param("visible", input.resultsVisible())
			.param("releaseAt", input.resultsReleaseAt())
			.param("user", createdBy)
			.update(key, "survey_id");
		return key.getKey().longValue();
	}

	/**
	 * 읽은 수정 번호가 지금 값과 같을 때만 바꾼다. 이 UPDATE가 설문 행의 배타 잠금을 잡으므로, 같은 설문의 완료(공유 잠금)와 차례로
	 * 실행된다.
	 *
	 * @return 바꿨는지
	 */
	boolean update(long surveyId, long revision, SurveyInput input, String responseMode) {
		return this.jdbc.sql("""
				UPDATE surveys
				   SET title = :title, response_mode = :mode, result_visible_yn = :visible, result_release_at = :releaseAt,
				       revision_no = revision_no + 1
				 WHERE survey_id = :id AND revision_no = :revision
				""")
			.param("title", input.activity().title())
			.param("mode", responseMode)
			.param("visible", input.resultsVisible())
			.param("releaseAt", input.resultsReleaseAt())
			.param("id", surveyId)
			.param("revision", revision)
			.update() > 0;
	}

	/** 완료 transaction의 첫 문장. 설문 행을 공유 잠금으로 잡아 문항 교체, 응답 방식 변경과 겹치지 않게 한다. */
	void lockShared(long surveyId) {
		this.jdbc.sql("SELECT survey_id FROM surveys WHERE survey_id = :id FOR SHARE")
			.param("id", surveyId)
			.query(Long.class)
			.single();
	}

	/** 문항 번호 순서의 문항과 보기 번호 순서의 보기. */
	List<Question> questions(long surveyId) {
		Map<Long, List<Option>> options = new HashMap<>();
		this.jdbc.sql("""
				SELECT o.survey_question_id, o.survey_question_option_id, o.option_no, o.option_text
				  FROM survey_question_options o JOIN survey_questions q ON q.survey_question_id = o.survey_question_id
				 WHERE q.survey_id = :survey
				 ORDER BY o.survey_question_id, o.option_no
				""").param("survey", surveyId).query((rs) -> {
					options.computeIfAbsent(rs.getLong(1), (k) -> new ArrayList<>())
						.add(new Option(rs.getLong(2), rs.getInt(3), rs.getString(4)));
				});
		return this.jdbc.sql("""
				SELECT survey_question_id, question_no, question_type, question_text, is_mandatory_yn, min_select, max_select
				  FROM survey_questions
				 WHERE survey_id = :survey
				 ORDER BY question_no
				""")
			.param("survey", surveyId)
			.query((rs, n) -> new Question(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getString(4),
					rs.getBoolean(5), rs.getObject(6, Integer.class), rs.getObject(7, Integer.class),
					options.getOrDefault(rs.getLong(1), List.of())))
			.list();
	}

	Map<Long, Integer> questionCounts(Collection<Long> surveyIds) {
		return countBySurvey("SELECT survey_id, COUNT(*) FROM survey_questions WHERE survey_id IN (:ids) GROUP BY survey_id",
				surveyIds);
	}

	/** 문항과 보기를 모두 지우고 새로 넣는다. 참여가 없을 때만 부른다. 부르는 쪽이 설문 행의 배타 잠금을 잡고 있다. */
	void replaceQuestions(long surveyId, List<QuestionInput> questions) {
		this.jdbc.sql("""
				DELETE o FROM survey_question_options o
				  JOIN survey_questions q ON q.survey_question_id = o.survey_question_id
				 WHERE q.survey_id = :survey
				""").param("survey", surveyId).update();
		this.jdbc.sql("DELETE FROM survey_questions WHERE survey_id = :survey").param("survey", surveyId).update();
		insertQuestions(surveyId, questions);
	}

	void insertQuestions(long surveyId, List<QuestionInput> questions) {
		for (int i = 0; i < questions.size(); i++) {
			QuestionInput question = questions.get(i);
			GeneratedKeyHolder key = new GeneratedKeyHolder();
			this.jdbc.sql("""
					INSERT INTO survey_questions (survey_id, question_no, page_no, question_type, question_text, is_mandatory_yn,
					                              min_select, max_select, sort_order)
					VALUES (:survey, :no, 1, :type, :text, :required, :min, :max, :no)
					""")
				.param("survey", surveyId)
				.param("no", i + 1)
				.param("type", question.type())
				.param("text", question.text())
				.param("required", question.required())
				.param("min", question.minSelect())
				.param("max", question.maxSelect())
				.update(key, "survey_question_id");
			long questionId = key.getKey().longValue();
			for (int o = 0; o < question.options().size(); o++) {
				this.jdbc.sql("""
						INSERT INTO survey_question_options (survey_question_id, option_no, option_text, sort_order)
						VALUES (:question, :no, :text, :no)
						""")
					.param("question", questionId)
					.param("no", o + 1)
					.param("text", question.options().get(o))
					.update();
			}
		}
	}

	// ---- 참여 원장 ----

	Optional<Participation> participation(long surveyId, long userId) {
		return this.jdbc.sql("""
				SELECT state, revision, completion_key
				  FROM survey_participations
				 WHERE survey_id = :survey AND user_id = :user
				""")
			.param("survey", surveyId)
			.param("user", userId)
			.query((rs, n) -> new Participation(rs.getString(1), rs.getInt(2), rs.getString(3)))
			.optional();
	}

	boolean hasParticipations(long surveyId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM survey_participations WHERE survey_id = :survey AND state = 'completed'")
			.param("survey", surveyId)
			.query(Integer.class)
			.single() > 0;
	}

	/** 설문별 완료한 사람 수. */
	Map<Long, Integer> participantCounts(Collection<Long> surveyIds) {
		return countBySurvey("""
				SELECT survey_id, COUNT(*) FROM survey_participations
				 WHERE survey_id IN (:ids) AND state = 'completed' GROUP BY survey_id
				""", surveyIds);
	}

	/** 그 사용자의 설문별 참여 상태. 참여가 없는 설문은 넣지 않는다. */
	Map<Long, String> statesOf(long userId, Collection<Long> surveyIds) {
		Map<Long, String> states = new HashMap<>();
		if (surveyIds.isEmpty()) {
			return states;
		}
		this.jdbc.sql("SELECT survey_id, state FROM survey_participations WHERE user_id = :user AND survey_id IN (:ids)")
			.param("user", userId)
			.param("ids", surveyIds)
			.query((rs) -> {
				states.put(rs.getLong(1), rs.getString(2));
			});
		return states;
	}

	/**
	 * 그 활동들 가운데 이 사용자가 완료한 강의실 설문의 활동 ID. 참여 원장과 설문 정의만 읽고 응답 저장부는 읽지 않으므로 익명 설문의 응답과
	 * 사용자를 잇지 않는다(ADR-0022). 활동 수와 관계없이 쿼리 한 번이다.
	 */
	List<Long> completedActivities(long userId, Collection<Long> activityIds) {
		if (activityIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("""
				SELECT s.activity_id FROM surveys s
				  JOIN survey_participations p ON p.survey_id = s.survey_id
				 WHERE s.activity_id IN (:ids) AND p.user_id = :user AND p.state = 'completed'
				""").param("ids", activityIds).param("user", userId).query(Long.class).list();
	}

	/**
	 * 완료한 참여를 넣는다. 응답 세트를 가리키지 않는다(current_response_id는 NULL).
	 *
	 * @param completionKey 익명 설문의 Idempotency-Key(SC-05-A). 실명 설문이면 null(공용 원장이 가진다)
	 * @param stamp 생성·완료 일시. 익명 설문이면 일 단위로 자른 값이다
	 * @throws org.springframework.dao.DuplicateKeyException 같은 사람의 참여가 이미 있을 때
	 */
	void insertParticipation(long surveyId, long userId, String completionKey, LocalDateTime stamp) {
		this.jdbc.sql("""
				INSERT INTO survey_participations (survey_id, user_id, state, completed_at, revision, completion_key, created_at)
				VALUES (:survey, :user, 'completed', :stamp, 1, :key, :stamp)
				""")
			.param("survey", surveyId)
			.param("user", userId)
			.param("key", completionKey)
			.param("stamp", stamp)
			.update();
	}

	// ---- 응답 저장부 ----

	/**
	 * 응답 세트 하나를 넣는다. 응답 세트, 답변, 고른 보기의 행 ID는 모두 {@link SecureRandom}으로 만든 임의 값이다(M4, ADR-0022).
	 * AUTO_INCREMENT 순번은 입력 순서라서, 같은 transaction에 넣는 참여 행(사용자가 있다)의 순서와 맞춰 익명 응답의 주인을 찾을 수
	 * 있기 때문이다. 63bit 값이 겹칠 확률은 무시할 만하고, 겹치면 PK 제약이 이 완료를 rollback한다.
	 *
	 * @param respondentUserId 실명 설문의 응답자. 익명 설문이면 null
	 * @param stamp 제출·생성 일시. 익명 설문이면 일 단위로 자른 값이다
	 */
	void insertResponse(long surveyId, Long respondentUserId, LocalDateTime stamp, List<Answer> answers) {
		long responseId = randomId();
		this.jdbc.sql("""
				INSERT INTO survey_responses (survey_response_id, survey_id, revision_no, respondent_user_id, is_current_yn,
				                              submitted_at, created_at)
				VALUES (:id, :survey, 1, :respondent, 1, :stamp, :stamp)
				""")
			.param("id", responseId)
			.param("survey", surveyId)
			.param("respondent", respondentUserId)
			.param("stamp", stamp)
			.update();
		for (Answer answer : answers) {
			long answerId = randomId();
			this.jdbc.sql("""
					INSERT INTO survey_answers (survey_answer_id, survey_response_id, survey_question_id, answer_text,
					                            created_at)
					VALUES (:id, :response, :question, :text, :stamp)
					""")
				.param("id", answerId)
				.param("response", responseId)
				.param("question", answer.questionId())
				.param("text", answer.text())
				.param("stamp", stamp)
				.update();
			for (long optionId : answer.optionIds()) {
				this.jdbc.sql("""
						INSERT INTO survey_answer_options (survey_answer_option_id, survey_answer_id,
						                                   survey_question_option_id, created_at)
						VALUES (:id, :answer, :option, :stamp)
						""")
					.param("id", randomId())
					.param("answer", answerId)
					.param("option", optionId)
					.param("stamp", stamp)
					.update();
			}
		}
	}

	private static long randomId() {
		return RANDOM.nextLong(1, Long.MAX_VALUE);
	}

	/** 현재 응답 세트 수. */
	int respondentCount(long surveyId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM survey_responses WHERE survey_id = :survey AND is_current_yn = 1")
			.param("survey", surveyId)
			.query(Integer.class)
			.single();
	}

	/** 문항별 답한 응답 세트 수. */
	Map<Long, Integer> answerCounts(long surveyId) {
		Map<Long, Integer> counts = new HashMap<>();
		this.jdbc.sql("""
				SELECT a.survey_question_id, COUNT(*)
				  FROM survey_answers a JOIN survey_responses r ON r.survey_response_id = a.survey_response_id
				 WHERE r.survey_id = :survey AND r.is_current_yn = 1
				 GROUP BY a.survey_question_id
				""").param("survey", surveyId).query((rs) -> {
					counts.put(rs.getLong(1), rs.getInt(2));
				});
		return counts;
	}

	/** 보기별 선택 수. 아무도 고르지 않은 보기는 넣지 않는다. */
	Map<Long, Integer> optionCounts(long surveyId) {
		Map<Long, Integer> counts = new HashMap<>();
		this.jdbc.sql("""
				SELECT ao.survey_question_option_id, COUNT(*)
				  FROM survey_answer_options ao
				  JOIN survey_answers a ON a.survey_answer_id = ao.survey_answer_id
				  JOIN survey_responses r ON r.survey_response_id = a.survey_response_id
				 WHERE r.survey_id = :survey AND r.is_current_yn = 1
				 GROUP BY ao.survey_question_option_id
				""").param("survey", surveyId).query((rs) -> {
					counts.put(rs.getLong(1), rs.getInt(2));
				});
		return counts;
	}

	/**
	 * 서술형 답변 한 쪽. 입력 순서가 참여 순서와 맞물리지 않도록 행 ID가 아니라 답변 문구 순서로 내보낸다. 같은 문구끼리만 ID로
	 * 순서를 정한다.
	 */
	List<String> texts(long surveyId, long questionId, int offset, int limit) {
		return this.jdbc.sql("""
				SELECT a.answer_text
				  FROM survey_answers a JOIN survey_responses r ON r.survey_response_id = a.survey_response_id
				 WHERE r.survey_id = :survey AND r.is_current_yn = 1 AND a.survey_question_id = :question
				   AND a.answer_text IS NOT NULL
				 ORDER BY a.answer_text, a.survey_answer_id
				 LIMIT :limit OFFSET :offset
				""")
			.param("survey", surveyId)
			.param("question", questionId)
			.param("limit", limit)
			.param("offset", offset)
			.query(String.class)
			.list();
	}

	private Map<Long, Integer> countBySurvey(String sql, Collection<Long> surveyIds) {
		Map<Long, Integer> counts = new LinkedHashMap<>();
		if (surveyIds.isEmpty()) {
			return counts;
		}
		this.jdbc.sql(sql).param("ids", surveyIds).query((rs) -> {
			counts.put(rs.getLong(1), rs.getInt(2));
		});
		return counts;
	}

	private static Settings settings(ResultSet rs, int n) throws SQLException {
		return new Settings(rs.getLong("survey_id"), rs.getObject("activity_id", Long.class), rs.getString("title"),
				rs.getString("description_text"), rs.getString("response_mode"),
				rs.getObject("open_at", LocalDateTime.class), rs.getObject("close_at", LocalDateTime.class),
				rs.getBoolean("result_visible_yn"), rs.getObject("result_release_at", LocalDateTime.class),
				rs.getInt("result_min_cell_k"), rs.getLong("revision_no"));
	}

	/**
	 * @param type single, multiple, scale, essay
	 * @param minSelect multiple만 값이 있다
	 */
	record Question(long questionId, int questionNo, String type, String text, boolean required, Integer minSelect,
			Integer maxSelect, List<Option> options) {
	}

	record Option(long optionId, int optionNo, String text) {
	}

	/**
	 * 참여 원장 한 행. 응답 세트를 가리키는 값은 읽지 않는다.
	 *
	 * @param completionKey 익명 설문 완료의 Idempotency-Key. 실명 설문이면 null
	 */
	record Participation(String state, int revision, String completionKey) {
	}

	/**
	 * 검사를 마친 답 하나. 보기는 오름차순이다.
	 *
	 * @param text 서술형 답변. 보기 문항이면 null
	 */
	record Answer(long questionId, List<Long> optionIds, String text) {
	}

	/**
	 * 알림마당 설문 대상 판정에 쓰는 사용자 속성. session의 역할과 identity의 학년에서만 만든다(SC-02).
	 *
	 * @param grade 학년. 없으면 null
	 */
	record Audience(boolean student, boolean staff, Integer grade) {

		Map<String, Object> params() {
			Map<String, Object> params = new HashMap<>();
			params.put("student", this.student);
			params.put("staff", this.staff);
			params.put("grade", this.grade == null ? null : this.grade.toString());
			return params;
		}

	}

}
