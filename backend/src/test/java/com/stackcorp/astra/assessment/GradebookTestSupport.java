package com.stackcorp.astra.assessment;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.stackcorp.astra.ApiClient;

import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.ResultActions;

import tools.jackson.databind.JsonNode;

/**
 * 성적부 테스트의 공통 준비. 사람과 과목은 {@link AssessmentTestSupport}가 테스트마다 새로 만든다.
 */
abstract class GradebookTestSupport extends AssessmentTestSupport {

	/** 담당자가 직접 입력 항목을 만든다. @return 항목 ID */
	long manualItem(String name, String maxScore, String weight) throws Exception {
		String body = "{\"name\":\"" + name + "\",\"maxScore\":" + maxScore + (weight == null ? "" : ",\"weight\":" + weight)
				+ "}";
		return json(this.professor.postJson(items(this.course), body).andExpect(status().isCreated())).get("itemId")
			.asLong();
	}

	long revisionOf(long itemId) {
		return this.jdbc.sql("SELECT revision_no FROM grade_items WHERE grade_item_id = :id")
			.param("id", itemId)
			.query(Long.class)
			.single();
	}

	long gradeItemOf(long assignmentId) {
		return this.jdbc.sql("""
				SELECT g.grade_item_id FROM grade_items g JOIN assignments a ON a.activity_id = g.activity_id
				 WHERE a.assignment_id = :id
				""").param("id", assignmentId).query(Long.class).single();
	}

	long scoreRows(long itemId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM grade_scores WHERE grade_item_id = :id")
			.param("id", itemId)
			.query(Long.class)
			.single();
	}

	ResultActions release(long itemId, boolean released) throws Exception {
		return this.professor.putJson(item(this.course, itemId) + "/release",
				"{\"released\":" + released + ",\"revision\":" + revisionOf(itemId) + "}");
	}

	ResultActions putScores(Client client, long courseId, long itemId, String... rows) throws Exception {
		return client.putJson(item(courseId, itemId) + "/scores", "{\"rows\":[" + String.join(",", rows) + "]}");
	}

	static String row(long studentId, String score, String feedback, long version) {
		return "{\"studentId\":" + studentId + ",\"score\":" + score + ",\"feedback\":"
				+ (feedback == null ? "null" : "\"" + feedback + "\"") + ",\"version\":" + version + "}";
	}

	static String items(long courseId) {
		return "/api/courses/" + courseId + "/grade-items";
	}

	static String item(long courseId, long itemId) {
		return items(courseId) + "/" + itemId;
	}

	static String gradebook(long courseId) {
		return "/api/courses/" + courseId + "/gradebook";
	}

	static String myGrades(long courseId) {
		return "/api/courses/" + courseId + "/grades/me";
	}

	static String settings(long courseId) {
		return "/api/courses/" + courseId + "/grade-settings";
	}

	static JsonNode json(ResultActions result) throws Exception {
		return ApiClient.json(result);
	}

	static String body(ResultActions result) throws Exception {
		return result.andReturn().getResponse().getContentAsString();
	}

	/** 여러 thread가 한꺼번에 시작해 부른다. */
	static List<MockHttpServletResponse> race(int threads, Attempt attempt) throws Exception {
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

}
