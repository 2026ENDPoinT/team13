package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * 성적부의 항목 하나(오프라인 점수표). 항목은 성적부와 같은 모양이고 학생마다 그 항목의 점수, 의견, 수정 번호가 있다. 권한은 성적부와
 * 같고, 지운 활동의 항목과 다른 과목의 항목은 404다. 쿼리 수는 학생 수에 관계없다.
 */
class GradebookItemTests extends GradebookTestSupport {

	private static final String MARK = "MARK-5d1e 발표 우수";

	@Test
	void instructorReadsOneItemWithEachStudentsScoreAndVersion() throws Exception {
		long itemId = manualItem("중간고사", "30", "20");
		manualItem("기말고사", "40", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", MARK, 0)).andExpect(status().isOk());
		long first = Math.min(this.studentId, this.classmateId);
		long second = Math.max(this.studentId, this.classmateId);
		boolean studentFirst = first == this.studentId;

		JsonNode sheet = json(this.professor.get(sheet(this.course, itemId))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.courseId").value(this.course))
			.andExpect(jsonPath("$.serverTime").exists())
			.andExpect(jsonPath("$.item.itemId").value(itemId))
			.andExpect(jsonPath("$.item.name").value("중간고사"))
			.andExpect(jsonPath("$.item.type").value("manual"))
			.andExpect(jsonPath("$.item.maxScore").value(30.0))
			.andExpect(jsonPath("$.item.weight").value(20.0))
			.andExpect(jsonPath("$.item.released").value(false))
			.andExpect(jsonPath("$.item.managedByAssignment").value(false))
			.andExpect(jsonPath("$.item.managedByQuiz").value(false))
			.andExpect(jsonPath("$.item.gradedCount").value(1))
			.andExpect(jsonPath("$.item.assignmentId").doesNotExist())
			.andExpect(jsonPath("$.totalStudents").value(2))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(50))
			.andExpect(jsonPath("$.students.length()").value(2))
			.andExpect(jsonPath("$.students[0].studentId").value(first))
			.andExpect(jsonPath("$.students[1].studentId").value(second)));
		JsonNode graded = sheet.get("students").get(studentFirst ? 0 : 1);
		JsonNode empty = sheet.get("students").get(studentFirst ? 1 : 0);
		assertThat(graded.get("score").decimalValue()).isEqualByComparingTo("25");
		assertThat(graded.get("feedback").asString()).isEqualTo(MARK);
		assertThat(graded.get("gradedAt").isNull()).isFalse();
		assertThat(graded.get("version").asLong()).isEqualTo(1);
		// 점수가 없는 학생도 속성은 모두 있고, 수정 번호는 일괄 저장에 그대로 보낼 0이다.
		assertThat(empty.get("score").isNull()).isTrue();
		assertThat(empty.get("feedback").isNull()).isTrue();
		assertThat(empty.get("gradedAt").isNull()).isTrue();
		assertThat(empty.get("version").asLong()).isZero();
		assertThat(empty.has("name")).isTrue();

		// 읽은 수정 번호로 바로 저장한다.
		putScores(this.professor, this.course, itemId, row(this.studentId, "26", null, graded.get("version").asLong()),
				row(this.classmateId, "10", null, empty.get("version").asLong()))
			.andExpect(status().isOk());

		// 학생 쪽은 성적부와 같이 나눈다.
		this.professor.get(sheet(this.course, itemId) + "?page=2&size=1")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.item.gradedCount").value(2))
			.andExpect(jsonPath("$.students.length()").value(1))
			.andExpect(jsonPath("$.students[0].studentId").value(second))
			.andExpect(jsonPath("$.students[0].version").value(1))
			.andExpect(jsonPath("$.totalStudents").value(2));
		this.professor.get(sheet(this.course, itemId) + "?page=3&size=1")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.students").isEmpty());
		for (String bad : new String[] { "?page=0", "?size=0", "?size=101" }) {
			this.professor.get(sheet(this.course, itemId) + bad)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_request"));
		}
	}

	@Test
	void onlyTheCourseInstructorReadsTheItemAndOnlyThroughItsCourse() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", MARK, 0)).andExpect(status().isOk());
		for (Client other : new Client[] { this.student, this.classmate, this.outsider }) {
			String body = body(other.get(sheet(this.course, itemId)).andExpect(status().isNotFound()));
			assertThat(body).doesNotContain(MARK);
		}
		this.anonymous.get(sheet(this.course, itemId)).andExpect(status().isUnauthorized());
		// 다른 과목 경로와 없는 항목은 404다.
		long otherCourse = otherCourseOfProfessor();
		this.professor.get(sheet(otherCourse, itemId)).andExpect(status().isNotFound());
		this.professor.get(sheet(this.course, itemId + 100_000)).andExpect(status().isNotFound());
		cancelCourse();
		this.professor.get(sheet(this.course, itemId)).andExpect(status().isNotFound());
	}

	@Test
	void anAssignmentItemIsGoneOnceItsActivityIsDeleted() throws Exception {
		long assignmentId = assignment("");
		long itemId = gradeItemOf(assignmentId);
		this.professor.get(sheet(this.course, itemId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.item.type").value("assignment"))
			.andExpect(jsonPath("$.item.assignmentId").value(assignmentId))
			.andExpect(jsonPath("$.item.managedByAssignment").value(true))
			.andExpect(jsonPath("$.item.gradedCount").value(0))
			.andExpect(jsonPath("$.students[0].score").value(nullValue()));

		this.jdbc.sql("UPDATE learning_activities SET deleted_at = CURRENT_TIMESTAMP(3) WHERE activity_id = :a")
			.param("a", activityOf(assignmentId))
			.update();
		this.professor.get(sheet(this.course, itemId)).andExpect(status().isNotFound());
	}

	@Test
	void statementCountDoesNotGrowWithTheNumberOfStudents() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", null, 0)).andExpect(status().isOk());
		long few = statements(itemId);
		for (int i = 0; i < 6; i++) {
			long extra = this.data.user("STUDENT");
			this.data.enroll(this.course, extra);
			putScores(this.professor, this.course, itemId, row(extra, "1" + i, "의견", 0)).andExpect(status().isOk());
		}
		long many = statements(itemId);
		assertThat(many).isEqualTo(few);
	}

	/** 항목 하나를 읽는 동안 DB가 받은 문장 수. 서버 전체 counter라 다른 작업이 끼면 늘어나기만 하므로 세 번 재어 가장 작은 값을 쓴다. */
	private long statements(long itemId) throws Exception {
		long min = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			long before = questions();
			this.professor.get(sheet(this.course, itemId)).andExpect(status().isOk());
			min = Math.min(min, questions() - before);
		}
		return min;
	}

	private long questions() {
		return this.jdbc.sql("SHOW GLOBAL STATUS LIKE 'Questions'").query((rs, n) -> rs.getLong(2)).single();
	}

	private static String sheet(long courseId, long itemId) {
		return gradebook(courseId) + "/items/" + itemId;
	}

}
