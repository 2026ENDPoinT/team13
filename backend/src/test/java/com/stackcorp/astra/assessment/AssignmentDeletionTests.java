package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * 과제 삭제(ADR-0011, ADR-0037). 활동에 삭제 표시만 남기고 제출물, 제출 버전과 그 파일, 점수는 보존한다(SC-07). 그 commit부터 과제,
 * 제출, 제출 파일, 성적 항목은 학생과 담당자 누구에게도 없고, 보낸 알림은 거둔다(ADR-0027).
 */
class AssignmentDeletionTests extends AssessmentTestSupport {

	private static final byte[] TXT = "제출 파일".getBytes(StandardCharsets.UTF_8);

	@Test
	void aDeletedAssignmentLeavesEveryScreenButKeepsSubmissionsFilesAndScores() throws Exception {
		long id = assignment("\"scoresReleased\":true");
		long activity = activityOf(id);
		long upload = this.student.upload(this.course, "answer.txt", TXT);
		JsonNode receipt = ApiClient.json(this.student.submit(submission(this.course, id), "mine", submitBody(0, TEXT, upload))
			.andExpect(status().isCreated()));
		long fileId = receipt.get("files").get(0).get("fileId").asLong();
		long submissionId = receipt.get("submissionId").asLong();
		this.professor.putJson(submissions(this.course, id) + "/" + submissionId + "/grade", "{\"score\":12}")
			.andExpect(status().isOk());
		this.classmate.putJson(submission(this.course, id) + "/draft", "{\"body\":\"초안\"}").andExpect(status().isOk());
		this.student.get(fileContent(this.course, fileId)).andExpect(status().isOk());
		this.student.get(GradebookTestSupport.myGrades(this.course)).andExpect(jsonPath("$.total.score").value(12.0));
		this.classmate.get("/api/me/todo?size=50").andExpect(jsonPath("$.items[*].activityId").value(hasItem((int) activity)));
		assertThat(notifications(id)).isPositive();
		long revision = ApiClient.json(this.professor.get(assignment(this.course, id))).get("revision").asLong();

		this.professor.delete(assignment(this.course, id), "\"" + revision + "\"").andExpect(status().isNoContent());

		for (Client client : List.of(this.professor, this.student, this.classmate)) {
			client.get(assignment(this.course, id)).andExpect(status().isNotFound());
			client.get(assignments(this.course)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
		}
		// 제출 파일은 부모 과제가 없으므로 제출한 학생에게도 담당자에게도 열리지 않는다.
		this.student.get(fileContent(this.course, fileId)).andExpect(status().isNotFound());
		this.professor.get(fileContent(this.course, fileId)).andExpect(status().isNotFound());
		this.professor.get(submissions(this.course, id)).andExpect(status().isNotFound());
		this.professor.get(submissions(this.course, id) + "/" + submissionId).andExpect(status().isNotFound());
		this.professor.putJson(submissions(this.course, id) + "/" + submissionId + "/grade", "{\"score\":1}")
			.andExpect(status().isNotFound());
		this.student.submit(submission(this.course, id), "again", submitBody(1, TEXT)).andExpect(status().isNotFound());
		this.classmate.putJson(submission(this.course, id) + "/draft", "{\"body\":\"초안\"}").andExpect(status().isNotFound());
		this.professor.delete(assignment(this.course, id), "\"" + (revision + 1) + "\"").andExpect(status().isNotFound());
		this.professor.get(GradebookTestSupport.gradebook(this.course)).andExpect(jsonPath("$.items").isEmpty());
		this.student.get(GradebookTestSupport.myGrades(this.course))
			.andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.total").doesNotExist());
		this.classmate.get("/api/me/todo?size=50")
			.andExpect(jsonPath("$.items[*].activityId").value(not(hasItem((int) activity))));
		this.student.get("/api/me/notifications").andExpect(jsonPath("$.items").isEmpty());
		assertThat(notifications(id)).isZero();

		// 제출물, 버전, 첨부 파일, 점수, 임시저장은 보존한다.
		assertThat(versionCount(id)).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT state FROM files WHERE file_id = :id").param("id", fileId).query(String.class).single())
			.isNotEqualTo("deleted");
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM assignment_submissions WHERE assignment_id = :id AND draft_body_text IS NOT NULL")
			.param("id", id)
			.query(Long.class)
			.single()).isEqualTo(1);
		assertThat(this.jdbc.sql("""
				SELECT s.score FROM grade_scores s JOIN grade_items g ON g.grade_item_id = s.grade_item_id
				 WHERE g.activity_id = :activity AND s.user_id = :user
				""").param("activity", activity).param("user", this.studentId).query(String.class).single())
			.isEqualTo("12.00");
		assertThat(auditLines("assignment.delete", id)).isEqualTo(1);
	}

	@Test
	void deletionNeedsTheInstructorAndTheRevisionReadLast() throws Exception {
		long id = assignment("");
		String path = assignment(this.course, id);
		long revision = ApiClient.json(this.professor.get(path)).get("revision").asLong();
		String current = "\"" + revision + "\"";
		for (Client denied : List.of(this.student, this.classmate, this.outsider)) {
			denied.delete(path, current).andExpect(status().isNotFound());
		}
		this.professor.delete(assignment(otherCourseOfProfessor(), id), current).andExpect(status().isNotFound());
		this.anonymous.delete(path, current).andExpect(status().isUnauthorized());
		this.professor.deleteWithoutCsrf(path, current).andExpect(status().isForbidden());
		this.professor.delete(path, null).andExpect(status().isPreconditionRequired());
		this.professor.delete(path, "\"x\"").andExpect(status().isBadRequest());
		this.professor.delete(path, "\"" + (revision + 1) + "\"")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		cancelCourse();
		this.professor.delete(path, current).andExpect(status().isNotFound());
		assertThat(auditLines("assignment.delete", id)).isZero();
		assertThat(notifications(id)).isPositive();
	}

	// 과제 알림의 대상은 과제 ID다(ADR-0036).
	private long notifications(long assignmentId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM notifications
				 WHERE notification_type IN ('ASSIGNMENT_OPENED', 'ASSIGNMENT_GRADED') AND target_ref_id = :id
				""")
			.param("id", assignmentId)
			.query(Long.class)
			.single();
	}

}
