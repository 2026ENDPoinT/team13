package com.stackcorp.astra.communication;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 강의실 게시판 테스트의 공통 준비. 테스트마다 새 과목에 담당자, 수강생 둘, 과목 밖 사용자를 만든다.
 */
abstract class BoardTestSupport extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	long prof;

	long stu;

	long mate;

	ApiClient professor;

	ApiClient student;

	ApiClient classmate;

	ApiClient outsider;

	ApiClient anonymous;

	@BeforeEach
	void people() {
		this.course = this.data.course();
		this.prof = this.data.user("PROFESSOR");
		this.stu = this.data.user("STUDENT");
		this.mate = this.data.user("STUDENT");
		this.data.instructor(this.course, this.prof);
		this.data.enroll(this.course, this.stu);
		this.data.enroll(this.course, this.mate);
		this.professor = new ApiClient(this.mvc, this.data.session(this.prof));
		this.student = new ApiClient(this.mvc, this.data.session(this.stu));
		this.classmate = new ApiClient(this.mvc, this.data.session(this.mate));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		this.anonymous = new ApiClient(this.mvc, null);
	}

	ResultActions put(ApiClient client, String path, String json) throws Exception {
		return client.send(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	ResultActions put(ApiClient client, String path) throws Exception {
		return client.send(MockMvcRequestBuilders.put(path));
	}

	ResultActions postWithKey(ApiClient client, String path, String json, String key) throws Exception {
		return client.send(MockMvcRequestBuilders.post(path)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)
			.header("Idempotency-Key", key));
	}

	ResultActions get(ApiClient client, String path, String... params) throws Exception {
		MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path);
		for (int i = 0; i < params.length; i += 2) {
			request.param(params[i], params[i + 1]);
		}
		return client.send(request);
	}

	/** session은 있지만 CSRF token이 없는 요청. */
	ResultActions withoutCsrf(MockHttpServletRequestBuilder request, long userId) throws Exception {
		return this.mvc.perform(request.cookie(new Cookie("ASTRA_SESSION", this.data.session(userId)))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{}"));
	}

	/** 용도 post_attachment로 텍스트 파일을 올린다. @return uploadId */
	long upload(ApiClient client, String name, String text) throws Exception {
		return ApiClient.json(client.upload(this.course, "post_attachment", name, text.getBytes(StandardCharsets.UTF_8), null)
			.andExpect(status().isCreated())).get("uploadId").asLong();
	}

	long created(ResultActions result, String idField) throws Exception {
		return ApiClient.json(result.andExpect(status().isCreated())).get(idField).asLong();
	}

	long count(String sql, long id) {
		return this.jdbc.sql(sql).param("id", id).query(Long.class).single();
	}

	long audits(String operation, long objectId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = :op AND object_id = :id")
			.param("op", operation)
			.param("id", objectId)
			.query(Long.class)
			.single();
	}

	/** 요청들을 동시에 시작하고 HTTP 상태 코드를 돌려준다. */
	static List<Integer> concurrently(List<Callable<ResultActions>> requests) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(requests.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (Callable<ResultActions> request : requests) {
				results.add(pool.submit(() -> {
					start.await();
					return request.call().andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> result : results) {
				statuses.add(result.get());
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

}
