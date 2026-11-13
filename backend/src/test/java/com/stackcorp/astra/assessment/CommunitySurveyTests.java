package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import com.stackcorp.astra.ApiClient;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * 알림마당 설문(EP-0163~EP-0167). 대상 행과 session의 역할·학년으로 대상자를 정하고, 대상이 아니면 목록에 없고 상세와 완료는
 * 404다. 완료와 익명 저장 규칙은 강의실 설문과 같다. 만드는 API가 없으므로 행을 직접 넣는다.
 */
class CommunitySurveyTests extends SurveyTestSupport {

	@Test
	void onlyTargetedUsersSeeAndCompleteACommunitySurvey() throws Exception {
		long second = this.data.user("STUDENT");
		this.jdbc.sql("UPDATE users SET grade = 2 WHERE user_id = :id").param("id", second).update();
		long third = this.data.user("STUDENT");
		this.jdbc.sql("UPDATE users SET grade = 3 WHERE user_id = :id").param("id", third).update();
		long staff = this.data.user("STAFF");
		long forGrade2 = communitySurvey("anonymous", "CURRENT_TIMESTAMP - INTERVAL 1 HOUR", "CURRENT_TIMESTAMP + INTERVAL 2 DAY",
				Map.of("grade", "2"));
		long forStaff = communitySurvey("anonymous", "CURRENT_TIMESTAMP - INTERVAL 1 HOUR", "CURRENT_TIMESTAMP + INTERVAL 1 DAY",
				Map.of("staff", ""));
		long noTargets = communitySurvey("anonymous", "CURRENT_TIMESTAMP - INTERVAL 1 HOUR", "CURRENT_TIMESTAMP + INTERVAL 1 DAY",
				Map.of());
		long notStarted = communitySurvey("anonymous", "CURRENT_TIMESTAMP + INTERVAL 1 HOUR", "CURRENT_TIMESTAMP + INTERVAL 1 DAY",
				Map.of("grade", "2"));

		Client grade2 = client(second);
		JsonNode list = ApiClient.json(grade2.get("/api/surveys").andExpect(status().isOk()));
		assertThat(ids(list)).contains(forGrade2).doesNotContain(forStaff, noTargets, notStarted);
		grade2.get("/api/surveys/" + forGrade2)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.survey.title").value("학교 설문"))
			.andExpect(jsonPath("$.survey.description").value("안내"))
			.andExpect(jsonPath("$.survey.activityId").doesNotExist())
			.andExpect(jsonPath("$.questions.length()").value(2));
		for (long hidden : new long[] { forStaff, noTargets, notStarted }) {
			grade2.get("/api/surveys/" + hidden).andExpect(status().isNotFound());
		}
		Client grade3 = client(third);
		grade3.get("/api/surveys/" + forGrade2).andExpect(status().isNotFound());
		assertThat(ids(ApiClient.json(client(staff).get("/api/surveys")))).contains(forStaff).doesNotContain(forGrade2);
		this.anonymous.get("/api/surveys").andExpect(status().isUnauthorized());

		// 강의실 경로로는 알림마당 설문이 없고, 알림마당 경로로는 강의실 설문이 없다(SC-03).
		long courseSurvey = survey("");
		this.student.get("/api/surveys/" + courseSurvey).andExpect(status().isNotFound());
		this.professor.get(survey(this.course, forGrade2)).andExpect(status().isNotFound());

		JsonNode q = ApiClient.json(grade2.get("/api/surveys/" + forGrade2)).get("questions");
		String body = "{\"answers\":[{\"questionId\":" + q.get(0).get("questionId").asLong() + ",\"optionIds\":["
				+ q.get(0).get("options").get(1).get("optionId").asLong() + "]}]}";
		grade3.submit("/api/surveys/" + forGrade2 + "/completion", "c", body).andExpect(status().isNotFound());
		grade2.submit("/api/surveys/" + forGrade2 + "/completion", "c", body)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.state").value("completed"));
		grade2.submit("/api/surveys/" + forGrade2 + "/completion", "c", body).andExpect(status().isCreated());
		grade2.submit("/api/surveys/" + forGrade2 + "/completion", "c2", body).andExpect(status().isConflict());
		grade2.get("/api/surveys/" + forGrade2)
			.andExpect(jsonPath("$.state").value("completed"))
			.andExpect(jsonPath("$.questions").doesNotExist());
		assertThat(participations(forGrade2)).isEqualTo(1);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM survey_responses WHERE survey_id = :s AND respondent_user_id IS NULL")
			.param("s", forGrade2)
			.query(Long.class)
			.single()).isEqualTo(1);

		// 결과는 공개 설정이 없으면 대상자에게도 없다.
		this.jdbc.sql("UPDATE surveys SET close_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE survey_id = :s")
			.param("s", forGrade2)
			.update();
		grade2.get("/api/surveys/" + forGrade2 + "/results").andExpect(status().isNotFound());
		this.jdbc.sql("UPDATE surveys SET result_visible_yn = 1 WHERE survey_id = :s").param("s", forGrade2).update();
		grade2.get("/api/surveys/" + forGrade2 + "/results")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.withheldReason").value("below_threshold"));
		grade3.get("/api/surveys/" + forGrade2 + "/results").andExpect(status().isNotFound());
	}

	@Test
	void theCommunityListIsPagedInCloseOrder() throws Exception {
		long user = this.data.user("STUDENT");
		long late = communitySurvey("named", "CURRENT_TIMESTAMP - INTERVAL 1 HOUR", "CURRENT_TIMESTAMP + INTERVAL 30 DAY",
				Map.of("user", Long.toString(user)));
		long early = communitySurvey("named", "CURRENT_TIMESTAMP - INTERVAL 1 HOUR", "CURRENT_TIMESTAMP + INTERVAL 20 DAY",
				Map.of("user", Long.toString(user)));
		Client client = client(user);
		JsonNode all = ApiClient.json(client.get("/api/surveys?size=100"));
		int total = all.get("total").asInt();
		assertThat(ids(all)).containsSubsequence(early, late);
		client.get("/api/surveys?size=1&page=" + total).andExpect(jsonPath("$.items.length()").value(1));
		client.get("/api/surveys?size=1&page=" + (total + 1)).andExpect(jsonPath("$.items.length()").value(0));
		client.get("/api/surveys?size=0").andExpect(status().isBadRequest());
	}

	/**
	 * 단일 선택(필수)과 서술형(선택) 두 문항의 알림마당 설문. 대상 구분 user는 테스트끼리 섞이지 않도록 쓰는 전체 대상(all)이다.
	 *
	 * @param targets 대상 구분별 값. user면 all 대상 행을 넣는다
	 */
	private long communitySurvey(String mode, String openAtSql, String closeAtSql, Map<String, String> targets) {
		long id = this.data.insert("""
				INSERT INTO surveys (title, description_text, response_mode, open_at, close_at)
				VALUES ('학교 설문', '안내', :mode, %s, %s)
				""".formatted(openAtSql, closeAtSql), Map.of("mode", mode));
		targets.forEach((type, value) -> this.jdbc.sql("""
				INSERT INTO survey_targets (survey_id, target_type, target_value) VALUES (:s, :type, :value)
				""").param("s", id).param("type", "user".equals(type) ? "all" : type)
			.param("value", value.isEmpty() ? null : value)
			.update());
		long single = this.data.insert("""
				INSERT INTO survey_questions (survey_id, question_no, question_type, question_text, is_mandatory_yn)
				VALUES (:s, 1, 'single', '만족합니까?', 1)
				""", Map.of("s", id));
		for (int i = 1; i <= 2; i++) {
			this.data.insert("""
					INSERT INTO survey_question_options (survey_question_id, option_no, option_text) VALUES (:q, :n, :t)
					""", Map.of("q", single, "n", i, "t", i == 1 ? "예" : "아니오"));
		}
		this.data.insert("""
				INSERT INTO survey_questions (survey_id, question_no, question_type, question_text, is_mandatory_yn)
				VALUES (:s, 2, 'essay', '의견', 0)
				""", Map.of("s", id));
		return id;
	}

	private static java.util.List<Long> ids(JsonNode page) {
		java.util.List<Long> ids = new java.util.ArrayList<>();
		page.get("items").forEach((item) -> ids.add(item.get("surveyId").asLong()));
		return ids;
	}

}
