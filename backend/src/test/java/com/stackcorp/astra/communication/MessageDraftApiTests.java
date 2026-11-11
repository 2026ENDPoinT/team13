package com.stackcorp.astra.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 쪽지쓰기 자동저장. 본인 초안만 있고, 저장 순서는 클라이언트 revision으로 정해져 늦게 도착한 이전 저장이 새 초안을 덮지 못한다. 초안의
 * 과목과 받는사람은 읽을 때마다 지금의 범위로 다시 판정한다.
 */
class MessageDraftApiTests extends IntegrationTest {

	private static final String DRAFT = "/api/messages/draft";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	long prof;

	long stu;

	ApiClient professor;

	ApiClient student;

	@BeforeEach
	void people() {
		this.course = this.data.course();
		this.prof = this.data.user("PROFESSOR");
		this.stu = this.data.user("STUDENT");
		this.data.instructor(this.course, this.prof);
		this.data.enroll(this.course, this.stu);
		this.professor = new ApiClient(this.mvc, this.data.session(this.prof));
		this.student = new ApiClient(this.mvc, this.data.session(this.stu));
	}

	@Test
	void draftKeepsCourseAndRecipientsAndRechecksThemWhenRead() throws Exception {
		long mate = this.data.user("STUDENT");
		this.data.enroll(this.course, mate);
		put(this.professor, "{\"revision\":1,\"title\":\"안내\",\"body\":\"본문\",\"courseId\":" + this.course
				+ ",\"recipientIds\":[" + this.stu + "," + mate + "]}").andExpect(status().isOk());
		this.professor.get(DRAFT).andExpect(status().isOk())
			.andExpect(jsonPath("$.course.courseId").value(this.course))
			.andExpect(jsonPath("$.recipients.length()").value(2))
			.andExpect(jsonPath("$.recipients[0].userId").value(this.stu))
			.andExpect(jsonPath("$.recipients[0].name").value("테스트 STUDENT"))
			.andExpect(jsonPath("$.recipients[0].role").value("STUDENT"))
			.andExpect(jsonPath("$.droppedRecipients").value(0))
			.andExpect(jsonPath("$.courseDropped").value(false));

		// 수강을 취소한 사람은 초안에서 빠지고 수만 알린다. 보내기도 거부한다.
		this.jdbc.sql("UPDATE course_enrollments SET enrollment_status = 'CANCELLED' WHERE course_id = :c AND user_id = :u")
			.param("c", this.course)
			.param("u", mate)
			.update();
		this.professor.get(DRAFT)
			.andExpect(jsonPath("$.recipients.length()").value(1))
			.andExpect(jsonPath("$.recipients[0].userId").value(this.stu))
			.andExpect(jsonPath("$.droppedRecipients").value(1));
		this.professor.send(MockMvcRequestBuilders.post("/api/messages")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"안내\",\"body\":\"본문\",\"courseId\":" + this.course + ",\"recipientIds\":[" + mate + "]}")
			.header("Idempotency-Key", "draft-dropped-1"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("recipient_not_allowed"));

		// 범위 밖 사람의 ID를 저장해도 이름은 나오지 않는다.
		long stranger = this.data.user("STUDENT");
		put(this.student, "{\"revision\":1,\"title\":\"질문\",\"body\":\"\",\"recipientIds\":[" + this.prof + "," + stranger + "]}")
			.andExpect(status().isOk());
		this.student.get(DRAFT)
			.andExpect(jsonPath("$.recipients.length()").value(1))
			.andExpect(jsonPath("$.recipients[0].userId").value(this.prof))
			.andExpect(jsonPath("$.droppedRecipients").value(1))
			.andExpect(jsonPath("$.course").doesNotExist());

		// 폐강하면 과목도 빠지고, 그 과목으로만 닿던 받는사람도 빠진다.
		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c").param("c", this.course).update();
		this.professor.get(DRAFT)
			.andExpect(jsonPath("$.courseDropped").value(true))
			.andExpect(jsonPath("$.course").doesNotExist())
			.andExpect(jsonPath("$.recipients.length()").value(0))
			.andExpect(jsonPath("$.droppedRecipients").value(2));
		// 구성원이 아닌 과목은 저장하지 못한다. 받는사람 목록의 모양도 본다.
		put(this.professor, "{\"revision\":2,\"title\":\"t\",\"body\":\"b\",\"courseId\":" + this.course + "}")
			.andExpect(status().isNotFound());
		put(this.professor, "{\"revision\":2,\"title\":\"t\",\"body\":\"b\",\"recipientIds\":[" + this.stu + "," + this.stu + "]}")
			.andExpect(status().isBadRequest());
		put(this.professor, "{\"revision\":2,\"title\":\"t\",\"body\":\"b\",\"recipientIds\":[0]}").andExpect(status().isBadRequest());
	}

	@Test
	void lateOlderSaveCannotOverwriteANewerDraft() throws Exception {
		this.student.get(DRAFT).andExpect(status().isNoContent());
		save(this.student, 1, "질문", "첫 줄").andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.revision").value(1));
		save(this.student, 3, "질문", "셋째 저장").andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
		// 2번 요청이 늦게 도착했다. 저장하지 않고 409다.
		save(this.student, 2, "질문", "둘째 저장")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("draft_stale"));
		this.student.get(DRAFT)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revision").value(3))
			.andExpect(jsonPath("$.body").value("셋째 저장"));
		// 응답을 잃은 저장을 같은 revision과 같은 내용으로 다시 보내면 성공이다(SEC-03). 내용이 다르면 409다.
		save(this.student, 3, "질문", "셋째 저장").andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(3));
		save(this.student, 3, "질문", "다른 내용").andExpect(status().isConflict());
		this.student.get(DRAFT).andExpect(jsonPath("$.body").value("셋째 저장"));
	}

	@Test
	void concurrentSavesKeepTheHighestRevision() throws Exception {
		int writers = 8;
		List<Integer> order = new ArrayList<>();
		for (int i = 1; i <= writers; i++) {
			order.add(i);
		}
		Collections.shuffle(order);
		ExecutorService pool = Executors.newFixedThreadPool(writers);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (int revision : order) {
				results.add(pool.submit(() -> {
					start.await();
					return save(this.student, revision, "t", "v" + revision).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get()).isIn(200, 409);
			}
		}
		finally {
			pool.shutdownNow();
		}
		this.student.get(DRAFT).andExpect(jsonPath("$.revision").value(writers)).andExpect(jsonPath("$.body").value("v" + writers));
	}

	@Test
	void draftsBelongToTheirOwnerAndSendingClearsTheComposeDraft() throws Exception {
		save(this.student, 5, "교수님께", "<b>질문</b>").andExpect(status().isOk());
		// 다른 사람에게는 초안이 없다. 자기 칸에 저장해도 남의 초안에 닿지 않는다(T-21).
		this.professor.get(DRAFT).andExpect(status().isNoContent());
		save(this.professor, 1, "공지", "본문").andExpect(status().isOk());
		this.professor.delete(DRAFT).andExpect(status().isNoContent());
		this.professor.delete(DRAFT).andExpect(status().isNoContent());
		this.student.get(DRAFT).andExpect(jsonPath("$.title").value("교수님께")).andExpect(jsonPath("$.body").value("<b>질문</b>"));

		// 쪽지를 보내면 같은 transaction에서 쪽지쓰기 초안을 지운다.
		this.student.send(MockMvcRequestBuilders.post("/api/messages")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"교수님께\",\"body\":\"질문\",\"recipientIds\":[" + this.prof + "]}")
			.header("Idempotency-Key", "draft-send-1")).andExpect(status().isCreated());
		this.student.get(DRAFT).andExpect(status().isNoContent());
		// 지운 뒤에는 처음부터 다시 센다.
		save(this.student, 1, "", "").andExpect(status().isOk());
	}

	@Test
	void draftInputIsBoundedPlainTextAndNeedsSessionAndCsrf() throws Exception {
		put(this.student, "{\"title\":\"t\",\"body\":\"b\"}").andExpect(status().isBadRequest());
		put(this.student, "{\"revision\":0,\"title\":\"t\",\"body\":\"b\"}").andExpect(status().isBadRequest());
		put(this.student, "{\"revision\":4294967296,\"title\":\"t\",\"body\":\"b\"}").andExpect(status().isBadRequest());
		save(this.student, 1, "t", "x".repeat(20_001)).andExpect(status().isBadRequest());
		save(this.student, 1, "a\\u0007b", "b").andExpect(status().isBadRequest());
		put(this.student, "{\"revision\":1,\"title\":\"t\",\"body\":\"b\",\"userId\":" + this.prof + "}")
			.andExpect(status().isBadRequest());
		this.student.get(DRAFT).andExpect(status().isNoContent());

		ApiClient anonymous = new ApiClient(this.mvc, null);
		anonymous.get(DRAFT).andExpect(status().isUnauthorized());
		put(anonymous, "{\"revision\":1,\"title\":\"t\",\"body\":\"b\"}").andExpect(status().isUnauthorized());
		Cookie session = new Cookie("ASTRA_SESSION", this.data.session(this.stu));
		this.mvc.perform(MockMvcRequestBuilders.put(DRAFT)
			.cookie(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"revision\":1,\"title\":\"t\",\"body\":\"b\"}")).andExpect(status().isForbidden());
		this.mvc.perform(MockMvcRequestBuilders.delete(DRAFT).cookie(session)).andExpect(status().isForbidden());
		this.student.get(DRAFT).andExpect(status().isNoContent());
	}

	private ResultActions save(ApiClient client, long revision, String title, String body) throws Exception {
		return put(client, "{\"revision\":" + revision + ",\"title\":\"" + title + "\",\"body\":\"" + body + "\"}");
	}

	private ResultActions put(ApiClient client, String json) throws Exception {
		return client.send(MockMvcRequestBuilders.put(DRAFT).contentType(MediaType.APPLICATION_JSON).content(json));
	}

}
