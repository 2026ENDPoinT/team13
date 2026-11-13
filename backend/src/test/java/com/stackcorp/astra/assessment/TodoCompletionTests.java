package com.stackcorp.astra.assessment;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

/**
 * 할 일의 완료 판정(assessment.api.ActivityCompletions, EP-0144의 "미완료"). 본인이 끝낸 과제, 퀴즈, 설문은 그 학생의 할 일 목록과 건수에서
 * 빠지고, 같은 과목의 다른 학생에게는 그대로 남는다.
 */
class TodoCompletionTests extends SurveyTestSupport {

	@Test
	void finishedActivitiesLeaveOnlyThatStudentsTodoList() throws Exception {
		long assignmentId = assignment("");
		long limitedQuiz = quiz("\"attemptLimit\":1");
		long unlimitedQuiz = quiz("");
		long offlineQuiz = ApiClient.json(this.professor.postJson(QuizTestSupport.quizzes(this.course), """
				{"weekNo":8,"title":"중간고사","examType":"offline","closeAt":"2999-01-01T00:00:00","maxScore":90}
				""").andExpect(status().isCreated())).get("quiz").get("quizId").asLong();
		long anonymousSurvey = survey("");
		long namedSurvey = survey("\"anonymous\":false");
		long report = activityOf(assignmentId);
		long limited = activityOfQuiz(limitedQuiz);
		long unlimited = activityOfQuiz(unlimitedQuiz);
		long offline = activityOfQuiz(offlineQuiz);
		long anonymous = activityOfSurvey(anonymousSurvey);
		long named = activityOfSurvey(namedSurvey);
		List<Long> all = List.of(report, limited, unlimited, offline, anonymous, named);
		assertTodo(this.student, all);

		// 임시저장은 제출이 아니다. 최종 제출하면 빠진다.
		this.student.putJson(submission(this.course, assignmentId) + "/draft", "{\"body\":\"초안\"}")
			.andExpect(status().isOk());
		assertTodo(this.student, all);
		this.student.submit(submission(this.course, assignmentId), "todo-submit", submitBody(0, TEXT))
			.andExpect(status().isCreated());
		assertTodo(this.student, without(all, report));

		// 응시 횟수가 한 번인 퀴즈는 진행 중이면 남고, 제출하면 빠진다. 횟수 제한이 없으면 제출해도 다시 응시할 수 있어 남는다.
		long attempt = start(this.student, limitedQuiz);
		assertTodo(this.student, without(all, report));
		this.student.submit(QuizTestSupport.submitPath(this.course, limitedQuiz, attempt), "todo-quiz", "{}")
			.andExpect(status().isOk());
		long again = start(this.student, unlimitedQuiz);
		this.student.submit(QuizTestSupport.submitPath(this.course, unlimitedQuiz, again), "todo-quiz-2", "{}")
			.andExpect(status().isOk());
		assertTodo(this.student, without(all, report, limited));

		// 익명 설문도 참여 원장으로 완료를 안다.
		this.student.submit(completion(this.course, anonymousSurvey), "todo-s1", form(anonymousSurvey).answers(0, 0, null))
			.andExpect(status().isCreated());
		this.student.submit(completion(this.course, namedSurvey), "todo-s2", form(namedSurvey).answers(0, 0, null))
			.andExpect(status().isCreated());
		// 오프라인 시험은 온라인으로 끝낼 수 없어 마감까지 남는다.
		assertTodo(this.student, List.of(unlimited, offline));
		this.student.get("/api/me/home")
			.andExpect(jsonPath("$.todo.items[*].activityId").value(containsInAnyOrder((int) unlimited, (int) offline)))
			.andExpect(jsonPath("$.todo.total").value(2));

		// 다른 학생의 할 일은 그대로다. 마감이 지난 진행 중 응시로 횟수를 다 썼으면 다시 시작할 수 없으므로 빠진다.
		assertTodo(this.classmate, all);
		long late = start(this.classmate, limitedQuiz);
		this.jdbc.sql("UPDATE quiz_attempts SET deadline_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE quiz_attempt_id = :id")
			.param("id", late)
			.update();
		assertTodo(this.classmate, without(all, limited));
		// 수강하지 않은 사용자에게는 없다.
		this.outsider.get("/api/me/todo").andExpect(jsonPath("$.total").value(0));
	}

	private void assertTodo(Client client, List<Long> expected) throws Exception {
		Object[] ids = expected.stream().map(Long::intValue).toArray();
		client.get("/api/me/todo?size=50")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].activityId").value(containsInAnyOrder(ids)))
			.andExpect(jsonPath("$.total").value(expected.size()))
			.andExpect(jsonPath("$.truncated").value(false));
	}

	private long quiz(String extraFields) throws Exception {
		String body = """
				{"weekNo":2,"title":"퀴즈","closeAt":"2999-01-01T00:00:00",%s"questions":%s}
				""".formatted(extraFields.isEmpty() ? "" : extraFields + ",", QuizTestSupport.QUESTIONS);
		return ApiClient.json(this.professor.postJson(QuizTestSupport.quizzes(this.course), body)
			.andExpect(status().isCreated())).get("quiz").get("quizId").asLong();
	}

	private long start(Client client, long quizId) throws Exception {
		return ApiClient.json(client.post(QuizTestSupport.attempts(this.course, quizId))).get("attemptId").asLong();
	}

	private long activityOfQuiz(long quizId) {
		return this.jdbc.sql("SELECT activity_id FROM quizzes WHERE quiz_id = :id")
			.param("id", quizId)
			.query(Long.class)
			.single();
	}

	private static List<Long> without(List<Long> all, Long... removed) {
		List<Long> rest = new ArrayList<>(all);
		rest.removeAll(List.of(removed));
		return rest;
	}

}
