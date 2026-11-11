package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

/**
 * 담당자의 과제 등록·수정·조회와 학생의 과제 조회. 권한은 과목 관계, 과제의 과목, 공개·시작 일시로만 정해진다(SC-02, SC-03).
 */
class AssignmentApiTests extends AssessmentTestSupport {

	@Test
	void instructorCreatesEditsAndListsAssignmentsOfOwnCourse() throws Exception {
		long id = ApiClient.json(this.professor.postJson(assignments(this.course), """
				{"weekNo":3,"title":"  정규화 보고서 ","description":"<b>3NF</b>까지\\n분석","openAt":"2026-01-01T09:00:00.700",
				 "closeAt":"2999-01-01T23:59:59","latePolicy":"allow_until","lateUntil":"2999-01-02T23:59:59","maxScore":15,
				 "allowFile":false}
				""")
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.assignment.title").value("정규화 보고서"))
			.andExpect(jsonPath("$.assignment.description").value("<b>3NF</b>까지\n분석"))
			.andExpect(jsonPath("$.assignment.openAt").value("2026-01-01T09:00:00"))
			.andExpect(jsonPath("$.assignment.weekNo").value(3))
			.andExpect(jsonPath("$.assignment.latePolicy").value("allow_until"))
			.andExpect(jsonPath("$.assignment.maxScore").value(15.0))
			.andExpect(jsonPath("$.assignment.allowText").value(true))
			.andExpect(jsonPath("$.assignment.allowFile").value(false))
			.andExpect(jsonPath("$.assignment.published").value(true))
			.andExpect(jsonPath("$.assignment.scoresReleased").value(false))
			.andExpect(jsonPath("$.revision").value(1))
			.andExpect(jsonPath("$.phase").value("open"))
			.andExpect(jsonPath("$.submittedCount").value(0))).get("assignment").get("assignmentId").asLong();

		// 활동은 learning이 가진다. 주차 목록에 과제(report)로 나온다.
		this.student.get("/api/courses/" + this.course + "/weeks")
			.andExpect(jsonPath("$[?(@.weekNo == 3)].activities[0].type").value("report"));

		String edit = """
				{"weekNo":4,"title":"정규화 보고서(수정)","closeAt":"2999-02-01T00:00:00","maxScore":20,"revision":%d}
				""";
		this.professor.putJson(assignment(this.course, id), edit.formatted(1))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.assignment.title").value("정규화 보고서(수정)"))
			.andExpect(jsonPath("$.assignment.weekNo").value(4))
			.andExpect(jsonPath("$.assignment.latePolicy").value("none"))
			.andExpect(jsonPath("$.assignment.maxScore").value(20.0))
			.andExpect(jsonPath("$.revision").value(2));
		// 다른 탭이 옛 수정 번호로 저장하면 덮어쓰지 않는다.
		this.professor.putJson(assignment(this.course, id), edit.replace("(수정)", "(옛 탭)").formatted(1))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		this.professor.get(assignment(this.course, id))
			.andExpect(jsonPath("$.assignment.title").value("정규화 보고서(수정)"))
			.andExpect(jsonPath("$.revision").value(2));

		this.professor.get(assignments(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].assignmentId").value(id))
			.andExpect(jsonPath("$[0].submittedCount").value(0))
			.andExpect(jsonPath("$[0].status").doesNotExist());
	}

	@Test
	void studentSeesOnlyPublishedStartedAssignmentsAndOnlyOwnStatus() throws Exception {
		long open = assignment("");
		long hidden = assignment("\"published\":false");
		long future = assignment("\"openAt\":\"2998-01-01T00:00:00\"");

		this.professor.get(assignments(this.course)).andExpect(jsonPath("$.length()").value(3));
		this.student.get(assignments(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].assignmentId").value(open))
			.andExpect(jsonPath("$[0].status").value("not_submitted"))
			.andExpect(jsonPath("$[0].submittedCount").doesNotExist());
		this.student.get(assignment(this.course, open))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.phase").value("open"))
			.andExpect(jsonPath("$.serverTime").exists())
			.andExpect(jsonPath("$.revision").doesNotExist())
			.andExpect(jsonPath("$.submittedCount").doesNotExist())
			.andExpect(jsonPath("$.submission.status").value("not_submitted"))
			.andExpect(jsonPath("$.submission.version").value(0))
			.andExpect(jsonPath("$.submission.grade").doesNotExist());
		for (long id : new long[] { hidden, future }) {
			this.student.get(assignment(this.course, id)).andExpect(status().isNotFound());
			this.student.submit(submission(this.course, id), "k-" + id, submitBody(0, TEXT)).andExpect(status().isNotFound());
		}
		// 지운 활동(ADR-0011 삭제 표시)의 과제는 담당자와 학생 모두에게 목록, 상세, 제출에서 사라진다.
		this.jdbc.sql("""
				UPDATE learning_activities a JOIN assignments s ON s.activity_id = a.activity_id
				   SET a.deleted_at = CURRENT_TIMESTAMP(3) WHERE s.assignment_id = :id
				""").param("id", open).update();
		this.professor.get(assignments(this.course)).andExpect(jsonPath("$.length()").value(2));
		this.student.get(assignments(this.course)).andExpect(jsonPath("$.length()").value(0));
		this.professor.get(assignment(this.course, open)).andExpect(status().isNotFound());
		this.student.get(assignment(this.course, open)).andExpect(status().isNotFound());
		this.student.submit(submission(this.course, open), "k-deleted-" + open, submitBody(0, TEXT))
			.andExpect(status().isNotFound());
	}

	@Test
	void hidesAssignmentsFromEveryoneOutsideTheCourseAndFromOtherCoursePaths() throws Exception {
		long id = assignment("");
		long otherCourse = this.data.course();
		// 두 과목을 모두 담당하는 교수도 다른 과목 경로로는 이 과제에 닿지 않는다(SC-03).
		this.jdbc.sql("""
				INSERT INTO course_instructors (course_id, user_id, instructor_role)
				SELECT :other, user_id, 'PROFESSOR' FROM course_instructors WHERE course_id = :course
				""").param("other", otherCourse).param("course", this.course).update();
		String edit = "{\"weekNo\":3,\"title\":\"바꿈\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":15,\"revision\":1}";

		this.professor.get(assignment(otherCourse, id)).andExpect(status().isNotFound());
		this.professor.putJson(assignment(otherCourse, id), edit).andExpect(status().isNotFound());
		this.professor.get(assignments(otherCourse)).andExpect(jsonPath("$.length()").value(0));

		this.outsider.get(assignments(this.course)).andExpect(status().isNotFound());
		this.outsider.get(assignment(this.course, id)).andExpect(status().isNotFound());
		this.outsider.putJson(assignment(this.course, id), edit).andExpect(status().isNotFound());
		this.student.putJson(assignment(this.course, id), edit).andExpect(status().isNotFound());
		this.student.postJson(assignments(this.course),
				"{\"weekNo\":1,\"title\":\"학생 과제\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":1}")
			.andExpect(status().isNotFound());
		this.anonymous.get(assignments(this.course)).andExpect(status().isUnauthorized());
		this.anonymous.get(assignment(this.course, id)).andExpect(status().isUnauthorized());
		this.professor.postWithoutCsrf(assignments(this.course),
				"{\"weekNo\":1,\"title\":\"t\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":1}")
			.andExpect(status().isForbidden());
		this.professor.putWithoutCsrf(assignment(this.course, id), edit).andExpect(status().isForbidden());
		this.professor.get(assignment(this.course, 999_999_999L)).andExpect(status().isNotFound());
		this.professor.get(assignment(this.course, id)).andExpect(jsonPath("$.assignment.title").value("과제 1"));
	}

	@Test
	void rejectsInvalidBodiesActorFieldsAndUnknownFields() throws Exception {
		String base = "\"weekNo\":1,\"title\":\"과제\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":10";
		String[] invalid = { "{" + base + ",\"userId\":1}", "{" + base + ",\"courseId\":1}",
				"{\"weekNo\":1,\"title\":\"과제\",\"maxScore\":10}",
				"{" + base + ",\"openAt\":\"2999-01-01T00:00:00\"}", "{" + base + ",\"latePolicy\":\"penalty\"}",
				"{" + base + ",\"latePolicy\":\"allow_until\"}",
				"{" + base + ",\"latePolicy\":\"allow_until\",\"lateUntil\":\"2998-12-31T00:00:00\"}",
				"{" + base + ",\"lateUntil\":\"2999-02-01T00:00:00\"}", "{" + base + ",\"allowText\":false,\"allowFile\":false}",
				"{" + base + ",\"revision\":1}", "{" + base.replace("10", "0") + "}", "{" + base.replace("10", "1.234") + "}",
				"{" + base.replace("10", "1001") + "}", "{" + base.replace("\"과제\"", "\"a\\u0000b\"") + "}",
				"{" + base.replace("\"weekNo\":1", "\"weekNo\":0") + "}", "{not json" };
		for (String body : invalid) {
			this.professor.postJson(assignments(this.course), body)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_request"));
		}
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_activities WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single()).isZero();
		long id = assignment("");
		this.professor.putJson(assignment(this.course, id),
				"{\"weekNo\":3,\"title\":\"과제\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":15}")
			.andExpect(status().isBadRequest());
		this.professor.putJson(assignment(this.course, id),
				"{\"weekNo\":3,\"title\":\"과제\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":15,\"revision\":1,\"createdBy\":2}")
			.andExpect(status().isBadRequest());
	}

	/**
	 * 과제 등록·수정, 수정으로 바꾼 점수 공개, 최종 제출은 감사 기록을 남긴다. 공개가 바뀐 수정만 성적 항목의 공개·거둠 줄이 붙고, 같은
	 * key의 제출 재전송은 줄을 더 남기지 않는다. 제목과 본문은 남기지 않는다(보안 검토 L2, SC-09).
	 */
	@Test
	void assignmentWritesScoreReleaseFlipsAndFinalSubmissionsLeaveAuditLines() throws Exception {
		long id = assignment("\"scoresReleased\":true");
		long gradeItem = this.jdbc.sql("SELECT grade_item_id FROM grade_items WHERE activity_id = :a")
			.param("a", activityOf(id))
			.query(Long.class)
			.single();
		String edit = "{\"weekNo\":3,\"title\":\"과제 1\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":15,\"revision\":%d%s}";
		// 공개 → 거둠 → 그대로(거둠) → 공개
		this.professor.putJson(assignment(this.course, id), edit.formatted(1, ",\"scoresReleased\":false"))
			.andExpect(status().isOk());
		this.professor.putJson(assignment(this.course, id), edit.formatted(2, ""))
			.andExpect(status().isOk());
		this.professor.putJson(assignment(this.course, id), edit.formatted(3, ",\"scoresReleased\":true"))
			.andExpect(status().isOk());
		// 거부한 수정(옛 revision)은 남기지 않는다.
		this.professor.putJson(assignment(this.course, id), edit.formatted(1, ",\"scoresReleased\":false"))
			.andExpect(status().isConflict());

		assertThat(this.jdbc.sql("""
				SELECT CONCAT(operation, ':', object_type) FROM audit_events
				 WHERE course_id = :course AND ((object_type = 'assignment' AND object_id = :id)
				       OR (object_type = 'grade_item' AND object_id = :item))
				 ORDER BY audit_event_id
				""").param("course", this.course).param("id", id).param("item", gradeItem).query(String.class).list())
			.containsExactly("assignment.create:assignment", "grade_item.release:grade_item",
					"assignment.update:assignment", "grade_item.withhold:grade_item", "assignment.update:assignment",
					"assignment.update:assignment", "grade_item.release:grade_item");

		String body = submitBody(0, "제출 본문");
		this.student.submit(submission(this.course, id), "audit-1", body).andExpect(status().isCreated());
		this.student.submit(submission(this.course, id), "audit-1", body).andExpect(status().isCreated());
		this.student.submit(submission(this.course, id), "audit-2", submitBody(1, "다시 제출")).andExpect(status().isCreated());
		long submissionId = this.jdbc.sql(
				"SELECT assignment_submission_id FROM assignment_submissions WHERE assignment_id = :a AND user_id = :u")
			.param("a", id)
			.param("u", this.studentId)
			.query(Long.class)
			.single();
		assertThat(this.jdbc.sql("""
				SELECT object_type, object_id, course_id, request_id FROM audit_events
				 WHERE actor_user_id = :user AND operation = 'assignment.submit'
				""").param("user", this.studentId).query().listOfRows()).hasSize(2).allSatisfy((row) -> {
					assertThat(row.get("object_type")).isEqualTo("assignment_submission");
					assertThat(((Number) row.get("object_id")).longValue()).isEqualTo(submissionId);
					assertThat(((Number) row.get("course_id")).longValue()).isEqualTo(this.course);
					assertThat(row.get("request_id")).isNotNull();
				});
	}

}
