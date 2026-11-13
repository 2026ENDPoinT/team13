package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 응시 뒤의 정답·배점 정정과 재채점(ADR-0037). 문항 하나의 정답 보기와 배점만 바뀌고, 닫힌 응시의 총점, 학생 성적, 성적 항목 배점이 같은
 * transaction에서 바뀐다. 진행 중 응시는 끝날 때 바뀐 정답으로 채점되고, 재채점과 겹친 제출도 바뀐 정답의 점수로 끝난다.
 */
class QuizRegradeTests extends QuizTestSupport {

	@Test
	void correctingTheKeyRescoresClosedAttemptsGradesAndTheItem() throws Exception {
		long id = quiz("\"resultReleaseAt\":\"2000-01-01T00:00:00\"");
		long q0 = questionId(id, 0);
		long right = optionId(id, 0, 0);
		long wrong = optionId(id, 0, 1);
		long mine = take(this.student, id, wrong);
		long theirs = take(this.classmate, id, right);
		long third = enrolled();
		Client late = client(third);
		long inFlight = start(late, id);
		late.putJson(answer(this.course, id, inFlight, q0), choice(0, wrong)).andExpect(status().isOk());
		assertThat(grade(id, this.studentId)).isEqualByComparingTo("0");
		assertThat(grade(id, this.classmateId)).isEqualByComparingTo("2");
		long revision = revision(id);

		this.professor.putJson(answerKey(id, q0), key(revision, "4", wrong))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rescoredAttempts").value(2))
			.andExpect(jsonPath("$.quiz.revision").value(revision + 1))
			.andExpect(jsonPath("$.quiz.quiz.maxScore").value(12.0))
			.andExpect(jsonPath("$.quiz.questions[0].text").value("1+1은?"))
			.andExpect(jsonPath("$.quiz.questions[0].points").value(4.0))
			.andExpect(jsonPath("$.quiz.questions[0].options[0].text").value("2"))
			.andExpect(jsonPath("$.quiz.questions[0].options[0].correct").value(false))
			.andExpect(jsonPath("$.quiz.questions[0].options[1].correct").value(true));
		assertThat(grade(id, this.studentId)).isEqualByComparingTo("4");
		assertThat(grade(id, this.classmateId)).isEqualByComparingTo("0");
		this.professor.get(attempt(this.course, id, mine))
			.andExpect(jsonPath("$.totalScore").value(4.0))
			.andExpect(jsonPath("$.answers[0].correct").value(true))
			.andExpect(jsonPath("$.answers[0].awardedScore").value(4.0))
			.andExpect(jsonPath("$.answers[0].points").value(4.0));
		this.professor.get(attempt(this.course, id, theirs)).andExpect(jsonPath("$.totalScore").value(0.0));
		// 공개한 점수와 합계, 성적부의 배점이 함께 바뀐다.
		this.student.get(GradebookTestSupport.myGrades(this.course))
			.andExpect(jsonPath("$.items[0].score").value(4.0))
			.andExpect(jsonPath("$.items[0].maxScore").value(12.0))
			.andExpect(jsonPath("$.total.score").value(4.0));
		this.classmate.get(quiz(this.course, id)).andExpect(jsonPath("$.score").value(0.0));
		this.professor.get(GradebookTestSupport.gradebook(this.course))
			.andExpect(jsonPath("$.items[0].maxScore").value(12.0));
		// 진행 중이던 응시는 끝날 때 바뀐 정답으로 채점된다.
		late.submit(submitPath(this.course, id, inFlight), "late", "{}").andExpect(jsonPath("$.status").value("graded"));
		assertThat(grade(id, third)).isEqualByComparingTo("4");
		assertThat(auditCount("quiz.regrade", id)).isEqualTo(1);
		assertThat(auditCount("quiz.attempt.regrade", mine)).isEqualTo(1);
		assertThat(auditCount("quiz.attempt.regrade", theirs)).isEqualTo(1);
		assertThat(auditCount("quiz.attempt.regrade", inFlight)).isZero();

		// 지문과 보기는 여전히 바꾸지 못하고, 읽은 revision이 다르면 아무것도 바뀌지 않는다.
		this.professor.putJson(quiz(this.course, id) + "/questions",
				"{\"revision\":" + revision(id) + ",\"questions\":" + QUESTIONS + "}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("questions_locked"));
		this.professor.putJson(answerKey(id, q0), key(revision, "2", right))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		this.professor.putJson(answerKey(id, q0),
				"{\"revision\":" + revision(id) + ",\"points\":2,\"correctOptionIds\":[" + right + "],\"text\":\"x\"}")
			.andExpect(status().isBadRequest());
		assertThat(grade(id, this.studentId)).isEqualByComparingTo("4");
	}

	@Test
	void keysAreCheckedAgainstTheQuestion() throws Exception {
		long id = quiz("");
		long q0 = questionId(id, 0);
		long q2 = questionId(id, 2);
		long r = revision(id);
		String[] invalid = { key(r, "2", optionId(id, 0, 0), optionId(id, 0, 1)), key(r, "2"),
				key(r, "2", optionId(id, 1, 0)), key(r, "0", optionId(id, 0, 0)), key(r, "1.001", optionId(id, 0, 0)),
				"{\"points\":2,\"correctOptionIds\":[" + optionId(id, 0, 0) + "]}",
				"{\"revision\":" + r + ",\"correctOptionIds\":[" + optionId(id, 0, 0) + "]}" };
		for (String body : invalid) {
			this.professor.putJson(answerKey(id, q0), body).andExpect(status().isBadRequest());
		}
		this.professor.putJson(answerKey(id, q2), key(r, "5", optionId(id, 0, 0))).andExpect(status().isBadRequest());
		this.professor.putJson(answerKey(id, questionId(quiz(""), 0)), key(r, "2", optionId(id, 0, 0)))
			.andExpect(status().isNotFound());
		// 배점 합은 1000을 넘지 못한다.
		this.professor.putJson(answerKey(id, q2), key(r, "996")).andExpect(status().isBadRequest());
		assertThat(revision(id)).isEqualTo(r);
		// 복수 선택의 정답 집합을 바꾼다. 응시가 없어도 된다.
		this.professor.putJson(answerKey(id, questionId(id, 1)), key(r, "3", optionId(id, 1, 1)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rescoredAttempts").value(0))
			.andExpect(jsonPath("$.quiz.questions[1].options[0].correct").value(false))
			.andExpect(jsonPath("$.quiz.questions[1].options[1].correct").value(true))
			.andExpect(jsonPath("$.quiz.questions[1].options[2].correct").value(false));
	}

	@Test
	void shortAnswerPointsCannotDropBelowAGivenScoreAndPendingAnswersStayPending() throws Exception {
		long id = quiz("");
		long q2 = questionId(id, 2);
		long a = start(this.student, id);
		this.student.putJson(answer(this.course, id, a, q2), text(0, "중복을 없앤다")).andExpect(status().isOk());
		this.student.submit(submitPath(this.course, id, a), "s1", "{}").andExpect(status().isOk());
		long b = start(this.classmate, id);
		this.classmate.putJson(answer(this.course, id, b, q2), text(0, "모름")).andExpect(status().isOk());
		this.classmate.submit(submitPath(this.course, id, b), "s2", "{}").andExpect(status().isOk());
		this.professor.putJson(attempt(this.course, id, a) + "/answers/" + q2 + "/grade", "{\"score\":4}")
			.andExpect(jsonPath("$.totalScore").value(4.0));

		this.professor.putJson(answerKey(id, q2), key(revision(id), "3"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("awarded_exceeds_points"));
		this.professor.putJson(answerKey(id, q2), key(revision(id), "6"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.quiz.quiz.maxScore").value(11.0))
			.andExpect(jsonPath("$.rescoredAttempts").value(2));
		this.professor.get(attempt(this.course, id, a))
			.andExpect(jsonPath("$.totalScore").value(4.0))
			.andExpect(jsonPath("$.answers[2].awardedScore").value(4.0))
			.andExpect(jsonPath("$.answers[2].gradingStatus").value("manual"));
		this.professor.get(attempt(this.course, id, b))
			.andExpect(jsonPath("$.status").value("submitted"))
			.andExpect(jsonPath("$.totalScore").isEmpty())
			.andExpect(jsonPath("$.answers[2].gradingStatus").value("pending"));
		assertThat(grade(id, this.studentId)).isEqualByComparingTo("4");
		assertThat(grade(id, this.classmateId)).isNull();
		// 새 배점까지 줄 수 있다.
		this.professor.putJson(attempt(this.course, id, b) + "/answers/" + q2 + "/grade", "{\"score\":6}")
			.andExpect(jsonPath("$.totalScore").value(6.0));
	}

	@Test
	void onlyTheInstructorOfThatCourseRegrades() throws Exception {
		long id = quiz("");
		long q0 = questionId(id, 0);
		long wrong = optionId(id, 0, 1);
		take(this.student, id, wrong);
		String body = key(revision(id), "2", wrong);
		for (Client denied : List.of(this.student, this.classmate, this.outsider)) {
			denied.putJson(answerKey(id, q0), body).andExpect(status().isNotFound());
		}
		this.professor.putJson(quiz(otherCourseOfProfessor(), id) + "/questions/" + q0 + "/answer-key", body)
			.andExpect(status().isNotFound());
		this.anonymous.putJson(answerKey(id, q0), body).andExpect(status().isUnauthorized());
		this.professor.putWithoutCsrf(answerKey(id, q0), body).andExpect(status().isForbidden());
		assertThat(grade(id, this.studentId)).isEqualByComparingTo("0");
		cancelCourse();
		this.professor.putJson(answerKey(id, q0), body).andExpect(status().isNotFound());
		assertThat(grade(id, this.studentId)).isEqualByComparingTo("0");
		assertThat(auditCount("quiz.regrade", id)).isZero();
	}

	@Test
	void submitsRacingARegradeEndWithTheCorrectedScore() throws Exception {
		long id = quiz("");
		long q0 = questionId(id, 0);
		long wrong = optionId(id, 0, 1);
		List<Client> clients = new ArrayList<>();
		List<Long> attempts = new ArrayList<>();
		List<Long> users = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			long user = enrolled();
			Client client = client(user);
			long a = start(client, id);
			client.putJson(answer(this.course, id, a, q0), choice(0, wrong)).andExpect(status().isOk());
			clients.add(client);
			attempts.add(a);
			users.add(user);
		}
		String body = key(revision(id), "2", wrong);
		List<MockHttpServletResponse> responses = GradebookTestSupport.race(clients.size() + 1, (n) -> n == clients.size()
				? this.professor.putJson(answerKey(id, q0), body).andReturn().getResponse()
				: clients.get(n).submit(submitPath(this.course, id, attempts.get(n)), "race-" + n, "{}").andReturn()
					.getResponse());
		for (MockHttpServletResponse response : responses) {
			assertThat(response.getStatus()).isEqualTo(200);
		}
		for (int i = 0; i < attempts.size(); i++) {
			assertThat(this.jdbc.sql("SELECT CONCAT(status, ':', total_score) FROM quiz_attempts WHERE quiz_attempt_id = :a")
				.param("a", attempts.get(i))
				.query(String.class)
				.single()).isEqualTo("graded:2.00");
			assertThat(grade(id, users.get(i))).isEqualByComparingTo("2");
		}
		assertThat(ApiClient.json(this.professor.get(quiz(this.course, id))).get("questions").get(0).get("options").get(1)
			.get("correct")
			.asBoolean()).isTrue();
	}

	/** 학생이 응시해 첫 문항만 option으로 답하고 제출한다. @return 응시 ID */
	private long take(Client client, long quizId, long option) throws Exception {
		long a = start(client, quizId);
		client.putJson(answer(this.course, quizId, a, questionId(quizId, 0)), choice(0, option)).andExpect(status().isOk());
		client.submit(submitPath(this.course, quizId, a), "take-" + a, "{}").andExpect(jsonPath("$.status").value("graded"));
		return a;
	}

	private long enrolled() {
		long user = this.data.user("STUDENT");
		this.data.enroll(this.course, user);
		return user;
	}

	private String answerKey(long quizId, long questionId) {
		return quiz(this.course, quizId) + "/questions/" + questionId + "/answer-key";
	}

	private static String key(long revision, String points, long... optionIds) {
		StringBuilder ids = new StringBuilder();
		for (long optionId : optionIds) {
			ids.append(ids.isEmpty() ? "" : ",").append(optionId);
		}
		return "{\"revision\":" + revision + ",\"points\":" + points
				+ (optionIds.length == 0 ? "" : ",\"correctOptionIds\":[" + ids + "]") + "}";
	}

	private BigDecimal grade(long quizId, long userId) {
		return this.jdbc.sql("""
				SELECT s.score FROM grade_scores s JOIN grade_items g ON g.grade_item_id = s.grade_item_id
				 WHERE g.activity_id = :activity AND s.user_id = :user
				""")
			.param("activity", activityOfQuiz(quizId))
			.param("user", userId)
			.query(BigDecimal.class)
			.optional()
			.orElse(null);
	}

}
