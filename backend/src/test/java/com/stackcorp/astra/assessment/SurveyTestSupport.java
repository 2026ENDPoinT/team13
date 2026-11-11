package com.stackcorp.astra.assessment;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.stackcorp.astra.ApiClient;

import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.JsonNode;

/**
 * 설문 테스트의 공통 준비. 과제 테스트와 같은 사람들(담당자, 수강생 둘, 과목 밖 사용자)을 쓴다.
 * 문항은 단일 선택(필수), 복수 선택(필수, 1~2개), 척도(필수, 1~5), 서술형(선택) 순서다.
 */
abstract class SurveyTestSupport extends AssessmentTestSupport {

	static final String QUESTIONS = """
			[{"type":"single","text":"수업 난이도는?","options":["쉬움","보통","어려움"]},
			 {"type":"multiple","text":"도움이 된 것을 고르세요","minSelect":1,"maxSelect":2,"options":["강의","실습","과제"]},
			 {"type":"scale","text":"만족도","options":["1","2","3","4","5"]},
			 {"type":"essay","text":"하고 싶은 말","required":false}]
			""";

	/** 담당자가 이 과목에 설문을 만든다. @return 만든 설문 JSON */
	JsonNode createSurvey(long courseId, Client instructor, String extraFields) throws Exception {
		String body = """
				{"weekNo":2,"title":"중간 강의평가","description":"수업 개선에 씁니다","closeAt":"2999-01-01T00:00:00",
				 "questions":%s%s}
				""".formatted(QUESTIONS, extraFields.isEmpty() ? "" : "," + extraFields);
		return ApiClient.json(instructor.postJson(surveys(courseId), body).andExpect(status().isCreated()));
	}

	long survey(String extraFields) throws Exception {
		return createSurvey(this.course, this.professor, extraFields).get("survey").get("surveyId").asLong();
	}

	/** 문항 ID와 보기 ID. 담당자 상세에서 읽는다. */
	Form form(long surveyId) throws Exception {
		JsonNode questions = ApiClient.json(this.professor.get(survey(this.course, surveyId))).get("questions");
		List<Long> ids = new ArrayList<>();
		List<List<Long>> options = new ArrayList<>();
		for (JsonNode q : questions) {
			ids.add(q.get("questionId").asLong());
			List<Long> o = new ArrayList<>();
			q.get("options").forEach((n) -> o.add(n.get("optionId").asLong()));
			options.add(o);
		}
		return new Form(ids, options);
	}

	long activityOfSurvey(long surveyId) {
		return this.jdbc.sql("SELECT activity_id FROM surveys WHERE survey_id = :id")
			.param("id", surveyId)
			.query(Long.class)
			.single();
	}

	/** 설문 기간을 DB 시계 기준으로 옮긴다. 식은 SQL이고 CURRENT_TIMESTAMP를 쓴다. */
	void scheduleSurvey(long surveyId, String openAtSql, String closeAtSql) {
		this.jdbc.sql("UPDATE learning_activities SET open_at = " + openAtSql + ", close_at = " + closeAtSql
				+ " WHERE activity_id = :id").param("id", activityOfSurvey(surveyId)).update();
	}

	void closeSurvey(long surveyId) {
		scheduleSurvey(surveyId, "NULL", "CURRENT_TIMESTAMP - INTERVAL 1 SECOND");
	}

	long count(String sql, long surveyId) {
		return this.jdbc.sql(sql).param("survey", surveyId).query(Long.class).single();
	}

	long participations(long surveyId) {
		return count("SELECT COUNT(*) FROM survey_participations WHERE survey_id = :survey", surveyId);
	}

	long responses(long surveyId) {
		return count("SELECT COUNT(*) FROM survey_responses WHERE survey_id = :survey", surveyId);
	}

	long answers(long surveyId) {
		return count("""
				SELECT COUNT(*) FROM survey_answers a JOIN survey_responses r ON r.survey_response_id = a.survey_response_id
				 WHERE r.survey_id = :survey
				""", surveyId);
	}

	long chosenOptions(long surveyId) {
		return count("""
				SELECT COUNT(*) FROM survey_answer_options ao
				  JOIN survey_answers a ON a.survey_answer_id = ao.survey_answer_id
				  JOIN survey_responses r ON r.survey_response_id = a.survey_response_id
				 WHERE r.survey_id = :survey
				""", surveyId);
	}

	/** 새 수강생. */
	Client enrolledStudent() {
		long id = this.data.user("STUDENT");
		this.data.enroll(this.course, id);
		return client(id);
	}

	static String surveys(long courseId) {
		return "/api/courses/" + courseId + "/surveys";
	}

	static String survey(long courseId, long surveyId) {
		return surveys(courseId) + "/" + surveyId;
	}

	static String completion(long courseId, long surveyId) {
		return survey(courseId, surveyId) + "/completion";
	}

	static String results(long courseId, long surveyId) {
		return survey(courseId, surveyId) + "/results";
	}

	List<MockHttpServletResponse> race(int threads, Attempt attempt) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int n = i;
				Callable<MockHttpServletResponse> call = () -> {
					start.await();
					return attempt.run(n);
				};
				futures.add(pool.submit(call));
			}
			start.countDown();
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get(60, TimeUnit.SECONDS));
			}
			return responses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@FunctionalInterface
	interface Attempt {

		MockHttpServletResponse run(int n) throws Exception;

	}

	/** 문항 ID와 문항별 보기 ID. 순서는 {@link #QUESTIONS}와 같다. */
	record Form(List<Long> questions, List<List<Long>> options) {

		long q(int index) {
			return this.questions.get(index);
		}

		long o(int question, int option) {
			return this.options.get(question).get(option);
		}

		/**
		 * 필수 문항 셋에 답한 완전한 답. 서술형은 text가 null이면 넣지 않는다.
		 * @param single 단일 선택 보기 위치
		 * @param scale 척도 보기 위치(0이면 값 1)
		 */
		String answers(int single, int scale, String text) {
			StringBuilder json = new StringBuilder("{\"answers\":[");
			json.append(answer(q(0), List.of(o(0, single)), null)).append(',');
			json.append(answer(q(1), List.of(o(1, 0), o(1, 1)), null)).append(',');
			json.append(answer(q(2), List.of(o(2, scale)), null));
			if (text != null) {
				json.append(',').append(answer(q(3), List.of(), text));
			}
			return json.append("]}").toString();
		}

		static String answer(long questionId, List<Long> optionIds, String text) {
			return "{\"questionId\":" + questionId + ",\"optionIds\":" + optionIds + ",\"text\":"
					+ (text == null ? "null" : "\"" + text + "\"") + "}";
		}

	}

	/** 행 하나를 맵으로 읽는다. */
	Map<String, Object> row(String sql, Map<String, ?> params) {
		return this.jdbc.sql(sql).params(params).query().singleRow();
	}

}
