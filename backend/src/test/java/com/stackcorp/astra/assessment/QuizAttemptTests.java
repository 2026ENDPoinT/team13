package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

/**
 * 학생의 응시 시작, 자동저장, 제출. 마감 일시는 서버가 시작할 때 정하고, 모든 판정은 DB 시계로 한다(SC-05, SC-06, SEC-13).
 * 정답, 해설, 문항별 점수, 총점은 결과 공개 정책이 허락하기 전에는 학생 응답에 없다(SEC-14).
 */
class QuizAttemptTests extends QuizTestSupport {

	@Test
	void startFixesTheDeadlineAndRepeatedStartsReturnTheSameAttempt() throws Exception {
		LocalDateTime t = this.clock.freeze();
		long id = quiz("\"timeLimitMinutes\":30");
		window(id, null, t.plusHours(2));
		JsonNode first = ApiClient.json(this.student.post(attempts(this.course, id))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.attemptNo").value(1))
			.andExpect(jsonPath("$.status").value("in_progress")));
		assertThat(time(first, "startedAt")).isEqualTo(t);
		assertThat(time(first, "deadlineAt")).isEqualTo(t.plusMinutes(30));
		assertThat(time(first, "serverTime")).isEqualTo(t);

		this.clock.advance(Duration.ofMinutes(5));
		JsonNode again = ApiClient.json(this.student.post(attempts(this.course, id)).andExpect(status().isOk()));
		assertThat(again.get("attemptId").asLong()).isEqualTo(first.get("attemptId").asLong());
		assertThat(time(again, "deadlineAt")).isEqualTo(t.plusMinutes(30));
		assertThat(attemptCount(id, this.studentId)).isEqualTo(1);
		assertThat(auditCount("quiz.attempt.start", first.get("attemptId").asLong())).isEqualTo(1);
		this.student.get(quiz(this.course, id))
			.andExpect(jsonPath("$.canStart").value(true))
			.andExpect(jsonPath("$.attempts[0].status").value("in_progress"));

		// 마감 일시는 제한 시간과 응시 마감 가운데 이른 쪽이다. 제한 시간이 없으면 응시 마감이다.
		long capped = quiz("\"timeLimitMinutes\":30");
		window(capped, null, t.plusMinutes(10));
		assertThat(time(ApiClient.json(this.student.post(attempts(this.course, capped))), "deadlineAt"))
			.isEqualTo(t.plusMinutes(10));
		long untimed = quiz("");
		window(untimed, null, t.plusHours(3));
		assertThat(time(ApiClient.json(this.student.post(attempts(this.course, untimed))), "deadlineAt"))
			.isEqualTo(t.plusHours(3));
	}

	@Test
	void theWindowIsOpenFromOpenAtUntilJustBeforeCloseAt() throws Exception {
		LocalDateTime t = this.clock.freeze();
		long id = quiz("");
		window(id, t.plusSeconds(1), t.plusHours(1));
		this.student.get(quiz(this.course, id)).andExpect(status().isNotFound());
		this.student.post(attempts(this.course, id)).andExpect(status().isNotFound());
		this.clock.set(t.plusSeconds(1));
		this.student.post(attempts(this.course, id)).andExpect(status().isCreated());

		long closing = quiz("");
		window(closing, null, t.plusSeconds(2));
		this.clock.set(t.plusSeconds(2));
		this.classmate.get(quiz(this.course, closing))
			.andExpect(jsonPath("$.phase").value("closed"))
			.andExpect(jsonPath("$.canStart").value(false));
		this.classmate.post(attempts(this.course, closing))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("deadline_passed"));
		this.clock.set(t.plusSeconds(2).minusNanos(1_000_000));
		JsonNode started = ApiClient.json(this.classmate.post(attempts(this.course, closing)).andExpect(status().isCreated()));
		assertThat(time(started, "deadlineAt")).isEqualTo(t.plusSeconds(2));
		assertThat(attemptCount(closing, this.classmateId)).isEqualTo(1);
	}

	@Test
	void answersSaveOnlyBeforeTheDeadlineAndALateSubmitClosesWithTheSavedAnswers() throws Exception {
		LocalDateTime t = this.clock.freeze();
		long id = quiz("\"timeLimitMinutes\":30");
		window(id, null, t.plusHours(2));
		long a = start(this.student, id);
		LocalDateTime deadline = t.plusMinutes(30);
		long q0 = questionId(id, 0);
		long q1 = questionId(id, 1);

		this.clock.set(deadline.minusNanos(1_000_000));
		JsonNode saved = ApiClient.json(this.student.putJson(answer(this.course, id, a, q0), choice(0, optionId(id, 0, 0)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.questionId").value(q0))
			.andExpect(jsonPath("$.revision").value(1)));
		assertThat(time(saved, "savedAt")).isEqualTo(deadline.minusNanos(1_000_000));

		this.clock.set(deadline);
		this.student.putJson(answer(this.course, id, a, q1), choice(0, optionId(id, 1, 0)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("deadline_passed"));
		this.student.get(attempt(this.course, id, a))
			.andExpect(jsonPath("$.status").value("in_progress"))
			.andExpect(jsonPath("$.acceptingAnswers").value(false));

		JsonNode receipt = ApiClient.json(this.student.submit(submitPath(this.course, id, a), "late-1", "{}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(a))
			.andExpect(jsonPath("$.status").value("graded"))
			.andExpect(jsonPath("$.finishReason").value("deadline")));
		assertThat(time(receipt, "submittedAt")).isEqualTo(deadline);
		this.professor.get(attempt(this.course, id, a))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.studentId").value(this.studentId))
			.andExpect(jsonPath("$.totalScore").value(2.0))
			.andExpect(jsonPath("$.answers[0].correct").value(true))
			.andExpect(jsonPath("$.answers[0].awardedScore").value(2.0))
			.andExpect(jsonPath("$.answers[1].optionIds.length()").value(0))
			.andExpect(jsonPath("$.answers[1].awardedScore").value(0.0))
			.andExpect(jsonPath("$.answers[2].gradingStatus").value("auto"))
			.andExpect(jsonPath("$.answers[2].awardedScore").value(0.0));
		this.student.putJson(answer(this.course, id, a, q1), choice(0, optionId(id, 1, 0)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attempt_closed"));
	}

	@Test
	void autosaveTakesTheRevisionTheStudentReadAndOnlyValidAnswers() throws Exception {
		long id = quiz("");
		long other = quiz("");
		long a = start(this.student, id);
		long q0 = questionId(id, 0);
		long q1 = questionId(id, 1);
		long q2 = questionId(id, 2);
		long o00 = optionId(id, 0, 0);
		long o01 = optionId(id, 0, 1);
		long o10 = optionId(id, 1, 0);
		this.student.putJson(answer(this.course, id, a, q0), choice(0, o00)).andExpect(jsonPath("$.revision").value(1));
		this.student.putJson(answer(this.course, id, a, q0), choice(0, o01))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("answer_conflict"));
		this.student.putJson(answer(this.course, id, a, q0), choice(1, o01)).andExpect(jsonPath("$.revision").value(2));

		for (String bad : new String[] { choice(2, o10), choice(2, o00, o01), text(2, "x"),
				"{\"revision\":2,\"optionIds\":[],\"correct\":true}", "{\"optionIds\":[]}",
				"{\"revision\":-1,\"optionIds\":[]}" }) {
			this.student.putJson(answer(this.course, id, a, q0), bad).andExpect(status().isBadRequest());
		}
		this.student.putJson(answer(this.course, id, a, q1), choice(0, o10, o10)).andExpect(status().isBadRequest());
		this.student.putJson(answer(this.course, id, a, q2), choice(0, o00)).andExpect(status().isBadRequest());
		this.student.putJson(answer(this.course, id, a, questionId(other, 0)), choice(0, optionId(other, 0, 0)))
			.andExpect(status().isNotFound());
		this.student.putJson(answer(this.course, id, a, q2), text(0, "중복을 없애 이상 현상을 막는다"))
			.andExpect(status().isOk());

		String body = this.student.get(attempt(this.course, id, a))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.acceptingAnswers").value(true))
			.andExpect(jsonPath("$.questions.length()").value(3))
			.andExpect(jsonPath("$.questions[0].answer.optionIds[0]").value(o01))
			.andExpect(jsonPath("$.questions[0].answer.revision").value(2))
			.andExpect(jsonPath("$.questions[1].answer.revision").value(0))
			.andExpect(jsonPath("$.questions[2].answer.text").value("중복을 없애 이상 현상을 막는다"))
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertNoResults(body);
	}

	@Test
	void aSubmittedAttemptIsImmutableAndSubmitReplaysItsReceipt() throws Exception {
		long id = quiz("");
		long a = start(this.student, id);
		this.student.putJson(answer(this.course, id, a, questionId(id, 0)), choice(0, optionId(id, 0, 0)));
		String receipt = this.student.submit(submitPath(this.course, id, a), "submit-1", "{}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("graded"))
			.andExpect(jsonPath("$.finishReason").value("submitted"))
			.andExpect(jsonPath("$.totalScore").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		this.student.submit(submitPath(this.course, id, a), "submit-1", "{}")
			.andExpect(status().isOk())
			.andExpect(content().json(receipt, JsonCompareMode.STRICT));
		this.student.submit(submitPath(this.course, id, a), "submit-2", "{}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.attemptId").value(a))
			.andExpect(jsonPath("$.status").value("graded"));
		this.student.putJson(answer(this.course, id, a, questionId(id, 1)), choice(0, optionId(id, 1, 0)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attempt_closed"));
		assertThat(auditCount("quiz.attempt.submit", a)).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM quiz_attempt_answers WHERE quiz_attempt_id = :a")
			.param("a", a)
			.query(Long.class)
			.single()).isEqualTo(3);
		this.student.get(attempt(this.course, id, a))
			.andExpect(jsonPath("$.acceptingAnswers").value(false))
			.andExpect(jsonPath("$.questions[0].answer.optionIds[0]").value(optionId(id, 0, 0)));
	}

	@Test
	void studentsCannotStartMoreAttemptsThanAllowed() throws Exception {
		long id = quiz("\"attemptLimit\":2");
		long first = start(this.student, id);
		this.student.submit(submitPath(this.course, id, first), "first", "{}").andExpect(status().isOk());
		JsonNode second = ApiClient.json(this.student.post(attempts(this.course, id)).andExpect(status().isCreated()));
		assertThat(second.get("attemptNo").asInt()).isEqualTo(2);
		this.student.get(quiz(this.course, id)).andExpect(jsonPath("$.canStart").value(true));
		this.student.submit(submitPath(this.course, id, second.get("attemptId").asLong()), "second", "{}")
			.andExpect(status().isOk());
		this.student.post(attempts(this.course, id))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attempt_limit_reached"));
		this.student.get(quiz(this.course, id))
			.andExpect(jsonPath("$.canStart").value(false))
			.andExpect(jsonPath("$.attemptsUsed").value(2))
			.andExpect(jsonPath("$.attempts.length()").value(2));
		this.student.get(quizzes(this.course))
			.andExpect(jsonPath("$[0].status").value("graded"))
			.andExpect(jsonPath("$[0].attemptsUsed").value(2));
		assertThat(attemptCount(id, this.studentId)).isEqualTo(2);
	}

	@Test
	void correctnessAndScoresStayOutOfStudentResponsesUntilTheReleasePolicyAllowsThem() throws Exception {
		LocalDateTime t = this.clock.freeze();
		long id = quiz("\"timeLimitMinutes\":30");
		LocalDateTime closeAt = t.plusHours(1);
		window(id, null, closeAt);
		long a = start(this.student, id);
		long q2 = questionId(id, 2);
		this.student.putJson(answer(this.course, id, a, questionId(id, 0)), choice(0, optionId(id, 0, 0)));
		this.student.putJson(answer(this.course, id, a, questionId(id, 1)),
				choice(0, optionId(id, 1, 0), optionId(id, 1, 2)));
		this.student.putJson(answer(this.course, id, a, q2), text(0, "중복 제거"));
		assertNoResults(body(this.student.get(attempt(this.course, id, a))));
		this.student.submit(submitPath(this.course, id, a), "s", "{}").andExpect(jsonPath("$.status").value("submitted"));
		this.professor.putJson(attempt(this.course, id, a) + "/answers/" + q2 + "/grade",
				"{\"score\":4,\"feedback\":\"핵심은 맞음\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalScore").value(9.0));

		// 결과 공개 전: 채점을 마쳐도 총점, 정답, 문항 점수, 의견이 없다.
		assertNoResults(body(this.student.get(attempt(this.course, id, a))));
		assertNoResults(body(this.student.get(quiz(this.course, id))));
		this.student.get(quiz(this.course, id)).andExpect(jsonPath("$.score").doesNotExist());

		// 총점 공개 일시가 지나면 총점과 성적만 보인다. 응시 기간 안이라 정답은 아직 없다.
		String release = """
				{"weekNo":2,"title":"퀴즈 1","closeAt":"%s","timeLimitMinutes":30,"resultReleaseAt":"%s",
				 "answersVisible":true,"revision":1}
				""".formatted(closeAt, t);
		this.professor.putJson(quiz(this.course, id), release).andExpect(status().isOk());
		String scored = this.student.get(attempt(this.course, id, a))
			.andExpect(jsonPath("$.totalScore").value(9.0))
			.andExpect(jsonPath("$.questions[0].explanation").doesNotExist())
			.andExpect(jsonPath("$.questions[0].answer.awardedScore").doesNotExist())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(scored).doesNotContain("\"correct\"").doesNotContain("feedback").doesNotContain("핵심은 맞음");
		this.student.get(quiz(this.course, id))
			.andExpect(jsonPath("$.score").value(9.0))
			.andExpect(jsonPath("$.attempts[0].totalScore").value(9.0));
		this.classmate.get(quiz(this.course, id)).andExpect(jsonPath("$.score").doesNotExist());

		// 응시 기간이 끝나면 정답, 해설, 문항 점수, 의견까지 보인다.
		this.clock.set(closeAt);
		this.student.get(attempt(this.course, id, a))
			.andExpect(jsonPath("$.questions[0].options[0].correct").value(true))
			.andExpect(jsonPath("$.questions[0].options[1].correct").value(false))
			.andExpect(jsonPath("$.questions[0].explanation").value("덧셈"))
			.andExpect(jsonPath("$.questions[0].answer.correct").value(true))
			.andExpect(jsonPath("$.questions[0].answer.awardedScore").value(2.0))
			.andExpect(jsonPath("$.questions[1].answer.awardedScore").value(3.0))
			.andExpect(jsonPath("$.questions[2].answer.awardedScore").value(4.0))
			.andExpect(jsonPath("$.questions[2].answer.feedback").value("핵심은 맞음"));
	}

	@Test
	void anotherSessionTakesTheAttemptOverWithoutExtendingIt() throws Exception {
		long id = quiz("\"timeLimitMinutes\":30");
		JsonNode first = ApiClient.json(this.student.post(attempts(this.course, id)).andExpect(status().isCreated()));
		long a = first.get("attemptId").asLong();
		Client otherBrowser = client(this.studentId);
		JsonNode resumed = ApiClient.json(otherBrowser.post(attempts(this.course, id)).andExpect(status().isOk()));
		assertThat(resumed.get("attemptId").asLong()).isEqualTo(a);
		assertThat(resumed.get("deadlineAt").asString()).isEqualTo(first.get("deadlineAt").asString());
		assertThat(auditCount("quiz.attempt.resume", a)).isEqualTo(1);
		long q0 = questionId(id, 0);
		this.student.putJson(answer(this.course, id, a, q0), choice(0, optionId(id, 0, 0)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attempt_moved"));
		this.student.submit(submitPath(this.course, id, a), "old-tab", "{}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attempt_moved"));
		otherBrowser.putJson(answer(this.course, id, a, q0), choice(0, optionId(id, 0, 0))).andExpect(status().isOk());
		otherBrowser.submit(submitPath(this.course, id, a), "new-tab", "{}").andExpect(status().isOk());
		assertThat(attemptCount(id, this.studentId)).isEqualTo(1);
	}

	@Test
	void attemptEndpointsFollowTheMembershipAndOwnershipMatrix() throws Exception {
		long id = quiz("");
		long second = quiz("");
		long a = start(this.student, id);
		long q0 = questionId(id, 0);
		String save = choice(0, optionId(id, 0, 0));
		long other = this.data.course();
		long prof = this.jdbc.sql("SELECT user_id FROM course_instructors WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single();
		this.data.instructor(other, prof);
		this.data.enroll(other, this.studentId);

		// 시작: 수강생만. client가 재진입을 지시하는 필드는 없다.
		this.professor.post(attempts(this.course, id)).andExpect(status().isNotFound());
		this.outsider.post(attempts(this.course, id)).andExpect(status().isNotFound());
		this.student.post(attempts(other, id)).andExpect(status().isNotFound());
		this.anonymous.post(attempts(this.course, id)).andExpect(status().isUnauthorized());
		this.student.postWithoutCsrf(attempts(this.course, id), "{}").andExpect(status().isForbidden());
		this.student.postJson(attempts(this.course, id), "{\"force\":true}").andExpect(status().isBadRequest());

		// 조회: 본인과 담당자만. 같은 과목의 다른 학생, 다른 퀴즈나 다른 과목의 경로로는 없다.
		this.classmate.get(attempt(this.course, id, a)).andExpect(status().isNotFound());
		this.outsider.get(attempt(this.course, id, a)).andExpect(status().isNotFound());
		this.student.get(attempt(other, id, a)).andExpect(status().isNotFound());
		this.student.get(attempt(this.course, second, a)).andExpect(status().isNotFound());
		this.professor.get(attempt(other, id, a)).andExpect(status().isNotFound());
		this.anonymous.get(attempt(this.course, id, a)).andExpect(status().isUnauthorized());
		this.professor.get(attempt(this.course, id, a)).andExpect(status().isOk());

		// 저장
		this.classmate.putJson(answer(this.course, id, a, q0), save).andExpect(status().isNotFound());
		this.professor.putJson(answer(this.course, id, a, q0), save).andExpect(status().isNotFound());
		this.outsider.putJson(answer(this.course, id, a, q0), save).andExpect(status().isNotFound());
		this.student.putJson(answer(other, id, a, q0), save).andExpect(status().isNotFound());
		this.anonymous.putJson(answer(this.course, id, a, q0), save).andExpect(status().isUnauthorized());
		this.student.putWithoutCsrf(answer(this.course, id, a, q0), save).andExpect(status().isForbidden());

		// 제출
		this.classmate.submit(submitPath(this.course, id, a), "k", "{}").andExpect(status().isNotFound());
		this.professor.submit(submitPath(this.course, id, a), "k", "{}").andExpect(status().isNotFound());
		this.outsider.submit(submitPath(this.course, id, a), "k", "{}").andExpect(status().isNotFound());
		this.student.submit(submitPath(other, id, a), "k", "{}").andExpect(status().isNotFound());
		this.anonymous.submit(submitPath(this.course, id, a), "k", "{}").andExpect(status().isUnauthorized());
		this.student.postWithoutCsrf(submitPath(this.course, id, a), "{}").andExpect(status().isForbidden());
		this.student.submit(submitPath(this.course, id, a), null, "{}").andExpect(status().isBadRequest());
		this.student.submit(submitPath(this.course, id, a), "bad key!", "{}").andExpect(status().isBadRequest());

		// 거부된 요청은 아무것도 바꾸지 않았다.
		this.student.get(attempt(this.course, id, a))
			.andExpect(jsonPath("$.status").value("in_progress"))
			.andExpect(jsonPath("$.questions[0].answer.revision").value(0));
		assertThat(attemptCount(id, this.classmateId) + attemptCount(id, this.studentId)).isEqualTo(1);
	}

	private static LocalDateTime time(JsonNode node, String field) {
		return LocalDateTime.parse(node.get(field).asString());
	}

	private static String body(ResultActions result) throws Exception {
		return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

	/** 학생 응답에 정답, 해설, 문항 점수, 의견, 총점이 없다. 값이 null인 것이 아니라 속성 자체가 없다. */
	private static void assertNoResults(String body) {
		assertThat(body).doesNotContain("\"correct\"")
			.doesNotContain("explanation")
			.doesNotContain("awardedScore")
			.doesNotContain("feedback")
			.doesNotContain("totalScore")
			.doesNotContain("\"score\"")
			.doesNotContain("덧셈");
	}

}
