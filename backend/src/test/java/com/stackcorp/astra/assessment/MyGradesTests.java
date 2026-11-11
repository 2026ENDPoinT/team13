package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import com.stackcorp.astra.assessment.api.ReleasedGrades;
import com.stackcorp.astra.assessment.api.ReleasedGrades.ReleasedGrade;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;

/**
 * 학생 성적 화면(EP-0035, EP-0036). 공개하지 않은 항목의 점수, 의견, 채점 일시와 그것이 들어간 합계는 응답에 값으로도 속성으로도 없다
 * (SEC-14). 합계는 공개한 항목 가운데 점수를 받은 것만 더한다(ADR-0023).
 */
class MyGradesTests extends GradebookTestSupport {

	private static final String MARK = "MARK-5d0e 보고서 구성이 좋음";

	@Autowired
	ReleasedGrades releasedGrades;

	@Test
	void releasingAnItemAddsTheScorePropertiesAndWithholdingRemovesThem() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "25.5", MARK, 0),
				row(this.classmateId, "10", null, 0))
			.andExpect(status().isOk());

		assertWithheld();

		release(itemId, true).andExpect(status().isOk()).andExpect(jsonPath("$.released").value(true));
		this.student.get(myGrades(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[0].released").value(true))
			.andExpect(jsonPath("$.items[0].score").value(25.5))
			.andExpect(jsonPath("$.items[0].feedback").value(MARK))
			.andExpect(jsonPath("$.items[0].gradedAt").exists())
			.andExpect(jsonPath("$.total.score").value(25.5))
			.andExpect(jsonPath("$.total.maxScore").value(30.0))
			.andExpect(jsonPath("$.total.itemCount").value(1));
		assertThat(body(this.classmate.get(myGrades(this.course))
			.andExpect(jsonPath("$.items[0].score").value(10.0))
			.andExpect(jsonPath("$.items[0].feedback").doesNotExist()))).doesNotContain(MARK);

		release(itemId, false).andExpect(status().isOk()).andExpect(jsonPath("$.released").value(false));
		assertWithheld();
	}

	private void assertWithheld() throws Exception {
		String body = body(this.student.get(myGrades(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activitiesVisible").value(true))
			.andExpect(jsonPath("$.items[0].name").value("중간고사"))
			.andExpect(jsonPath("$.items[0].maxScore").value(30.0))
			.andExpect(jsonPath("$.items[0].released").value(false))
			.andExpect(jsonPath("$.items[0].score").doesNotExist())
			.andExpect(jsonPath("$.items[0].feedback").doesNotExist())
			.andExpect(jsonPath("$.items[0].gradedAt").doesNotExist())
			.andExpect(jsonPath("$.total").doesNotExist()));
		assertThat(body).doesNotContain(MARK).doesNotContain("\"score\"").doesNotContain("\"total\"");
	}

	@Test
	void totalsCountOnlyReleasedGradedItemsForStudentsAndEverythingForTheInstructor() throws Exception {
		long quiz = manualItem("퀴즈", "10", "40");
		long exam = manualItem("기말고사", "20", "60");
		long project = manualItem("프로젝트", "50", null);
		putScores(this.professor, this.course, quiz, row(this.studentId, "8", null, 0)).andExpect(status().isOk());
		putScores(this.professor, this.course, exam, row(this.studentId, "15", null, 0)).andExpect(status().isOk());
		release(quiz, true).andExpect(status().isOk());
		// 공개했지만 이 학생은 아직 점수가 없다. 0점으로 치지 않는다.
		release(project, true).andExpect(status().isOk());

		// 공개하지 않은 기말고사의 15점과 배점 20이 섞이면 23, 30, 77이 된다.
		this.student.get(myGrades(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(3))
			.andExpect(jsonPath("$.items[1].itemId").value(exam))
			.andExpect(jsonPath("$.items[1].score").doesNotExist())
			.andExpect(jsonPath("$.items[2].released").value(true))
			.andExpect(jsonPath("$.items[2].score").doesNotExist())
			.andExpect(jsonPath("$.total.score").value(8.0))
			.andExpect(jsonPath("$.total.maxScore").value(10.0))
			.andExpect(jsonPath("$.total.itemCount").value(1))
			.andExpect(jsonPath("$.total.weightedScore").value(32.0))
			.andExpect(jsonPath("$.total.weightTotal").value(40.0));

		this.professor.get(gradebook(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.students[0].studentId").value(this.studentId))
			.andExpect(jsonPath("$.students[0].total.score").value(23.0))
			.andExpect(jsonPath("$.students[0].total.maxScore").value(30.0))
			.andExpect(jsonPath("$.students[0].total.itemCount").value(2))
			.andExpect(jsonPath("$.students[0].total.weightedScore").value(77.0))
			.andExpect(jsonPath("$.students[0].total.weightTotal").value(100.0))
			.andExpect(jsonPath("$.items[1].released").value(false))
			.andExpect(jsonPath("$.items[1].gradedCount").value(1));

		// 공개한 항목에 점수가 하나도 없으면 합계 자체가 없다.
		this.classmate.get(myGrades(this.course)).andExpect(jsonPath("$.total").doesNotExist());
	}

	@Test
	void assignmentItemsFollowTheAssignmentVisibilityAndTheCourseCanHideTheWholeList() throws Exception {
		long open = assignment("");
		assignment("\"published\":false");
		long manual = manualItem("출석 점수", "10", null);

		this.student.get(myGrades(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].type").value("assignment"))
			.andExpect(jsonPath("$.items[0].assignmentId").value(open))
			.andExpect(jsonPath("$.items[0].closeAt").exists())
			.andExpect(jsonPath("$.items[0].submitted").value(false))
			.andExpect(jsonPath("$.items[0].maxScore").value(15.0))
			.andExpect(jsonPath("$.items[1].type").value("manual"))
			.andExpect(jsonPath("$.items[1].submitted").doesNotExist());
		this.student.get(myGrades(this.course) + "?type=manual")
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].itemId").value(manual));
		this.student.get(myGrades(this.course) + "?type=all&q=출석").andExpect(jsonPath("$.items.length()").value(1));
		this.student.get(myGrades(this.course) + "?type=report1").andExpect(status().isBadRequest());
		this.student.get(myGrades(this.course) + "?q=" + "가".repeat(101)).andExpect(status().isBadRequest());
		// 담당자에게는 공개하지 않은 과제의 항목도 보인다.
		this.professor.get(gradebook(this.course)).andExpect(jsonPath("$.items.length()").value(3));

		// 평가 활동을 비공개로 두면 목록과 합계가 응답에 없다(원본 43c).
		this.professor.putJson(settings(this.course), "{\"activitiesVisible\":false,\"revision\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(1));
		this.student.get(myGrades(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.activitiesVisible").value(false))
			.andExpect(jsonPath("$.items").doesNotExist())
			.andExpect(jsonPath("$.total").doesNotExist());
		// 읽은 번호가 다르면 덮어쓰지 않는다.
		this.professor.putJson(settings(this.course), "{\"activitiesVisible\":true,\"revision\":0}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		this.professor.putJson(settings(this.course), "{\"activitiesVisible\":true,\"revision\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(2));
		this.professor.get(gradebook(this.course)).andExpect(jsonPath("$.settings.activitiesVisible").value(true));
	}

	@Test
	void newlyReleasedGradesFollowTheSameReleaseRule() throws Exception {
		long itemId = manualItem("중간고사", "30", null);
		putScores(this.professor, this.course, itemId, row(this.studentId, "25", MARK, 0)).andExpect(status().isOk());
		LocalDateTime since = now().minusHours(1);
		assertThat(this.releasedGrades.releasedSince(this.studentId, List.of(this.course), since, now(), 10)).isEmpty();

		release(itemId, true).andExpect(status().isOk());
		List<ReleasedGrade> released = this.releasedGrades.releasedSince(this.studentId, List.of(this.course), since,
				now(), 10);
		assertThat(released).extracting(ReleasedGrade::gradeItemId).containsExactly(itemId);
		assertThat(released.get(0).releasedAt()).isNotNull();
		// 점수를 받지 않은 학생, 넘기지 않은 과목에는 없다.
		assertThat(this.releasedGrades.releasedSince(this.classmateId, List.of(this.course), since, now(), 10)).isEmpty();
		assertThat(this.releasedGrades.releasedSince(this.studentId, List.of(this.data.course()), since, now(), 10))
			.isEmpty();

		this.professor.putJson(settings(this.course), "{\"activitiesVisible\":false,\"revision\":0}")
			.andExpect(status().isOk());
		assertThat(this.releasedGrades.releasedSince(this.studentId, List.of(this.course), since, now(), 10)).isEmpty();
	}

	private LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

}
