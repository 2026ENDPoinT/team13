package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

/**
 * 담당자의 채점과 점수 공개. 공개하기 전에는 점수와 의견이 학생 응답에 값으로도 속성으로도 없다(SEC-14, ASSESS-06).
 */
class GradingTests extends AssessmentTestSupport {

	private static final String FEEDBACK = "FEEDBACK-7f3a 논리 전개가 좋습니다";

	@Test
	void unreleasedScoresAndFeedbackAreNeverSerializedToStudents() throws Exception {
		long id = assignment("");
		long submissionId = submitOnce(id);
		this.professor.putJson(grade(id, submissionId), "{\"score\":12.5,\"feedback\":\"" + FEEDBACK + "\"}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.score").value(12.5))
			.andExpect(jsonPath("$.feedback").value(FEEDBACK))
			.andExpect(jsonPath("$.gradedAt").exists());
		this.professor.get(submissions(this.course, id)).andExpect(jsonPath("$[0].score").value(12.5));
		this.professor.get(submissions(this.course, id) + "/" + submissionId)
			.andExpect(jsonPath("$.grade.feedback").value(FEEDBACK));

		assertStudentSeesNoScore(id);

		this.professor.putJson(assignment(this.course, id), edit(1, true)).andExpect(status().isOk());
		this.student.get(assignment(this.course, id))
			.andExpect(jsonPath("$.assignment.scoresReleased").value(true))
			.andExpect(jsonPath("$.submission.grade.score").value(12.5))
			.andExpect(jsonPath("$.submission.grade.feedback").value(FEEDBACK));
		// 다른 학생에게는 공개 뒤에도 남의 점수가 없다.
		assertThat(body(this.classmate.get(assignment(this.course, id)).andExpect(status().isOk())))
			.doesNotContain(FEEDBACK)
			.doesNotContain("\"grade\"");

		// 공개를 거두면 다시 응답에서 빠진다.
		this.professor.putJson(assignment(this.course, id), edit(2, false)).andExpect(status().isOk());
		assertStudentSeesNoScore(id);
	}

	@Test
	void onlyInstructorsOfTheCourseGradeWithinTheMaximum() throws Exception {
		long id = assignment("");
		long submissionId = submitOnce(id);
		String good = "{\"score\":10,\"feedback\":\"좋음\"}";
		this.student.putJson(grade(id, submissionId), good).andExpect(status().isNotFound());
		this.classmate.putJson(grade(id, submissionId), good).andExpect(status().isNotFound());
		this.outsider.putJson(grade(id, submissionId), good).andExpect(status().isNotFound());
		this.anonymous.putJson(grade(id, submissionId), good).andExpect(status().isUnauthorized());
		this.professor.putWithoutCsrf(grade(id, submissionId), good).andExpect(status().isForbidden());
		// 다른 과제 경로로 이 제출물에 점수를 줄 수 없다.
		long other = assignment("");
		this.professor.putJson(grade(other, submissionId), good).andExpect(status().isNotFound());
		this.professor.get(submissions(this.course, other) + "/" + submissionId).andExpect(status().isNotFound());

		for (String bad : new String[] { "{\"score\":15.01}", "{\"score\":-1}", "{\"score\":1.555}", "{\"feedback\":\"x\"}",
				"{\"score\":1,\"feedback\":\"" + "가".repeat(1001) + "\"}", "{\"score\":1,\"userId\":1}" }) {
			this.professor.putJson(grade(id, submissionId), bad).andExpect(status().isBadRequest());
		}
		this.professor.putJson(grade(id, submissionId), "{\"score\":15}").andExpect(status().isOk());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM grade_scores WHERE user_id = :u")
			.param("u", this.studentId)
			.query(Long.class)
			.single()).isEqualTo(1);

		// 이미 준 점수보다 배점을 낮추지 못한다.
		this.professor.putJson(assignment(this.course, id),
				"{\"weekNo\":3,\"title\":\"과제 1\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":10,\"revision\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("score_exceeds_max"));
		this.professor.get(assignment(this.course, id))
			.andExpect(jsonPath("$.assignment.maxScore").value(15.0))
			.andExpect(jsonPath("$.revision").value(1));
	}

	@Test
	void draftOnlySubmissionsCannotBeGraded() throws Exception {
		long id = assignment("");
		this.student.putJson(submission(this.course, id) + "/draft", "{\"body\":\"초안\"}").andExpect(status().isOk());
		long submissionId = this.jdbc.sql("SELECT assignment_submission_id FROM assignment_submissions WHERE assignment_id = :a")
			.param("a", id)
			.query(Long.class)
			.single();
		this.professor.get(submissions(this.course, id) + "/" + submissionId).andExpect(status().isNotFound());
		this.professor.putJson(grade(id, submissionId), "{\"score\":1}").andExpect(status().isNotFound());
	}

	private void assertStudentSeesNoScore(long id) throws Exception {
		String detail = body(this.student.get(assignment(this.course, id))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.assignment.scoresReleased").value(false))
			.andExpect(jsonPath("$.submission.status").value("submitted"))
			.andExpect(jsonPath("$.submission.grade").doesNotExist()));
		assertThat(detail).doesNotContain(FEEDBACK).doesNotContain("\"grade\"").doesNotContain("\"score\"");
		String list = body(this.student.get(assignments(this.course)).andExpect(status().isOk()));
		assertThat(list).doesNotContain(FEEDBACK).doesNotContain("\"score\"");
		this.student.get(submissions(this.course, id)).andExpect(status().isNotFound());
	}

	private long submitOnce(long id) throws Exception {
		return ApiClient.json(this.student.submit(submission(this.course, id), "once-" + id, submitBody(0, TEXT))
			.andExpect(status().isCreated())).get("submissionId").asLong();
	}

	private String grade(long id, long submissionId) {
		return submissions(this.course, id) + "/" + submissionId + "/grade";
	}

	private static String edit(long revision, boolean released) {
		return "{\"weekNo\":3,\"title\":\"과제 1\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":15,\"scoresReleased\":"
				+ released + ",\"revision\":" + revision + "}";
	}

	private static String body(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
	}

}
