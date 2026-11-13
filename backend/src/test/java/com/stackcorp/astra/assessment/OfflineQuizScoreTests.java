package com.stackcorp.astra.assessment;

import static com.stackcorp.astra.assessment.GradebookTestSupport.gradebook;
import static com.stackcorp.astra.assessment.GradebookTestSupport.item;
import static com.stackcorp.astra.assessment.GradebookTestSupport.myGrades;
import static com.stackcorp.astra.assessment.GradebookTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import org.springframework.test.web.servlet.ResultActions;

/**
 * 오프라인 시험의 점수 입력(ADR-0021 13번, ADR-0037). 직접 입력 항목의 점수 일괄 저장과 같은 계약(전부 아니면 전무, 행마다 수정 번호,
 * 행마다 감사 기록)이고, 학생에게는 퀴즈의 결과 공개 일시부터 보인다(원본 62의 "평가 82 / 90점").
 */
class OfflineQuizScoreTests extends QuizTestSupport {

	@Test
	void instructorsEnterOfflineScoresThatStudentsSeeFromTheResultRelease() throws Exception {
		LocalDateTime t = this.clock.freeze();
		long id = offline("\"resultReleaseAt\":\"" + t.plusHours(1) + "\"");
		String scores = quiz(this.course, id) + "/scores";

		this.professor.putJson(scores, rows(row(this.studentId, "82", "잘했음", 0), row(this.classmateId, "70", null, 0)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rows.length()").value(2))
			.andExpect(jsonPath("$.rows[0].studentId").value(Math.min(this.studentId, this.classmateId)))
			.andExpect(jsonPath("$.rows[0].version").value(1));
		this.professor.get(gradebook(this.course))
			.andExpect(jsonPath("$.items[0].quizId").value(id))
			.andExpect(jsonPath("$.items[0].gradedCount").value(2))
			.andExpect(jsonPath("$.students[?(@.studentId == %d)].scores[0].score", this.studentId).value(82.0));
		// 결과 공개 전에는 학생 응답에 점수 속성이 없다.
		this.student.get(quiz(this.course, id)).andExpect(status().isOk()).andExpect(jsonPath("$.score").doesNotExist());
		this.student.get(myGrades(this.course))
			.andExpect(jsonPath("$.items[0].released").value(false))
			.andExpect(jsonPath("$.items[0].score").doesNotExist());

		this.clock.set(t.plusHours(1));
		this.student.get(quiz(this.course, id)).andExpect(jsonPath("$.score").value(82.0));
		this.student.get(myGrades(this.course))
			.andExpect(jsonPath("$.items[0].score").value(82.0))
			.andExpect(jsonPath("$.items[0].feedback").value("잘했음"))
			.andExpect(jsonPath("$.items[0].maxScore").value(90.0))
			.andExpect(jsonPath("$.total.score").value(82.0));
		this.classmate.get(quiz(this.course, id)).andExpect(jsonPath("$.score").value(70.0));

		// 읽은 수정 번호가 다르면 아무 행도 저장하지 않는다. 맞는 번호로 고치면 학생 화면도 바뀐다.
		this.professor.putJson(scores, rows(row(this.studentId, "85", null, 1), row(this.classmateId, "75", null, 0)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"))
			.andExpect(jsonPath("$.rows.length()").value(1))
			.andExpect(jsonPath("$.rows[0].code").value("version_conflict"));
		this.student.get(quiz(this.course, id)).andExpect(jsonPath("$.score").value(82.0));
		this.professor.putJson(scores, rows(row(this.studentId, "85", null, 1))).andExpect(status().isOk());
		this.student.get(quiz(this.course, id)).andExpect(jsonPath("$.score").value(85.0));
		long scoreId = this.jdbc.sql("""
				SELECT s.grade_score_id FROM grade_scores s JOIN grade_items g ON g.grade_item_id = s.grade_item_id
				 WHERE g.activity_id = :activity AND s.user_id = :user
				""").param("activity", activityOfQuiz(id)).param("user", this.studentId).query(Long.class).single();
		assertThat(auditCount("grade_score.write", scoreId)).isEqualTo(2);

		// 이미 준 점수보다 배점을 낮추지 못한다.
		this.professor.putJson(quiz(this.course, id), """
				{"weekNo":8,"title":"중간고사","closeAt":"2999-01-01T00:00:00","maxScore":80,"revision":%d}
				""".formatted(revision(id)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("score_exceeds_max"));
	}

	@Test
	void oneBadRowStoresNothing() throws Exception {
		long id = offline("");
		String scores = quiz(this.course, id) + "/scores";
		long outsiderId = this.data.user("STUDENT");
		ResultActions rejected = this.professor.putJson(scores,
				rows(row(this.studentId, "80", null, 0), row(this.classmateId, "90.5", null, 0), row(outsiderId, "1", null, 0)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_rows"));
		assertThat(ApiClient.json(rejected).get("rows").toString()).contains("not_student");
		this.professor.putJson(scores, rows(row(this.studentId, "80", null, 0), row(this.classmateId, "90.5", null, 0)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.rows[0].index").value(1))
			.andExpect(jsonPath("$.rows[0].code").value("score_exceeds_max"));
		this.professor.putJson(scores, "{\"rows\":[]}").andExpect(status().isBadRequest());
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM grade_scores s JOIN grade_items g ON g.grade_item_id = s.grade_item_id
				 WHERE g.activity_id = :activity
				""").param("activity", activityOfQuiz(id)).query(Long.class).single()).isZero();
	}

	@Test
	void onlyTheInstructorEntersOfflineScoresAndOnlineQuizzesRefuse() throws Exception {
		long id = offline("");
		String scores = quiz(this.course, id) + "/scores";
		String body = rows(row(this.studentId, "10", null, 0));
		for (Client denied : List.of(this.student, this.classmate, this.outsider)) {
			denied.putJson(scores, body).andExpect(status().isNotFound());
		}
		this.professor.putJson(quiz(otherCourseOfProfessor(), id) + "/scores", body).andExpect(status().isNotFound());
		this.anonymous.putJson(scores, body).andExpect(status().isUnauthorized());
		this.professor.putWithoutCsrf(scores, body).andExpect(status().isForbidden());
		this.professor.putJson(quiz(this.course, quiz("")) + "/scores", body)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("online_quiz"));
		// 성적부의 항목 경로로는 여전히 퀴즈 점수를 쓰지 못한다.
		long itemId = this.jdbc.sql("SELECT grade_item_id FROM grade_items WHERE activity_id = :id")
			.param("id", activityOfQuiz(id))
			.query(Long.class)
			.single();
		this.professor.putJson(item(this.course, itemId) + "/scores", body)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("managed_by_quiz"));
		cancelCourse();
		this.professor.putJson(scores, body).andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM grade_scores WHERE grade_item_id = :id")
			.param("id", itemId)
			.query(Long.class)
			.single()).isZero();
	}

	private long offline(String extraFields) throws Exception {
		String body = """
				{"weekNo":8,"title":"중간고사","examType":"offline","closeAt":"2999-01-01T00:00:00","maxScore":90%s}
				""".formatted(extraFields.isEmpty() ? "" : "," + extraFields);
		return ApiClient.json(this.professor.postJson(quizzes(this.course), body).andExpect(status().isCreated()))
			.get("quiz")
			.get("quizId")
			.asLong();
	}

	private static String rows(String... rows) {
		return "{\"rows\":[" + String.join(",", rows) + "]}";
	}

}
