package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * 채점과 성적 반영. 선택 문항은 제출할 때 서버가 채점하고 단답형은 담당자가 채점한다. 응시 총점과 그 퀴즈 성적 항목의 점수는 같은
 * transaction에서 바뀐다. 성적은 채점을 마친 응시 가운데 가장 높은 총점이다(ADR-0021).
 */
class QuizGradingTests extends QuizTestSupport {

	@Test
	void shortAnswersAreGradedByTheInstructorAndTheGradeItemFollowsInTheSameTransaction() throws Exception {
		long id = quiz("");
		long a = start(this.student, id);
		long q0 = questionId(id, 0);
		long q2 = questionId(id, 2);
		this.student.putJson(answer(this.course, id, a, q0), choice(0, optionId(id, 0, 0)));
		this.student.putJson(answer(this.course, id, a, questionId(id, 1)),
				choice(0, optionId(id, 1, 0), optionId(id, 1, 2)));
		this.student.putJson(answer(this.course, id, a, q2), text(0, "중복을 없앤다"));
		this.student.submit(submitPath(this.course, id, a), "submit", "{}")
			.andExpect(jsonPath("$.status").value("submitted"));
		assertThat(score(id, this.studentId)).isNull();
		this.professor.get(attempts(this.course, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].studentName").value("테스트 STUDENT"))
			.andExpect(jsonPath("$.items[0].status").value("submitted"))
			.andExpect(jsonPath("$.items[0].pendingCount").value(1))
			.andExpect(jsonPath("$.items[0].totalScore").isEmpty())
			.andExpect(jsonPath("$.nextCursor").isEmpty());
		this.professor.get(quizzes(this.course)).andExpect(jsonPath("$[0].awaitingGradingCount").value(1));

		String grade = attempt(this.course, id, a) + "/answers/" + q2 + "/grade";
		this.professor.putJson(attempt(this.course, id, a) + "/answers/" + q0 + "/grade", "{\"score\":1}")
			.andExpect(status().isBadRequest());
		this.professor.putJson(grade, "{\"score\":5.01}").andExpect(status().isBadRequest());
		this.professor.putJson(grade, "{\"score\":-1}").andExpect(status().isBadRequest());
		this.professor.putJson(grade, "{\"score\":1,\"feedback\":\"<b>x</b>\u0007\"}").andExpect(status().isBadRequest());
		this.professor.putJson(grade, "{\"score\":1,\"studentId\":1}").andExpect(status().isBadRequest());

		this.professor.putJson(grade, "{\"score\":4,\"feedback\":\"정의는 맞고 예시가 없음\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("graded"))
			.andExpect(jsonPath("$.totalScore").value(9.0))
			.andExpect(jsonPath("$.answers[2].gradingStatus").value("manual"))
			.andExpect(jsonPath("$.answers[2].awardedScore").value(4.0))
			.andExpect(jsonPath("$.answers[2].feedback").value("정의는 맞고 예시가 없음"));
		assertThat(score(id, this.studentId)).isEqualByComparingTo("9");
		// 다시 채점해도 총점과 성적이 함께 바뀐다.
		this.professor.putJson(grade, "{\"score\":5}").andExpect(jsonPath("$.totalScore").value(10.0));
		assertThat(score(id, this.studentId)).isEqualByComparingTo("10");
		this.professor.get(attempts(this.course, id))
			.andExpect(jsonPath("$.items[0].pendingCount").value(0))
			.andExpect(jsonPath("$.items[0].totalScore").value(10.0));
		assertThat(auditCount("quiz.answer.grade", a)).isEqualTo(2);
	}

	@Test
	void theGradeIsTheBestGradedAttempt() throws Exception {
		long id = quiz("\"attemptLimit\":3");
		long first = start(this.student, id);
		this.student.putJson(answer(this.course, id, first, questionId(id, 0)), choice(0, optionId(id, 0, 0)));
		this.student.putJson(answer(this.course, id, first, questionId(id, 1)),
				choice(0, optionId(id, 1, 0), optionId(id, 1, 2)));
		this.student.submit(submitPath(this.course, id, first), "first", "{}").andExpect(jsonPath("$.status").value("graded"));
		assertThat(score(id, this.studentId)).isEqualByComparingTo("5");

		long second = start(this.student, id);
		// 복수 선택은 정답 보기를 모두, 그것만 골라야 맞다.
		this.student.putJson(answer(this.course, id, second, questionId(id, 1)), choice(0, optionId(id, 1, 0)));
		this.student.submit(submitPath(this.course, id, second), "second", "{}");
		assertThat(score(id, this.studentId)).isEqualByComparingTo("5");
		this.professor.get(attempt(this.course, id, second))
			.andExpect(jsonPath("$.totalScore").value(0.0))
			.andExpect(jsonPath("$.answers[1].correct").value(false));

		long third = start(this.student, id);
		this.student.putJson(answer(this.course, id, third, questionId(id, 2)), text(0, "답"));
		this.student.submit(submitPath(this.course, id, third), "third", "{}");
		this.professor.putJson(attempt(this.course, id, third) + "/answers/" + questionId(id, 2) + "/grade", "{\"score\":5}")
			.andExpect(jsonPath("$.totalScore").value(5.0));
		this.professor.putJson(attempt(this.course, id, third) + "/answers/" + questionId(id, 2) + "/grade",
				"{\"score\":4.5}");
		assertThat(score(id, this.studentId)).isEqualByComparingTo("5");
	}

	@Test
	void instructorsPageThroughAttemptsWithACursor() throws Exception {
		long id = quiz("");
		List<Long> ids = new ArrayList<>();
		for (Client client : List.of(this.student, this.classmate, client(enrolled()))) {
			ids.add(start(client, id));
		}
		JsonNode page = ApiClient.json(this.professor.get(attempts(this.course, id) + "?size=2").andExpect(status().isOk()));
		assertThat(page.get("items")).hasSize(2);
		assertThat(page.get("items").get(0).get("attemptId").asLong()).isEqualTo(ids.get(0));
		assertThat(page.get("items").get(1).get("attemptId").asLong()).isEqualTo(ids.get(1));
		String cursor = page.get("nextCursor").asString();
		JsonNode next = ApiClient.json(this.professor.get(attempts(this.course, id) + "?size=2&cursor=" + cursor));
		assertThat(next.get("items")).hasSize(1);
		assertThat(next.get("items").get(0).get("attemptId").asLong()).isEqualTo(ids.get(2));
		assertThat(next.get("nextCursor").isNull()).isTrue();
		this.professor.get(attempts(this.course, id) + "?size=0").andExpect(status().isBadRequest());
		this.professor.get(attempts(this.course, id) + "?size=101").andExpect(status().isBadRequest());
		this.professor.get(attempts(this.course, id) + "?cursor=abc").andExpect(status().isBadRequest());
	}

	@Test
	void gradingEndpointsFollowTheMembershipMatrix() throws Exception {
		long id = quiz("");
		long a = start(this.student, id);
		long q2 = questionId(id, 2);
		String grade = attempt(this.course, id, a) + "/answers/" + q2 + "/grade";
		this.professor.putJson(grade, "{\"score\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attempt_not_submitted"));
		this.student.putJson(answer(this.course, id, a, q2), text(0, "답"));
		this.student.submit(submitPath(this.course, id, a), "s", "{}");

		long other = this.data.course();
		long prof = this.jdbc.sql("SELECT user_id FROM course_instructors WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single();
		this.data.instructor(other, prof);
		for (Client denied : List.of(this.student, this.classmate, this.outsider)) {
			denied.putJson(grade, "{\"score\":1}").andExpect(status().isNotFound());
			denied.get(attempts(this.course, id)).andExpect(status().isNotFound());
		}
		this.professor.putJson(attempt(other, id, a) + "/answers/" + q2 + "/grade", "{\"score\":1}")
			.andExpect(status().isNotFound());
		this.professor.get(attempts(other, id)).andExpect(status().isNotFound());
		this.professor.putJson(attempt(this.course, id, a) + "/answers/" + questionId(quiz(""), 2) + "/grade",
				"{\"score\":1}")
			.andExpect(status().isNotFound());
		this.anonymous.putJson(grade, "{\"score\":1}").andExpect(status().isUnauthorized());
		this.anonymous.get(attempts(this.course, id)).andExpect(status().isUnauthorized());
		this.professor.putWithoutCsrf(grade, "{\"score\":1}").andExpect(status().isForbidden());
		this.professor.get(attempt(this.course, id, a)).andExpect(jsonPath("$.answers[2].gradingStatus").value("pending"));
		assertThat(score(id, this.studentId)).isNull();
	}

	private long enrolled() {
		long user = this.data.user("STUDENT");
		this.data.enroll(this.course, user);
		return user;
	}

	private BigDecimal score(long quizId, long userId) {
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
