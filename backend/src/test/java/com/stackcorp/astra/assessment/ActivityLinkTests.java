package com.stackcorp.astra.assessment;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 주차 목록, 할 일, 캘린더의 행과 과제·퀴즈·설문 목록의 행이 서로를 ID로 바로 가리킨다(learning.api.ActivityTargets). 평가 목록에는
 * 활동 ID가, 활동 쪽 목록에는 과제·퀴즈·설문 ID가 있다. 구간(phase)은 과제 화면과 주차 목록이 같은 DB 시계로 같은 값을 낸다.
 */
class ActivityLinkTests extends SurveyTestSupport {

	long assignmentId;

	long quizId;

	long surveyId;

	long report;

	long test;

	long poll;

	@BeforeEach
	void activities() throws Exception {
		this.assignmentId = assignment("");
		this.quizId = ApiClient.json(this.professor.postJson(QuizTestSupport.quizzes(this.course), """
				{"weekNo":2,"title":"퀴즈","closeAt":"2999-01-01T00:00:00","questions":%s}
				""".formatted(QuizTestSupport.QUESTIONS)).andExpect(status().isCreated())).get("quiz").get("quizId").asLong();
		this.surveyId = survey("");
		this.report = activityOf(this.assignmentId);
		this.test = this.jdbc.sql("SELECT activity_id FROM quizzes WHERE quiz_id = :id")
			.param("id", this.quizId)
			.query(Long.class)
			.single();
		this.poll = activityOfSurvey(this.surveyId);
	}

	@Test
	void assessmentListsCarryTheActivityIdAndActivityListsCarryTheAssessmentIds() throws Exception {
		for (Client who : new Client[] { this.professor, this.student }) {
			who.get(assignments(this.course)).andExpect(jsonPath("$[0].activityId").value(this.report));
			who.get(QuizTestSupport.quizzes(this.course)).andExpect(jsonPath("$[0].activityId").value(this.test));
			who.get(surveys(this.course)).andExpect(jsonPath("$[0].activityId").value(this.poll));
			who.get("/api/courses/" + this.course + "/weeks")
				.andExpect(jsonPath("$.weeks[*].activities[?(@.activityId == %d)].assignmentId", this.report)
					.value((int) this.assignmentId))
				.andExpect(jsonPath("$.weeks[*].activities[?(@.activityId == %d)].quizId", this.test)
					.value((int) this.quizId))
				.andExpect(jsonPath("$.weeks[*].activities[?(@.activityId == %d)].surveyId", this.poll)
					.value((int) this.surveyId))
				.andExpect(jsonPath("$.weeks[*].activities[?(@.activityId == %d)].quizId", this.report).isEmpty());
			who.get("/api/courses/" + this.course + "/activities/" + this.test)
				.andExpect(jsonPath("$.quizId").value(this.quizId))
				.andExpect(jsonPath("$.phase").value("open"));
		}
		this.student.get("/api/me/todo")
			.andExpect(jsonPath("$.items[?(@.activityId == %d)].assignmentId", this.report).value((int) this.assignmentId))
			.andExpect(jsonPath("$.items[?(@.activityId == %d)].quizId", this.test).value((int) this.quizId))
			.andExpect(jsonPath("$.items[?(@.activityId == %d)].surveyId", this.poll).value((int) this.surveyId));
		this.student.get("/api/me/home")
			.andExpect(jsonPath("$.todo.items[?(@.activityId == %d)].quizId", this.test).value((int) this.quizId));

		// 캘린더는 마감일 칸에 같은 ID를 붙인다. 학생과 담당자 모두 같다.
		this.jdbc.sql("UPDATE learning_activities SET close_at = CURRENT_TIMESTAMP + INTERVAL 2 DAY WHERE activity_id = :id")
			.param("id", this.test)
			.update();
		LocalDate day = this.jdbc.sql("SELECT DATE(close_at) FROM learning_activities WHERE activity_id = :id")
			.param("id", this.test)
			.query(LocalDate.class)
			.single();
		for (Client who : new Client[] { this.professor, this.student }) {
			who.get("/api/me/calendar/days/" + day)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activities[?(@.activityId == %d)].quizId", this.test).value((int) this.quizId));
		}
	}

	@Test
	void weekPhaseFollowsTheAssignmentLateWindow() throws Exception {
		schedule(this.assignmentId, "NULL", "CURRENT_TIMESTAMP - INTERVAL 1 HOUR");
		latePolicy(this.assignmentId, "allow_until", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		this.student.get(assignments(this.course) + "/" + this.assignmentId).andExpect(jsonPath("$.phase").value("late"));
		this.student.get("/api/courses/" + this.course + "/weeks")
			.andExpect(jsonPath("$.weeks[*].activities[?(@.activityId == %d)].phase", this.report).value("late"));
		this.student.get("/api/courses/" + this.course + "/activities/" + this.report)
			.andExpect(jsonPath("$.phase").value("late"))
			.andExpect(jsonPath("$.assignmentId").value(this.assignmentId));

		latePolicy(this.assignmentId, "allow_until", "CURRENT_TIMESTAMP - INTERVAL 1 MINUTE");
		this.student.get(assignments(this.course) + "/" + this.assignmentId).andExpect(jsonPath("$.phase").value("closed"));
		this.student.get("/api/courses/" + this.course + "/weeks")
			.andExpect(jsonPath("$.weeks[*].activities[?(@.activityId == %d)].phase", this.report).value("closed"));
	}

	@Test
	void todoCanBeNarrowedToOneOfMyCoursesAndCountedAlone() throws Exception {
		long other = this.data.course();
		this.data.enroll(other, this.studentId);
		long elsewhere = this.data.activity(other, "lecture_weeks", true, null, null);
		this.jdbc.sql("UPDATE learning_activities SET close_at = '2999-01-01 00:00:00' WHERE activity_id = :id")
			.param("id", elsewhere)
			.update();

		this.student.get("/api/me/todo").andExpect(jsonPath("$.total").value(4));
		this.student.get("/api/me/todo?courseId=" + this.course)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[?(@.courseId != %d)]", this.course).isEmpty());
		this.student.get("/api/me/todo?courseId=" + other + "&size=1")
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].activityId").value(elsewhere))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		// 쪽 나누기와 완료 거르기는 과목을 골라도 같다.
		String next = ApiClient.json(this.student.get("/api/me/todo?courseId=" + this.course + "&size=2")
			.andExpect(jsonPath("$.items.length()").value(2))).get("nextCursor").asString();
		this.student.get("/api/me/todo?courseId=" + this.course + "&size=2&cursor=" + next)
			.andExpect(jsonPath("$.items.length()").value(1));

		this.student.get("/api/me/todo/count")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.truncated").value(false))
			.andExpect(jsonPath("$.serverTime").isNotEmpty());
		this.student.get("/api/me/todo/count?courseId=" + this.course).andExpect(jsonPath("$.total").value(3));

		// 내 과목이 아니면 없는 과목이다. 담당 과목에는 할 일이 없다.
		this.outsider.get("/api/me/todo?courseId=" + this.course).andExpect(status().isNotFound());
		this.outsider.get("/api/me/todo/count?courseId=" + this.course).andExpect(status().isNotFound());
		this.professor.get("/api/me/todo?courseId=" + this.course).andExpect(jsonPath("$.total").value(0));
		this.anonymous.get("/api/me/todo/count").andExpect(status().isUnauthorized());
	}

}
