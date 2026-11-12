package com.stackcorp.astra.assessment;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.AfterEach;

import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;

/**
 * 퀴즈 테스트의 공통 준비. 과제 테스트와 같은 사람들(담당자, 수강생 둘, 과목 밖 사용자)을 쓰고, 퀴즈 시계를 테스트가 움직인다.
 * 기본 퀴즈는 단일 선택(2점), 복수 선택(3점), 단답형(5점) 세 문항이다.
 */
abstract class QuizTestSupport extends AssessmentTestSupport {

	static final String QUESTIONS = """
			[{"type":"single","text":"1+1은?","points":2,"explanation":"덧셈","options":[{"text":"2","correct":true},{"text":"3"}]},
			 {"type":"multiple","text":"짝수를 모두 고르시오","points":3,"explanation":"2와 4","options":[{"text":"2","correct":true},{"text":"3"},{"text":"4","correct":true}]},
			 {"type":"short","text":"정규화를 한 줄로 설명하시오","points":5}]
			""";

	@Autowired
	TestQuizClock clock;

	@AfterEach
	void releaseClock() {
		this.clock.release();
	}

	/** 담당자가 기본 문항으로 온라인 퀴즈를 만든다. @return 응답 본문 */
	JsonNode createQuiz(String extraFields) throws Exception {
		String body = """
				{"weekNo":2,"title":"퀴즈 1","description":"3장 범위","closeAt":"2999-01-01T00:00:00"%s,"questions":%s}
				""".formatted(extraFields.isEmpty() ? "" : "," + extraFields, QUESTIONS);
		return ApiClient.json(this.professor.postJson(quizzes(this.course), body).andExpect(status().isCreated()));
	}

	long quiz(String extraFields) throws Exception {
		return createQuiz(extraFields).get("quiz").get("quizId").asLong();
	}

	/** 담당자가 본 문항. index는 출제 순서(0부터). */
	JsonNode question(long quizId, int index) throws Exception {
		return ApiClient.json(this.professor.get(quiz(this.course, quizId))).get("questions").get(index);
	}

	long questionId(long quizId, int index) throws Exception {
		return question(quizId, index).get("questionId").asLong();
	}

	long optionId(long quizId, int question, int option) throws Exception {
		return question(quizId, question).get("options").get(option).get("optionId").asLong();
	}

	long revision(long quizId) throws Exception {
		return ApiClient.json(this.professor.get(quiz(this.course, quizId))).get("revision").asLong();
	}

	/** 학생이 응시를 시작한다. @return 응시 ID */
	long start(Client client, long quizId) throws Exception {
		return ApiClient.json(client.post(attempts(this.course, quizId))).get("attemptId").asLong();
	}

	long activityOfQuiz(long quizId) {
		return this.jdbc.sql("SELECT activity_id FROM quizzes WHERE quiz_id = :id").param("id", quizId).query(Long.class).single();
	}

	/** 응시 기간을 바꾼다. 활동 기간 컬럼은 초 단위다. */
	void window(long quizId, LocalDateTime openAt, LocalDateTime closeAt) {
		this.jdbc.sql("UPDATE learning_activities SET open_at = :open, close_at = :close WHERE activity_id = :id")
			.param("open", openAt)
			.param("close", closeAt)
			.param("id", activityOfQuiz(quizId))
			.update();
	}

	void deadline(long attemptId, LocalDateTime deadline) {
		this.jdbc.sql("UPDATE quiz_attempts SET deadline_at = :deadline WHERE quiz_attempt_id = :id")
			.param("deadline", deadline)
			.param("id", attemptId)
			.update();
	}

	long attemptCount(long quizId, long userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM quiz_attempts WHERE quiz_id = :quiz AND user_id = :user")
			.param("quiz", quizId)
			.param("user", userId)
			.query(Long.class)
			.single();
	}

	long auditCount(String operation, long objectId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = :op AND object_id = :id")
			.param("op", operation)
			.param("id", objectId)
			.query(Long.class)
			.single();
	}

	static String choice(int revision, long... optionIds) {
		StringBuilder ids = new StringBuilder();
		for (long id : optionIds) {
			ids.append(ids.isEmpty() ? "" : ",").append(id);
		}
		return "{\"revision\":" + revision + ",\"optionIds\":[" + ids + "]}";
	}

	static String text(int revision, String text) {
		return "{\"revision\":" + revision + ",\"text\":\"" + text + "\"}";
	}

	static String quizzes(long courseId) {
		return "/api/courses/" + courseId + "/quizzes";
	}

	static String quiz(long courseId, long quizId) {
		return quizzes(courseId) + "/" + quizId;
	}

	static String attempts(long courseId, long quizId) {
		return quiz(courseId, quizId) + "/attempts";
	}

	static String attempt(long courseId, long quizId, long attemptId) {
		return attempts(courseId, quizId) + "/" + attemptId;
	}

	static String answer(long courseId, long quizId, long attemptId, long questionId) {
		return attempt(courseId, quizId, attemptId) + "/answers/" + questionId;
	}

	static String submitPath(long courseId, long quizId, long attemptId) {
		return attempt(courseId, quizId, attemptId) + "/submit";
	}

}
