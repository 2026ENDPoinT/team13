package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

/**
 * 성적부의 권한(SC-02, SC-03, SEC-14). 담당자만 성적부를 읽고 쓴다. 학생은 자기 성적만 읽고, 어떤 parameter로도 다른 학생의 점수를
 * 읽거나 쓰지 못한다. 권한이 없거나 경로의 과목과 항목이 맞지 않으면 404다.
 */
class GradebookAccessTests extends GradebookTestSupport {

	private static final String MARK = "MARK-91c2 발표 우수";

	@Test
	void onlyTheCourseInstructorReadsAndWritesTheGradebook() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		String scores = item(this.course, itemId) + "/scores";
		String rows = "{\"rows\":[" + row(this.studentId, "25", MARK, 0) + "]}";
		String edit = "{\"name\":\"중간\",\"maxScore\":30,\"revision\":1}";
		String release = "{\"released\":true,\"revision\":1}";
		for (Client other : new Client[] { this.student, this.classmate, this.outsider }) {
			other.get(gradebook(this.course)).andExpect(status().isNotFound());
			other.postJson(items(this.course), "{\"name\":\"가짜\",\"maxScore\":10}").andExpect(status().isNotFound());
			other.putJson(item(this.course, itemId), edit).andExpect(status().isNotFound());
			other.putJson(item(this.course, itemId) + "/release", release).andExpect(status().isNotFound());
			other.putJson(scores, rows).andExpect(status().isNotFound());
			other.putJson(settings(this.course), "{\"activitiesVisible\":false,\"revision\":0}")
				.andExpect(status().isNotFound());
			other.delete(item(this.course, itemId) + "?revision=1").andExpect(status().isNotFound());
		}
		this.anonymous.get(gradebook(this.course)).andExpect(status().isUnauthorized());
		this.anonymous.get(myGrades(this.course)).andExpect(status().isUnauthorized());
		this.anonymous.putJson(scores, rows).andExpect(status().isUnauthorized());
		this.professor.putWithoutCsrf(scores, rows).andExpect(status().isForbidden());
		this.professor.postWithoutCsrf(items(this.course), "{\"name\":\"가짜\",\"maxScore\":10}")
			.andExpect(status().isForbidden());
		assertThat(scoreRows(itemId)).isZero();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM grade_items WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single()).isEqualTo(1);

		// 성적부 응답은 담당자에게만, 캐시하지 않는다.
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", MARK, 0)).andExpect(status().isOk());
		this.professor.get(gradebook(this.course))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.students[0].scores[0].feedback").value(MARK));
		// 담당자는 자기 성적 화면이 없다.
		this.professor.get(myGrades(this.course)).andExpect(status().isNotFound());
		this.outsider.get(myGrades(this.course)).andExpect(status().isNotFound());
	}

	@Test
	void anotherCoursePathNeverReachesTheItem() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		long otherCourse = this.data.course();
		long prof = this.jdbc.sql("SELECT user_id FROM course_instructors WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single();
		this.data.instructor(otherCourse, prof);
		this.data.enroll(otherCourse, this.studentId);

		putScores(this.professor, otherCourse, itemId, row(this.studentId, "25", null, 0)).andExpect(status().isNotFound());
		this.professor.putJson(item(otherCourse, itemId), "{\"name\":\"중간\",\"maxScore\":30,\"revision\":1}")
			.andExpect(status().isNotFound());
		this.professor.putJson(item(otherCourse, itemId) + "/release", "{\"released\":true,\"revision\":1}")
			.andExpect(status().isNotFound());
		this.professor.delete(item(otherCourse, itemId) + "?revision=1").andExpect(status().isNotFound());
		this.professor.get(gradebook(otherCourse)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
		assertThat(scoreRows(itemId)).isZero();
		assertThat(revisionOf(itemId)).isEqualTo(1);

		// 다른 과목에서 공개한 항목도 이 과목 학생 성적에 섞이지 않는다.
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", null, 0)).andExpect(status().isOk());
		release(itemId, true).andExpect(status().isOk());
		this.student.get(myGrades(otherCourse)).andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty())
			.andExpect(jsonPath("$.total").doesNotExist());
	}

	@Test
	void aStudentReadsOnlyTheirOwnScoresWhateverParametersTheySend() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", MARK, 0),
				row(this.classmateId, "10", "보통", 0))
			.andExpect(status().isOk());
		release(itemId, true).andExpect(status().isOk());

		String[] probes = { myGrades(this.course), myGrades(this.course) + "?studentId=" + this.studentId,
				myGrades(this.course) + "?userId=" + this.studentId + "&user_id=" + this.studentId,
				myGrades(this.course) + "?ud=" + this.studentId };
		for (String probe : probes) {
			String body = body(this.classmate.get(probe)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.items.length()").value(1))
				.andExpect(jsonPath("$.items[0].score").value(10))
				.andExpect(jsonPath("$.total.score").value(10)));
			assertThat(body).doesNotContain(MARK);
		}
		// 학생 경로 말고는 모두 404다. 자기 ID로도 점수를 쓰지 못한다.
		this.classmate.get(gradebook(this.course) + "?studentId=" + this.studentId).andExpect(status().isNotFound());
		putScores(this.classmate, this.course, itemId, row(this.classmateId, "30", null, 1))
			.andExpect(status().isNotFound());
		// 본문에 작성자나 채점자 같은 필드를 넣으면 400이다.
		this.professor.putJson(item(this.course, itemId) + "/scores",
				"{\"rows\":[" + row(this.studentId, "20", null, 1).replace("}", ",\"gradedBy\":1}") + "]}")
			.andExpect(status().isBadRequest());
		// 수강생이 아닌 사람에게는 점수를 주지 못한다.
		long prof = this.jdbc.sql("SELECT user_id FROM course_instructors WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single();
		putScores(this.professor, this.course, itemId, row(prof, "1", null, 0))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_rows"))
			.andExpect(jsonPath("$.rows[0].code").value("not_student"));
	}

}
