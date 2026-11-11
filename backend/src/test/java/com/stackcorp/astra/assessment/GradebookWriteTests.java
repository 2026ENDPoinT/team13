package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 성적부 쓰기(ADR-0023). 점수 일괄 저장은 한 행이라도 틀리면 아무것도 저장하지 않는다. 수정은 읽은 번호로 겹친 수정을 409로 막고, 점수는
 * 배점을 넘지 않으며, 과제 항목은 과제 화면이 정한 값을 성적부에서 바꾸지 못한다. 점수를 쓸 때마다 감사 기록이 남는다.
 */
class GradebookWriteTests extends GradebookTestSupport {

	@Test
	void oneBadRowRejectsTheWholeBatchAndNamesEveryBadRow() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "20", "좋음", 0), row(this.classmateId, "31", null, 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_rows"))
			.andExpect(jsonPath("$.rows.length()").value(1))
			.andExpect(jsonPath("$.rows[0].index").value(1))
			.andExpect(jsonPath("$.rows[0].code").value("score_exceeds_max"));
		assertThat(scoreRows(itemId)).isZero();

		long outsiderId = this.data.user("STUDENT");
		long thirdId = this.data.user("STUDENT");
		this.data.enroll(this.course, thirdId);
		putScores(this.professor, this.course, itemId, row(this.studentId, "1.555", null, 0),
				row(this.classmateId, "null", "의견만", 0), row(outsiderId, "1", null, 0), row(this.studentId, "1", null, 0),
				row(thirdId, "1", null, -1), "{\"studentId\":null,\"score\":1,\"feedback\":null,\"version\":0}", "null")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.rows.length()").value(7))
			.andExpect(jsonPath("$.rows[0].code").value("invalid_score"))
			.andExpect(jsonPath("$.rows[1].code").value("feedback_without_score"))
			.andExpect(jsonPath("$.rows[2].code").value("not_student"))
			.andExpect(jsonPath("$.rows[3].code").value("duplicate_student"))
			.andExpect(jsonPath("$.rows[4].code").value("invalid_version"))
			.andExpect(jsonPath("$.rows[5].code").value("invalid_student"))
			.andExpect(jsonPath("$.rows[6].index").value(6));
		putScores(this.professor, this.course, itemId, row(this.studentId, "-1", null, 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.rows[0].code").value("invalid_score"));
		// 의견에는 줄바꿈과 tab 말고는 제어 문자를 받지 않는다.
		putScores(this.professor, this.course, itemId, row(this.studentId, "1", "줄\\u0007바꿈", 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.rows[0].code").value("invalid_feedback"));
		assertThat(scoreRows(itemId)).isZero();

		putScores(this.professor, this.course, itemId, row(this.studentId, "20", "좋음", 0), row(this.classmateId, "30", null, 0))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rows.length()").value(2))
			.andExpect(jsonPath("$.rows[0].version").value(1))
			.andExpect(jsonPath("$.rows[1].score").value(30.0));
		assertThat(scoreRows(itemId)).isEqualTo(2);
	}

	@Test
	void aStaleVersionInAnyRowRejectsTheBatchWithAConflict() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "20", null, 0)).andExpect(status().isOk());
		// 학생 행은 이미 1이다. 같이 보낸 classmate 행도 저장되지 않는다.
		putScores(this.professor, this.course, itemId, row(this.classmateId, "10", null, 0), row(this.studentId, "25", null, 0))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"))
			.andExpect(jsonPath("$.rows.length()").value(1))
			.andExpect(jsonPath("$.rows[0].index").value(1));
		assertThat(scoreRows(itemId)).isEqualTo(1);
		this.professor.get(gradebook(this.course))
			.andExpect(jsonPath("$.students[0].scores[0].score").value(20.0))
			.andExpect(jsonPath("$.students[0].scores[0].version").value(1))
			.andExpect(jsonPath("$.students[1].scores").isEmpty());

		// 점수를 비우면 미채점이 되고 번호가 오른다.
		putScores(this.professor, this.course, itemId, row(this.studentId, "null", null, 1))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.rows[0].score").doesNotExist())
			.andExpect(jsonPath("$.rows[0].version").value(2));
		this.professor.get(gradebook(this.course)).andExpect(jsonPath("$.items[0].gradedCount").value(0));
	}

	@Test
	void concurrentEditsOfTheSameScoreLetExactlyOneWin() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		List<MockHttpServletResponse> responses = race(2, (n) -> putScores(this.professor, this.course, itemId,
				row(this.studentId, n == 0 ? "10" : "20", null, 0))
			.andReturn()
			.getResponse());
		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
		assertThat(responses.stream().filter((r) -> r.getStatus() == 409).findFirst().orElseThrow().getContentAsString())
			.contains("version_conflict");
		assertThat(this.jdbc.sql("SELECT revision_no FROM grade_scores WHERE grade_item_id = :i")
			.param("i", itemId)
			.query(Long.class)
			.single()).isEqualTo(1);
	}

	@Test
	void concurrentWritesToNeighbouringItemsAllSucceed() throws Exception {
		long first = manualItem("퀴즈 1", "10", null);
		long second = manualItem("퀴즈 2", "10", null);
		long[] items = { first, second, first, second };
		long[] students = { this.studentId, this.studentId, this.classmateId, this.classmateId };
		List<MockHttpServletResponse> responses = race(4, (n) -> putScores(this.professor, this.course, items[n],
				row(students[n], "5", null, 0))
			.andReturn()
			.getResponse());
		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsOnly(200);
		assertThat(scoreRows(first) + scoreRows(second)).isEqualTo(4);
	}

	@Test
	void editsUseTheRevisionTheClientReadAndNeverLowerTheMaximumBelowAScore() throws Exception {
		long itemId = manualItem("중간고사", "30", "20");
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", null, 0)).andExpect(status().isOk());
		this.professor.putJson(item(this.course, itemId), "{\"name\":\"중간\",\"maxScore\":24,\"weight\":20,\"revision\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("score_exceeds_max"));
		this.professor.putJson(item(this.course, itemId), "{\"name\":\"중간고사(수정)\",\"maxScore\":25,\"weight\":30,\"revision\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("중간고사(수정)"))
			.andExpect(jsonPath("$.maxScore").value(25.0))
			.andExpect(jsonPath("$.weight").value(30.0))
			.andExpect(jsonPath("$.revision").value(2));
		// 옛 탭이 읽은 번호로 저장하면 덮어쓰지 않는다.
		this.professor.putJson(item(this.course, itemId), "{\"name\":\"옛 탭\",\"maxScore\":30,\"revision\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		this.professor.putJson(item(this.course, itemId) + "/release", "{\"released\":true,\"revision\":1}")
			.andExpect(status().isConflict());
		// 배점을 줄인 뒤에는 새 배점으로 검사한다.
		putScores(this.professor, this.course, itemId, row(this.classmateId, "26", null, 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.rows[0].code").value("score_exceeds_max"));
		// 직접 입력 항목은 이름과 배점을 함께 보내야 한다.
		this.professor.putJson(item(this.course, itemId), "{\"weight\":10,\"revision\":2}").andExpect(status().isBadRequest());
	}

	@Test
	void assignmentItemsKeepTheirNameMaximumReleaseAndScoresInTheAssignment() throws Exception {
		long assignmentId = assignment("");
		long itemId = gradeItemOf(assignmentId);
		this.professor.get(gradebook(this.course))
			.andExpect(jsonPath("$.items[0].type").value("assignment"))
			.andExpect(jsonPath("$.items[0].assignmentId").value(assignmentId))
			.andExpect(jsonPath("$.items[0].managedByAssignment").value(true));
		for (String body : new String[] { "{\"name\":\"바꾼 이름\",\"revision\":1}", "{\"maxScore\":100,\"revision\":1}" }) {
			this.professor.putJson(item(this.course, itemId), body)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("managed_by_assignment"));
		}
		this.professor.putJson(item(this.course, itemId) + "/release", "{\"released\":true,\"revision\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("managed_by_assignment"));
		putScores(this.professor, this.course, itemId, row(this.studentId, "10", null, 0))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("managed_by_assignment"));
		this.professor.delete(item(this.course, itemId) + "?revision=1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("managed_by_assignment"));
		assertThat(scoreRows(itemId)).isZero();

		// 비율은 성적부가 정한다.
		this.professor.putJson(item(this.course, itemId), "{\"weight\":25,\"revision\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.weight").value(25.0))
			.andExpect(jsonPath("$.name").value("과제 1"))
			.andExpect(jsonPath("$.released").value(false))
			.andExpect(jsonPath("$.revision").value(2));
		// 과제를 고치면 항목 번호도 올라, 그 전에 읽은 성적부 화면의 수정은 409다.
		this.professor.putJson(assignment(this.course, assignmentId),
				"{\"weekNo\":3,\"title\":\"과제 1\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":20,\"scoresReleased\":true,\"revision\":1}")
			.andExpect(status().isOk());
		this.professor.putJson(item(this.course, itemId), "{\"weight\":30,\"revision\":2}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		this.professor.get(gradebook(this.course))
			.andExpect(jsonPath("$.items[0].maxScore").value(20.0))
			.andExpect(jsonPath("$.items[0].weight").value(25.0))
			.andExpect(jsonPath("$.items[0].released").value(true))
			.andExpect(jsonPath("$.items[0].revision").value(3));
	}

	@Test
	void anItemWithRecordedScoresCannotBeDeletedUntilTheScoresAreCleared() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", "의견", 0)).andExpect(status().isOk());
		this.professor.delete(item(this.course, itemId) + "?revision=1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("item_has_scores"));
		this.professor.delete(item(this.course, itemId)).andExpect(status().isBadRequest());
		putScores(this.professor, this.course, itemId, row(this.studentId, "null", null, 1)).andExpect(status().isOk());
		release(itemId, true).andExpect(status().isOk());
		this.professor.delete(item(this.course, itemId) + "?revision=1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		this.professor.delete(item(this.course, itemId) + "?revision=2").andExpect(status().isNoContent());
		assertThat(scoreRows(itemId)).isZero();
		this.professor.get(gradebook(this.course)).andExpect(jsonPath("$.items").isEmpty());
		this.professor.delete(item(this.course, itemId) + "?revision=2").andExpect(status().isNotFound());
	}

	@Test
	void everyScoreWriteIsAuditedWithoutItsValue() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", "의견", 0), row(this.classmateId, "7", null, 0))
			.andExpect(status().isOk());
		long assignmentId = assignment("");
		long submissionId = json(this.student.submit(submission(this.course, assignmentId), "grade-audit-1",
				submitBody(0, TEXT))
			.andExpect(status().isCreated())).get("submissionId").asLong();
		this.professor.putJson(submissions(this.course, assignmentId) + "/" + submissionId + "/grade", "{\"score\":12}")
			.andExpect(status().isOk());

		List<Long> scoreIds = this.jdbc.sql("""
				SELECT grade_score_id FROM grade_scores s JOIN grade_items g ON g.grade_item_id = s.grade_item_id
				 WHERE g.course_id = :c ORDER BY grade_score_id
				""").param("c", this.course).query(Long.class).list();
		List<Long> audited = this.jdbc.sql("""
				SELECT object_id FROM audit_events
				 WHERE operation = 'grade_score.write' AND object_type = 'grade_score' AND course_id = :c AND result_code = 'ok'
				 ORDER BY object_id
				""").param("c", this.course).query(Long.class).list();
		assertThat(audited).hasSize(3).containsExactlyElementsOf(scoreIds);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = 'grade_item.create' AND object_id = :i")
			.param("i", itemId)
			.query(Long.class)
			.single()).isEqualTo(1);
		// 거부한 일괄 저장은 아무것도 남기지 않는다.
		putScores(this.professor, this.course, itemId, row(this.studentId, "99", null, 1)).andExpect(status().isBadRequest());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = 'grade_score.write' AND course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single()).isEqualTo(3);
	}

	@Test
	void requestsAreBounded() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		List<String> rows = new ArrayList<>();
		for (int i = 0; i <= GradebookService.MAX_ROWS; i++) {
			rows.add(row(this.studentId, "1", null, 0));
		}
		putScores(this.professor, this.course, itemId, rows.toArray(String[]::new)).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		putScores(this.professor, this.course, itemId).andExpect(status().isBadRequest());
		this.professor.get(gradebook(this.course) + "?size=101").andExpect(status().isBadRequest());
		this.professor.get(gradebook(this.course) + "?page=0").andExpect(status().isBadRequest());
		for (String bad : new String[] { "{\"name\":\"" + "가".repeat(201) + "\",\"maxScore\":10}", "{\"name\":\"a\",\"maxScore\":0}",
				"{\"name\":\"a\",\"maxScore\":1000.01}", "{\"name\":\"a\",\"maxScore\":10,\"weight\":100.01}",
				"{\"name\":\"a\",\"maxScore\":10,\"revision\":1}", "{\"name\":\"a\",\"maxScore\":10,\"createdBy\":1}",
				"{\"name\":\"\",\"maxScore\":10}" }) {
			this.professor.postJson(items(this.course), bad).andExpect(status().isBadRequest());
		}
		this.professor.putJson(settings(this.course), "{\"activitiesVisible\":false}").andExpect(status().isBadRequest());
		this.professor.putJson(item(this.course, itemId) + "/release", "{\"released\":true}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void theGradebookPagesStudentsInRosterOrderAndCreateIsIdempotent() throws Exception {
		long third = this.data.user("STUDENT");
		this.data.enroll(this.course, third);
		String body = "{\"name\":\"발표\",\"maxScore\":10}";
		long first = json(createWithKey("grade-item-key-1", body).andExpect(status().isCreated())).get("itemId").asLong();
		createWithKey("grade-item-key-1", body).andExpect(status().isCreated()).andExpect(jsonPath("$.itemId").value(first));
		createWithKey("grade-item-key-1", "{\"name\":\"다른 항목\",\"maxScore\":10}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM grade_items WHERE course_id = :c").param("c", this.course)
			.query(Long.class).single()).isEqualTo(1);

		this.professor.get(gradebook(this.course) + "?page=1&size=2")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalStudents").value(3))
			.andExpect(jsonPath("$.students.length()").value(2))
			.andExpect(jsonPath("$.students[0].studentId").value(this.studentId))
			.andExpect(jsonPath("$.students[1].studentId").value(this.classmateId))
			.andExpect(jsonPath("$.students[0].name").exists());
		this.professor.get(gradebook(this.course) + "?page=2&size=2")
			.andExpect(jsonPath("$.students.length()").value(1))
			.andExpect(jsonPath("$.students[0].studentId").value(third));
		this.professor.get(gradebook(this.course) + "?page=3&size=2").andExpect(jsonPath("$.students").isEmpty());
	}

	private ResultActions createWithKey(String key, String body) throws Exception {
		return this.professor.send(MockMvcRequestBuilders.post(items(this.course))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header("Idempotency-Key", key));
	}

}
