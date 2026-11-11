package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

/**
 * 설문 삭제(ADR-0011, ADR-0037). 활동에 삭제 표시만 남기고 참여 원장과 응답 저장부는 건드리지 않는다. 그 commit부터 설문, 응답, 결과는
 * 누구에게도 없고 할 일에서 빠진다. 익명 설문의 응답 행은 삭제 뒤에도 사용자와 이어지지 않는다(ADR-0022).
 */
class SurveyDeletionTests extends SurveyTestSupport {

	@Test
	void aDeletedSurveyLeavesEveryScreenAndKeepsTheUnlinkedResponses() throws Exception {
		long id = survey("");
		long activity = activityOfSurvey(id);
		this.classmate.submit(completion(this.course, id), "done", form(id).answers(0, 0, "좋아요"))
			.andExpect(status().isCreated());
		this.student.get("/api/me/todo?size=50").andExpect(jsonPath("$.items[*].activityId").value(hasItem((int) activity)));
		long revision = ApiClient.json(this.professor.get(survey(this.course, id))).get("revision").asLong();
		String body = form(id).answers(1, 2, null);

		this.professor.delete(survey(this.course, id), "\"" + revision + "\"").andExpect(status().isNoContent());

		for (Client client : List.of(this.professor, this.student, this.classmate)) {
			client.get(survey(this.course, id)).andExpect(status().isNotFound());
			client.get(surveys(this.course)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
			client.get(results(this.course, id)).andExpect(status().isNotFound());
		}
		this.student.submit(completion(this.course, id), "late", body).andExpect(status().isNotFound());
		this.classmate.submit(completion(this.course, id), "done", body).andExpect(status().isNotFound());
		this.professor.putJson(survey(this.course, id), "{\"weekNo\":2,\"title\":\"x\",\"closeAt\":\"2999-01-01T00:00:00\",\"revision\":"
				+ (revision + 1) + "}")
			.andExpect(status().isNotFound());
		this.professor.delete(survey(this.course, id), "\"" + (revision + 1) + "\"").andExpect(status().isNotFound());
		this.student.get("/api/me/todo?size=50")
			.andExpect(jsonPath("$.items[*].activityId").value(not(hasItem((int) activity))));

		// 참여와 응답은 그대로이고, 응답 세트에는 여전히 사용자가 없다.
		assertThat(count("SELECT COUNT(*) FROM survey_participations WHERE survey_id = :survey", id)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM survey_responses WHERE survey_id = :survey", id)).isEqualTo(1);
		assertThat(count("SELECT COUNT(*) FROM survey_responses WHERE survey_id = :survey AND respondent_user_id IS NOT NULL",
				id)).isZero();
		assertThat(auditLines("survey.delete", id)).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_activities WHERE activity_id = :id AND deleted_at IS NOT NULL")
			.param("id", activity)
			.query(Long.class)
			.single()).isEqualTo(1);
	}

	@Test
	void deletionNeedsTheInstructorAndTheRevisionReadLast() throws Exception {
		long id = survey("\"anonymous\":false");
		String path = survey(this.course, id);
		long revision = ApiClient.json(this.professor.get(path)).get("revision").asLong();
		String current = "\"" + revision + "\"";
		for (Client denied : List.of(this.student, this.classmate, this.outsider)) {
			denied.delete(path, current).andExpect(status().isNotFound());
		}
		this.professor.delete(survey(otherCourseOfProfessor(), id), current).andExpect(status().isNotFound());
		this.anonymous.delete(path, current).andExpect(status().isUnauthorized());
		this.professor.deleteWithoutCsrf(path, current).andExpect(status().isForbidden());
		this.professor.delete(path, null).andExpect(status().isPreconditionRequired());
		this.professor.delete(path, "W/" + current).andExpect(status().isBadRequest());
		this.professor.delete(path, "\"" + (revision + 1) + "\"")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("edit_conflict"));
		cancelCourse();
		this.professor.delete(path, current).andExpect(status().isNotFound());
		assertThat(auditLines("survey.delete", id)).isZero();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_activities WHERE activity_id = :id AND deleted_at IS NULL")
			.param("id", activityOfSurvey(id))
			.query(Long.class)
			.single()).isEqualTo(1);
	}

}
