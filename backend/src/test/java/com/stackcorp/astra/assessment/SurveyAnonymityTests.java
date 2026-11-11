package com.stackcorp.astra.assessment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * 익명 설문의 저장 모양(SC-05-A, SC-09, ASSESS-08, N-08). 응답 저장부의 행과 스키마에서 사용자로 가는 컬럼이나 join 경로가 없고,
 * 참여 원장과 감사 기록에는 답에서 나온 값과 requestId, 정밀 시각이 없음을 DB에서 직접 본다.
 */
class SurveyAnonymityTests extends SurveyTestSupport {

	private static final List<String> RESPONSE_STORE = List.of("survey_responses", "survey_answers",
			"survey_answer_options");

	@Test
	void theResponseStoreHasNoPathToTheRespondent() throws Exception {
		// 응답 저장부에서 밖으로 나가는 FK는 설문 정의 쪽과, 실명 설문에서만 채우는 respondent_user_id 하나뿐이다.
		List<Map<String, Object>> fks = this.jdbc.sql("""
				SELECT table_name AS t, column_name AS c, referenced_table_name AS r
				  FROM information_schema.key_column_usage
				 WHERE table_schema = DATABASE() AND referenced_table_name IS NOT NULL AND table_name IN (:tables)
				""").param("tables", RESPONSE_STORE).query().listOfRows();
		assertThat(fks).extracting((fk) -> fk.get("r").toString())
			.containsOnly("surveys", "survey_responses", "survey_answers", "survey_questions", "survey_question_options",
					"users");
		assertThat(fks).filteredOn((fk) -> "users".equals(fk.get("r")))
			.extracting((fk) -> fk.get("t") + "." + fk.get("c"))
			.containsExactly("survey_responses.respondent_user_id");
		// 참여 원장에서 응답 저장부로 가는 컬럼은 current_response_id 하나이고, 이 기능은 그 값을 쓰지 않는다.
		assertThat(this.jdbc.sql("""
				SELECT column_name FROM information_schema.key_column_usage
				 WHERE table_schema = DATABASE() AND table_name = 'survey_participations' AND referenced_table_name IN (:tables)
				""").param("tables", RESPONSE_STORE).query(String.class).list()).containsExactly("current_response_id");
		// 응답 저장부에는 요청이나 key를 담을 컬럼이 없다.
		assertThat(this.jdbc.sql("""
				SELECT column_name FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name IN (:tables)
				   AND (column_name LIKE '%key%' OR column_name LIKE '%request%' OR column_name LIKE '%fingerprint%'
				        OR column_name LIKE '%session%' OR column_name LIKE '%participation%')
				""").param("tables", RESPONSE_STORE).query(String.class).list()).isEmpty();
	}

	@Test
	void noStoredOrderOrTimeLinksRespondentsToTheirAnswers() throws Exception {
		// 학생 열 명이 정해진 순서로 완료한다. 서술형 답 "응답 i"가 i번째 완료한 학생의 것이다.
		long id = survey("");
		Form form = form(id);
		int respondents = 10;
		List<Long> students = new ArrayList<>();
		List<String> completionOrder = new ArrayList<>();
		for (int i = 0; i < respondents; i++) {
			long userId = this.data.user("STUDENT");
			this.data.enroll(this.course, userId);
			students.add(userId);
			completionOrder.add("응답 " + i);
			client(userId).submit(completion(this.course, id), "order-" + i, form.answers(i % 3, i % 5, "응답 " + i))
				.andExpect(status().isCreated());
		}
		// 참여 원장은 완료 순서를 안다(ID 순서가 위의 순서다).
		assertThat(this.jdbc.sql("""
				SELECT user_id FROM survey_participations WHERE survey_id = :survey ORDER BY survey_participation_id
				""").param("survey", id).query(Long.class).list()).containsExactlyElementsOf(students);

		// 응답 저장부의 행 ID는 순번이 아니다. 어느 ID로 줄을 세워도 완료 순서가 나오지 않는다.
		assertThat(this.jdbc.sql("""
				SELECT CONCAT(table_name, '.', column_name) FROM information_schema.columns
				 WHERE table_schema = DATABASE() AND table_name IN (:tables) AND extra LIKE '%auto_increment%'
				""").param("tables", RESPONSE_STORE).query(String.class).list()).isEmpty();
		for (String order : List.of("r.survey_response_id", "a.survey_answer_id",
				"(SELECT MIN(ao.survey_answer_option_id) FROM survey_answer_options ao"
						+ " JOIN survey_answers x ON x.survey_answer_id = ao.survey_answer_id"
						+ " WHERE x.survey_response_id = r.survey_response_id)")) {
			List<String> texts = this.jdbc.sql("""
					SELECT a.answer_text
					  FROM survey_answers a JOIN survey_responses r ON r.survey_response_id = a.survey_response_id
					 WHERE r.survey_id = :survey AND a.answer_text IS NOT NULL
					 ORDER BY\s""" + order).param("survey", id).query(String.class).list();
			assertThat(texts).as(order).containsExactlyInAnyOrderElementsOf(completionOrder);
			assertThat(texts).as(order).isNotEqualTo(completionOrder);
		}

		// 응답 쪽 시각은 모두 같은 날의 자정이라 순서를 주지 않는다.
		assertThat(this.jdbc.sql("""
				SELECT r.submitted_at FROM survey_responses r WHERE r.survey_id = :survey
				UNION SELECT r.created_at FROM survey_responses r WHERE r.survey_id = :survey
				UNION SELECT a.created_at FROM survey_answers a
				        JOIN survey_responses r ON r.survey_response_id = a.survey_response_id WHERE r.survey_id = :survey
				UNION SELECT ao.created_at FROM survey_answer_options ao
				        JOIN survey_answers a ON a.survey_answer_id = ao.survey_answer_id
				        JOIN survey_responses r ON r.survey_response_id = a.survey_response_id WHERE r.survey_id = :survey
				""").param("survey", id).query(LocalDateTime.class).list()).singleElement()
			.satisfies((stamp) -> assertThat(stamp.toLocalTime()).isEqualTo(LocalTime.MIDNIGHT));

		// 감사 기록도 requestId가 없고 발생 일시가 일 단위다. 응답 행과 맞출 요청 ID나 시각이 없다.
		List<Map<String, Object>> audits = this.jdbc.sql("""
				SELECT request_id, occurred_at FROM audit_events
				 WHERE operation = 'survey.complete' AND object_id = :survey AND actor_user_id IN (:students)
				""").param("survey", id).param("students", students).query().listOfRows();
		assertThat(audits).hasSize(respondents);
		for (Map<String, Object> audit : audits) {
			assertThat(audit.get("request_id")).isNull();
			assertMidnight(audit.get("occurred_at"));
		}

		// 집계는 그대로다. 단일 선택 i % 3, 척도 i % 5 + 1, 복수 선택은 모두 앞의 두 보기.
		closeSurvey(id);
		this.professor.get(results(this.course, id))
			.andExpect(jsonPath("$.disclosed").value(true))
			.andExpect(jsonPath("$.respondentCount").value(respondents))
			.andExpect(jsonPath("$.questions[0].options[0].count").value(4))
			.andExpect(jsonPath("$.questions[0].options[1].count").value(3))
			.andExpect(jsonPath("$.questions[0].options[2].count").value(3))
			.andExpect(jsonPath("$.questions[1].options[0].count").value(respondents))
			.andExpect(jsonPath("$.questions[1].options[1].count").value(respondents))
			.andExpect(jsonPath("$.questions[1].options[2].count").value(0))
			.andExpect(jsonPath("$.questions[2].scaleMean").value(3.0))
			.andExpect(jsonPath("$.questions[3].answerCount").value(respondents));
		this.professor.get(results(this.course, id) + "/questions/" + form.q(3) + "/texts?size=" + respondents)
			.andExpect(jsonPath("$.total").value(respondents))
			.andExpect(jsonPath("$.items[0]").value("응답 0"))
			.andExpect(jsonPath("$.items[9]").value("응답 9"));
	}

	@Test
	void anAnonymousCompletionWritesNoLinkBetweenTheStudentAndTheAnswers() throws Exception {
		long id = survey("");
		Form form = form(id);
		this.student.submit(completion(this.course, id), "anon-1", form.answers(1, 3, "익명 의견")).andExpect(status().isCreated());
		Map<String, Object> key = Map.of("survey", id, "user", this.studentId);

		// 응답 세트: 사용자 없음, 시각은 일 단위.
		Map<String, Object> response = row("""
				SELECT respondent_user_id, submitted_at, created_at FROM survey_responses WHERE survey_id = :survey
				""", key);
		assertThat(response.get("respondent_user_id")).isNull();
		assertMidnight(response.get("submitted_at"));
		assertMidnight(response.get("created_at"));
		for (LocalDateTime stamp : this.jdbc.sql("""
				SELECT a.created_at FROM survey_answers a JOIN survey_responses r ON r.survey_response_id = a.survey_response_id
				 WHERE r.survey_id = :survey
				UNION ALL
				SELECT ao.created_at FROM survey_answer_options ao
				  JOIN survey_answers a ON a.survey_answer_id = ao.survey_answer_id
				  JOIN survey_responses r ON r.survey_response_id = a.survey_response_id
				 WHERE r.survey_id = :survey
				""").param("survey", id).query(LocalDateTime.class).list()) {
			assertMidnight(stamp);
		}

		// 참여 원장: (설문, 사용자, key)와 최소 결과. 응답 세트를 가리키지 않고 정밀 시각이 없다.
		Map<String, Object> participation = row("""
				SELECT state, revision, completion_key, current_response_id, started_at, completed_at, created_at, updated_at
				  FROM survey_participations WHERE survey_id = :survey AND user_id = :user
				""", key);
		assertThat(participation.get("state")).isEqualTo("completed");
		assertThat(participation.get("completion_key")).isEqualTo("anon-1");
		assertThat(participation.get("current_response_id")).isNull();
		assertThat(participation.get("started_at")).isNull();
		assertThat(participation.get("updated_at")).isNull();
		assertMidnight(participation.get("completed_at"));
		assertMidnight(participation.get("created_at"));

		// 공용 멱등 원장에는 이 사용자의 완료 지문과 receipt가 없다.
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM idempotency_ledger WHERE principal_user_id = :user AND operation = 'survey.complete'
				""").param("user", this.studentId).query(Long.class).single()).isZero();

		// 감사 기록: 행위자와 설문, 결과만. requestId가 없고 응답 세트를 가리키지 않는다(SC-09).
		List<Map<String, Object>> audits = this.jdbc.sql("""
				SELECT object_type, object_id, result_code, request_id FROM audit_events
				 WHERE actor_user_id = :user AND operation = 'survey.complete'
				""").param("user", this.studentId).query().listOfRows();
		assertThat(audits).hasSize(1);
		assertThat(audits.get(0).get("object_type")).isEqualTo("survey");
		assertThat(((Number) audits.get(0).get("object_id")).longValue()).isEqualTo(id);
		assertThat(audits.get(0).get("result_code")).isEqualTo("ok");
		assertThat(audits.get(0).get("request_id")).isNull();
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM audit_events
				 WHERE actor_user_id = :user AND object_type IN ('survey_response', 'survey_answer')
				""").param("user", this.studentId).query(Long.class).single()).isZero();
	}

	@Test
	void aNamedCompletionKeepsTheRespondentAndUsesTheSharedLedger() throws Exception {
		long id = survey("\"anonymous\":false");
		this.student.submit(completion(this.course, id), "named-1", form(id).answers(0, 0, null))
			.andExpect(status().isCreated());
		assertThat(((Number) row("SELECT respondent_user_id FROM survey_responses WHERE survey_id = :survey",
				Map.of("survey", id)).get("respondent_user_id")).longValue()).isEqualTo(this.studentId);
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM idempotency_ledger
				 WHERE principal_user_id = :user AND operation = 'survey.complete' AND idempotency_key = 'named-1'
				""").param("user", this.studentId).query(Long.class).single()).isEqualTo(1);
		assertThat(this.jdbc.sql("""
				SELECT request_id FROM audit_events WHERE actor_user_id = :user AND operation = 'survey.complete'
				""").param("user", this.studentId).query(String.class).single()).isNotNull();
		assertThat(row("""
				SELECT current_response_id FROM survey_participations WHERE survey_id = :survey AND user_id = :user
				""", Map.of("survey", id, "user", this.studentId)).get("current_response_id")).isNull();
	}

	private static void assertMidnight(Object value) {
		LocalDateTime time = value instanceof LocalDateTime t ? t : ((java.sql.Timestamp) value).toLocalDateTime();
		assertThat(time.toLocalTime()).isEqualTo(LocalTime.MIDNIGHT);
	}

}
