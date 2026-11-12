package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 퀴즈 삭제(ADR-0011, ADR-0037). 활동에 삭제 표시만 남기고 응시, 답안, 성적은 보존한다. 그 commit부터 퀴즈, 응시, 성적 항목은 담당자와
 * 학생 누구에게도 없고, 성적부와 학생 합계, 할 일, 캘린더에서 빠진다. 진행 중 응시는 더 저장·제출되지 않고 worker가 마감 일시에 닫는다.
 */
class QuizDeletionTests extends QuizTestSupport {

	@Autowired
	QuizAttemptCloser closer;

	@Test
	void aDeletedQuizLeavesEveryScreenButKeepsItsHistory() throws Exception {
		long id = quiz("\"resultReleaseAt\":\"2000-01-01T00:00:00\"");
		LocalDateTime now = this.jdbc.sql("SELECT CURRENT_TIMESTAMP").query(LocalDateTime.class).single();
		LocalDateTime closeAt = now.plusHours(2).withNano(0);
		window(id, null, closeAt);
		long activity = activityOfQuiz(id);
		long q0 = questionId(id, 0);
		long right = optionId(id, 0, 0);
		long wrong = optionId(id, 0, 1);
		long taken = start(this.classmate, id);
		this.classmate.putJson(answer(this.course, id, taken, q0), choice(0, right)).andExpect(status().isOk());
		this.classmate.submit(submitPath(this.course, id, taken), "done", "{}").andExpect(status().isOk());
		long inFlight = start(this.student, id);
		this.student.putJson(answer(this.course, id, inFlight, q0), choice(0, right))
			.andExpect(status().isOk());
		String day = "/api/me/calendar/days/" + closeAt.toLocalDate();
		this.classmate.get(GradebookTestSupport.myGrades(this.course)).andExpect(jsonPath("$.total.score").value(2.0));
		this.student.get("/api/me/todo?size=50").andExpect(jsonPath("$.items[*].activityId").value(hasItem((int) activity)));
		this.student.get(day).andExpect(jsonPath("$.activities[*].activityId").value(hasItem((int) activity)));
		long revision = revision(id);

		this.professor.delete(quiz(this.course, id), "\"" + revision + "\"").andExpect(status().isNoContent());

		for (Client client : List.of(this.professor, this.student, this.classmate)) {
			client.get(quiz(this.course, id)).andExpect(status().isNotFound());
			client.get(quizzes(this.course)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		}
		this.professor.get(attempts(this.course, id)).andExpect(status().isNotFound());
		this.professor.get(attempt(this.course, id, taken)).andExpect(status().isNotFound());
		this.classmate.get(attempt(this.course, id, taken)).andExpect(status().isNotFound());
		this.student.putJson(answer(this.course, id, inFlight, q0), choice(1, wrong))
			.andExpect(status().isNotFound());
		this.student.submit(submitPath(this.course, id, inFlight), "late", "{}").andExpect(status().isNotFound());
		this.student.post(attempts(this.course, id)).andExpect(status().isNotFound());
		this.professor.putJson(quiz(this.course, id) + "/questions/" + q0 + "/answer-key",
				"{\"revision\":" + (revision + 1) + ",\"points\":2,\"correctOptionIds\":[" + right + "]}")
			.andExpect(status().isNotFound());
		this.professor.delete(quiz(this.course, id), "\"" + (revision + 1) + "\"").andExpect(status().isNotFound());
		// 성적부, 학생 성적과 합계, 할 일, 캘린더에서 빠진다.
		this.professor.get(GradebookTestSupport.gradebook(this.course)).andExpect(jsonPath("$.items").isEmpty());
		this.classmate.get(GradebookTestSupport.myGrades(this.course))
			.andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.total").doesNotExist());
		this.student.get("/api/me/todo?size=50")
			.andExpect(jsonPath("$.items[*].activityId").value(not(hasItem((int) activity))));
		this.student.get(day).andExpect(jsonPath("$.activities[*].activityId").value(not(hasItem((int) activity))));
		this.professor.get(day).andExpect(jsonPath("$.activities[*].activityId").value(not(hasItem((int) activity))));

		// 기록은 남는다. 진행 중이던 응시는 worker가 마감 일시에 마지막 저장 답안으로 닫는다.
		assertThat(attemptCount(id, this.studentId) + attemptCount(id, this.classmateId)).isEqualTo(2);
		assertThat(this.jdbc.sql("""
				SELECT s.score FROM grade_scores s JOIN grade_items g ON g.grade_item_id = s.grade_item_id
				 WHERE g.activity_id = :activity AND s.user_id = :user
				""").param("activity", activity).param("user", this.classmateId).query(String.class).single())
			.isEqualTo("2.00");
		assertThat(this.jdbc.sql("SELECT deleted_by FROM learning_activities WHERE activity_id = :id AND deleted_at IS NOT NULL")
			.param("id", activity)
			.query(Long.class)
			.single()).isEqualTo(this.professorId);
		assertThat(auditCount("quiz.delete", id)).isEqualTo(1);
		deadline(inFlight, now.minusSeconds(1));
		this.closer.closeExpired();
		assertThat(this.jdbc.sql("SELECT CONCAT(status, ':', finish_reason, ':', total_score) FROM quiz_attempts WHERE quiz_attempt_id = :a")
			.param("a", inFlight)
			.query(String.class)
			.single()).isEqualTo("graded:deadline:2.00");
	}

	@Test
	void deletionNeedsTheInstructorAndTheRevisionReadLast() throws Exception {
		long id = quiz("");
		String path = quiz(this.course, id);
		String current = "\"" + revision(id) + "\"";
		for (Client denied : List.of(this.student, this.classmate, this.outsider)) {
			denied.delete(path, current).andExpect(status().isNotFound());
		}
		this.professor.delete(quiz(otherCourseOfProfessor(), id), current).andExpect(status().isNotFound());
		this.anonymous.delete(path, current).andExpect(status().isUnauthorized());
		this.professor.deleteWithoutCsrf(path, current).andExpect(status().isForbidden());
		this.professor.delete(path, null)
			.andExpect(status().isPreconditionRequired())
			.andExpect(jsonPath("$.code").value("precondition_required"));
		for (String malformed : new String[] { "1", "W/" + current, "*", "\"0\"", "\"abc\"" }) {
			this.professor.delete(path, malformed).andExpect(status().isBadRequest());
		}
		this.professor.delete(path, "\"" + (revision(id) + 1) + "\"")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		this.professor.get(path).andExpect(status().isOk());
		cancelCourse();
		this.professor.delete(path, current).andExpect(status().isNotFound());
		assertThat(auditCount("quiz.delete", id)).isZero();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_activities WHERE activity_id = :id AND deleted_at IS NULL")
			.param("id", activityOfQuiz(id))
			.query(Long.class)
			.single()).isEqualTo(1);
	}

}
