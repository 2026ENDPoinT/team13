package com.stackcorp.astra.assessment;

import static com.stackcorp.astra.assessment.GradebookTestSupport.gradebook;
import static com.stackcorp.astra.assessment.GradebookTestSupport.item;
import static com.stackcorp.astra.assessment.GradebookTestSupport.items;
import static com.stackcorp.astra.assessment.GradebookTestSupport.myGrades;
import static com.stackcorp.astra.assessment.GradebookTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.assessment.api.ReleasedGrades;
import com.stackcorp.astra.assessment.api.ReleasedGrades.ReleasedGrade;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 퀴즈 성적 항목(ADR-0023, ADR-0021). 성적부와 학생 성적에 퀴즈 항목이 나오고, 이름·배점·공개·점수는 퀴즈 화면이 정하며 성적부는 비율만
 * 바꾼다. 학생에게는 퀴즈가 보일 때만 항목이 있고, 점수와 그 점수가 들어간 합계는 퀴즈의 결과 공개 판정이 허락한 뒤에만 있다(SEC-14).
 */
class QuizGradeItemTests extends QuizTestSupport {

	@Autowired
	ReleasedGrades releasedGrades;

	@Test
	void quizScoresReachTheGradebookAtOnceAndTheStudentOnlyAfterTheResultRelease() throws Exception {
		LocalDateTime t = this.clock.freeze();
		long id = quiz("\"resultReleaseAt\":\"" + t.plusHours(1) + "\"");
		long itemId = gradeItemOfQuiz(id);
		take(this.student, id, true);
		take(this.classmate, id, false);
		long manual = manualItem();
		this.professor.putJson(item(this.course, manual) + "/scores", "{\"rows\":[" + row(this.studentId, "8", null, 0) + "]}")
			.andExpect(status().isOk());
		this.professor.putJson(item(this.course, manual) + "/release", "{\"released\":true,\"revision\":1}")
			.andExpect(status().isOk());

		// 담당자는 공개 전에도 퀴즈 점수와 그것이 들어간 합계를 본다.
		this.professor.get(gradebook(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].itemId").value(itemId))
			.andExpect(jsonPath("$.items[0].type").value("quiz"))
			.andExpect(jsonPath("$.items[0].quizId").value(id))
			.andExpect(jsonPath("$.items[0].assignmentId").doesNotExist())
			.andExpect(jsonPath("$.items[0].managedByQuiz").value(true))
			.andExpect(jsonPath("$.items[0].managedByAssignment").value(false))
			.andExpect(jsonPath("$.items[0].maxScore").value(10.0))
			.andExpect(jsonPath("$.items[0].released").value(false))
			.andExpect(jsonPath("$.items[0].gradedCount").value(2))
			.andExpect(jsonPath("$.students[0].studentId").value(this.studentId))
			.andExpect(jsonPath("$.students[0].scores[0].itemId").value(itemId))
			.andExpect(jsonPath("$.students[0].scores[0].score").value(5.0))
			.andExpect(jsonPath("$.students[0].total.score").value(13.0))
			.andExpect(jsonPath("$.students[1].scores[0].score").value(2.0));

		// 학생은 공개 전에 이름과 배점만 본다. 점수 속성이 없고 합계에 퀴즈가 섞이지 않는다.
		String before = body(this.student.get(myGrades(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].type").value("quiz"))
			.andExpect(jsonPath("$.items[0].quizId").value(id))
			.andExpect(jsonPath("$.items[0].closeAt").exists())
			.andExpect(jsonPath("$.items[0].submitted").doesNotExist())
			.andExpect(jsonPath("$.items[0].maxScore").value(10.0))
			.andExpect(jsonPath("$.items[0].released").value(false))
			.andExpect(jsonPath("$.items[0].score").doesNotExist())
			.andExpect(jsonPath("$.items[0].gradedAt").doesNotExist())
			.andExpect(jsonPath("$.total.score").value(8.0))
			.andExpect(jsonPath("$.total.maxScore").value(10.0))
			.andExpect(jsonPath("$.total.itemCount").value(1)));
		assertThat(body(this.student.get(myGrades(this.course) + "?type=quiz")
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.total.itemCount").value(1)))).doesNotContain("\"score\":5");
		assertThat(before).doesNotContain("\"score\":5");
		this.student.get(quiz(this.course, id)).andExpect(jsonPath("$.score").doesNotExist());

		// 결과 공개 일시(퀴즈 시계)가 되면 점수와 합계에 들어간다. 각자 자기 점수만 본다.
		this.clock.set(t.plusHours(1));
		this.student.get(myGrades(this.course))
			.andExpect(jsonPath("$.items[0].released").value(true))
			.andExpect(jsonPath("$.items[0].score").value(5.0))
			.andExpect(jsonPath("$.items[0].gradedAt").exists())
			.andExpect(jsonPath("$.total.score").value(13.0))
			.andExpect(jsonPath("$.total.maxScore").value(20.0))
			.andExpect(jsonPath("$.total.itemCount").value(2));
		assertThat(body(this.classmate.get(myGrades(this.course))
			.andExpect(jsonPath("$.items[0].score").value(2.0))
			.andExpect(jsonPath("$.total.score").value(2.0)))).doesNotContain("\"score\":5");
		this.professor.get(gradebook(this.course)).andExpect(jsonPath("$.items[0].released").value(true));

		// 과목 밖 사용자, 담당자의 학생 경로, 다른 과목 경로는 404이거나 그 과목의 항목이 없다.
		this.outsider.get(myGrades(this.course)).andExpect(status().isNotFound());
		this.professor.get(myGrades(this.course)).andExpect(status().isNotFound());
		this.student.get(gradebook(this.course)).andExpect(status().isNotFound());
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.studentId);
		this.student.get(myGrades(otherCourse)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
	}

	@Test
	void theGradebookOnlyChangesTheWeightOfAQuizItem() throws Exception {
		long id = quiz("");
		long itemId = gradeItemOfQuiz(id);
		for (String body : new String[] { "{\"name\":\"바꾼 이름\",\"revision\":1}", "{\"maxScore\":100,\"revision\":1}" }) {
			this.professor.putJson(item(this.course, itemId), body)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("managed_by_quiz"));
		}
		this.professor.putJson(item(this.course, itemId) + "/release", "{\"released\":true,\"revision\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("managed_by_quiz"));
		this.professor.putJson(item(this.course, itemId) + "/scores", "{\"rows\":[" + row(this.studentId, "3", null, 0) + "]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("managed_by_quiz"));
		this.professor.delete(item(this.course, itemId) + "?revision=1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("managed_by_quiz"));
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM grade_scores WHERE grade_item_id = :id")
			.param("id", itemId)
			.query(Long.class)
			.single()).isZero();
		// 학생과 과목 밖 사용자는 성적부의 어떤 경로로도 퀴즈 항목에 닿지 못한다.
		for (Client other : new Client[] { this.student, this.outsider }) {
			other.putJson(item(this.course, itemId), "{\"weight\":10,\"revision\":1}").andExpect(status().isNotFound());
		}

		this.professor.putJson(item(this.course, itemId), "{\"weight\":30,\"revision\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("quiz"))
			.andExpect(jsonPath("$.quizId").value(id))
			.andExpect(jsonPath("$.weight").value(30.0))
			.andExpect(jsonPath("$.name").value("퀴즈 1"))
			.andExpect(jsonPath("$.revision").value(2));
		// 퀴즈를 고치면 항목 번호가 올라, 그 전에 읽은 성적부 화면의 수정은 409다.
		this.professor.putJson(quiz(this.course, id),
				"{\"weekNo\":2,\"title\":\"퀴즈 이름 변경\",\"closeAt\":\"2999-01-01T00:00:00\",\"revision\":" + revision(id) + "}")
			.andExpect(status().isOk());
		this.professor.putJson(item(this.course, itemId), "{\"weight\":40,\"revision\":2}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		this.professor.get(gradebook(this.course))
			.andExpect(jsonPath("$.items[0].name").value("퀴즈 이름 변경"))
			.andExpect(jsonPath("$.items[0].weight").value(30.0))
			.andExpect(jsonPath("$.items[0].revision").value(3));
	}

	@Test
	void hiddenAndDeletedQuizzesStayOutOfStudentGradesAndDeletedOnesOutOfTheGradebook() throws Exception {
		long hidden = quiz("\"published\":false");
		long deleted = quiz("");
		long assignmentId = assignment("");
		this.student.get(myGrades(this.course)).andExpect(jsonPath("$.items.length()").value(2));
		this.professor.get(gradebook(this.course)).andExpect(jsonPath("$.items.length()").value(3));

		long deletedItem = gradeItemOfQuiz(deleted);
		softDelete(activityOfQuiz(deleted));
		softDelete(activityOf(assignmentId));
		this.professor.get(gradebook(this.course))
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].quizId").value(hidden));
		this.student.get(myGrades(this.course)).andExpect(jsonPath("$.items").isEmpty());
		this.student.get(myGrades(this.course) + "?type=quiz").andExpect(jsonPath("$.items").isEmpty());
		this.professor.putJson(item(this.course, deletedItem), "{\"weight\":10,\"revision\":1}")
			.andExpect(status().isNotFound());
		this.professor.get(quiz(this.course, deleted)).andExpect(status().isNotFound());
	}

	@Test
	void newlyReleasedQuizGradesFollowTheQuizReleaseRule() throws Exception {
		LocalDateTime t = this.clock.freeze();
		long id = quiz("\"resultReleaseAt\":\"" + t.plusHours(1) + "\"");
		long itemId = gradeItemOfQuiz(id);
		take(this.student, id, true);
		LocalDateTime since = t.minusHours(1);
		List<Long> courses = List.of(this.course);

		assertThat(this.releasedGrades.releasedSince(this.studentId, courses, since, t, 10)).isEmpty();
		List<ReleasedGrade> released = this.releasedGrades.releasedSince(this.studentId, courses, since, t.plusHours(1),
				10);
		assertThat(released).extracting(ReleasedGrade::gradeItemId).containsExactly(itemId);
		assertThat(released.get(0).itemType()).isEqualTo("quiz");
		assertThat(released.get(0).activityId()).isEqualTo(activityOfQuiz(id));
		// 응시하지 않은 학생에게는 없다. 퀴즈를 비공개로 돌리거나 지우면 없다.
		assertThat(this.releasedGrades.releasedSince(this.classmateId, courses, since, t.plusHours(1), 10)).isEmpty();
		this.jdbc.sql("UPDATE learning_activities SET published_yn = 0 WHERE activity_id = :id")
			.param("id", activityOfQuiz(id))
			.update();
		assertThat(this.releasedGrades.releasedSince(this.studentId, courses, since, t.plusHours(1), 10)).isEmpty();
		this.jdbc.sql("UPDATE learning_activities SET published_yn = 1 WHERE activity_id = :id")
			.param("id", activityOfQuiz(id))
			.update();
		softDelete(activityOfQuiz(id));
		assertThat(this.releasedGrades.releasedSince(this.studentId, courses, since, t.plusHours(1), 10)).isEmpty();
	}

	/** 학생이 응시해 제출한다. 선택 문항만 답한다. allRight면 5점, 아니면 2점이다(배점 10). */
	private void take(Client client, long quizId, boolean allRight) throws Exception {
		long a = start(client, quizId);
		client.putJson(answer(this.course, quizId, a, questionId(quizId, 0)), choice(0, optionId(quizId, 0, 0)))
			.andExpect(status().isOk());
		if (allRight) {
			client.putJson(answer(this.course, quizId, a, questionId(quizId, 1)),
					choice(0, optionId(quizId, 1, 0), optionId(quizId, 1, 2)))
				.andExpect(status().isOk());
		}
		client.submit(submitPath(this.course, quizId, a), "take-" + a, "{}").andExpect(jsonPath("$.status").value("graded"));
	}

	/** 담당자가 배점 10의 직접 입력 항목을 만든다. */
	private long manualItem() throws Exception {
		return ApiClient.json(this.professor.postJson(items(this.course), "{\"name\":\"출석\",\"maxScore\":10}")
			.andExpect(status().isCreated())).get("itemId").asLong();
	}

	private long gradeItemOfQuiz(long quizId) {
		return this.jdbc.sql("SELECT grade_item_id FROM grade_items WHERE activity_id = :id")
			.param("id", activityOfQuiz(quizId))
			.query(Long.class)
			.single();
	}

	private void softDelete(long activityId) {
		this.jdbc.sql("UPDATE learning_activities SET deleted_at = CURRENT_TIMESTAMP(3) WHERE activity_id = :id")
			.param("id", activityId)
			.update();
	}

	private static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

}
