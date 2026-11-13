package com.stackcorp.astra.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 쪽지. 보내는 사람과 보관함의 주인은 session으로만 정해지고, 받는사람은 서버가 계산한 범위 안에서만 고를 수 있다. 보관함은 사람마다
 * 따로 지워지며, 읽음과 회수는 같은 수신자 행의 배타적인 조건부 전이다.
 */
class MessageApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	long otherCourse;

	long prof;

	long stu1;

	long stu2;

	long otherProf;

	long otherStu;

	ApiClient professor;

	ApiClient student1;

	ApiClient student2;

	ApiClient otherProfessor;

	ApiClient outsider;

	@BeforeEach
	void people() {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		this.course = this.data.course();
		this.otherCourse = this.data.course();
		this.prof = user("김교수" + tag, "PROFESSOR");
		this.stu1 = user("이학생" + tag, "STUDENT");
		this.stu2 = user("박학생" + tag, "STUDENT");
		this.otherProf = user("최교수" + tag, "PROFESSOR");
		this.otherStu = user("정학생" + tag, "STUDENT");
		this.data.instructor(this.course, this.prof);
		this.data.enroll(this.course, this.stu1);
		this.data.enroll(this.course, this.stu2);
		this.data.instructor(this.otherCourse, this.otherProf);
		this.data.enroll(this.otherCourse, this.otherStu);
		this.professor = client(this.prof);
		this.student1 = client(this.stu1);
		this.student2 = client(this.stu2);
		this.otherProfessor = client(this.otherProf);
		this.outsider = client(user("외부인" + tag, "STUDENT"));
	}

	@Test
	void instructorSendsToStudentsAndReadingIsAnExplicitCommand() throws Exception {
		// 사진 유무는 같은 과목 사람끼리만 true다(아바타 경로와 같은 규칙).
		this.data.photo(this.prof);
		this.data.photo(this.stu1);
		JsonNode sent = ApiClient.json(send(this.professor, compose("중간고사 안내", "10월 21일\n본관 101호", this.course,
				this.stu1, this.stu2), "send-1")
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.recipients.length()").value(2))
			.andExpect(jsonPath("$.recipients[?(@.userId == " + this.stu1 + ")].hasPhoto").value(contains(true)))
			.andExpect(jsonPath("$.recipients[?(@.userId == " + this.stu2 + ")].hasPhoto").value(contains(false))));
		long id = sent.get("messageId").asLong();

		this.student1.get("/api/messages/inbox")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.unreadCount").value(1))
			.andExpect(jsonPath("$.items[0].messageId").value(id))
			.andExpect(jsonPath("$.items[0].sender.userId").value(this.prof))
			.andExpect(jsonPath("$.items[0].sender.hasPhoto").value(true))
			.andExpect(jsonPath("$.items[0].read").value(false))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		// 상세를 여러 번 열어도 읽음이 남지 않는다(SC-04).
		this.student1.get(inbox(id)).andExpect(status().isOk());
		this.student1.get(inbox(id))
			.andExpect(jsonPath("$.body").value("10월 21일\n본관 101호"))
			.andExpect(jsonPath("$.read").value(false))
			.andExpect(jsonPath("$.course.courseId").value(this.course))
			.andExpect(jsonPath("$.sender.hasPhoto").value(true));
		this.student1.get("/api/messages/unread-count").andExpect(jsonPath("$.unreadCount").value(1));

		// 읽음은 처음 한 번만 바뀌고 응답에 서버의 안 읽은 수가 있다(COMM-02).
		this.student1.post(inbox(id) + "/read")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.wasUnread").value(true))
			.andExpect(jsonPath("$.unreadCount").value(0));
		String firstReadAt = readAt(id, this.stu1);
		this.student1.post(inbox(id) + "/read")
			.andExpect(jsonPath("$.wasUnread").value(false))
			.andExpect(jsonPath("$.unreadCount").value(0));
		assertThat(readAt(id, this.stu1)).isEqualTo(firstReadAt);
		this.student1.get(inbox(id)).andExpect(jsonPath("$.read").value(true));
		this.student2.get("/api/messages/inbox").andExpect(jsonPath("$.unreadCount").value(1));

		this.professor.get("/api/messages/sent")
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].recipientCount").value(2))
			.andExpect(jsonPath("$.items[0].readCount").value(1))
			.andExpect(jsonPath("$.items[0].firstRecipient.userId").value(Math.min(this.stu1, this.stu2)))
			.andExpect(jsonPath("$.items[0].firstRecipient.hasPhoto").value(this.stu1 < this.stu2));
		this.professor.get(sentPath(id))
			.andExpect(jsonPath("$.recipients.length()").value(2))
			.andExpect(jsonPath("$.recipients[?(@.userId == " + this.stu2 + ")].readAt").value(contains(nullValue())));
	}

	@Test
	void everyEndpointFollowsTheAuthorizationMatrix() throws Exception {
		long id = sentId(send(this.professor, compose("안내", "본문", this.course, this.stu1), "matrix-1"));

		// 수신자: 받은함에서만 보인다.
		this.student1.get(inbox(id)).andExpect(status().isOk());
		this.student1.get(sentPath(id)).andExpect(status().isNotFound());
		this.student1.post(sentPath(id) + "/recall").andExpect(status().isNotFound());
		this.student1.delete(sentPath(id)).andExpect(status().isNotFound());
		// 발신자: 보낸함에서만 보인다. 받은함 경로로는 읽음을 남기지 못한다.
		this.professor.get(sentPath(id)).andExpect(status().isOk());
		this.professor.get(inbox(id)).andExpect(status().isNotFound());
		this.professor.post(inbox(id) + "/read").andExpect(status().isNotFound());
		this.professor.delete(inbox(id)).andExpect(status().isNotFound());
		send(this.professor, "{\"title\":\"답\",\"body\":\"답\"}", inbox(id) + "/reply", "matrix-2")
			.andExpect(status().isNotFound());
		// 같은 과목의 다른 학생, 과목 밖의 사람: 모든 경로가 존재를 드러내지 않는 404다(COMM-13).
		for (ApiClient stranger : List.of(this.student2, this.outsider, this.otherProfessor)) {
			stranger.get(inbox(id)).andExpect(status().isNotFound());
			stranger.get(sentPath(id)).andExpect(status().isNotFound());
			stranger.post(inbox(id) + "/read").andExpect(status().isNotFound());
			stranger.delete(inbox(id)).andExpect(status().isNotFound());
			stranger.delete(sentPath(id)).andExpect(status().isNotFound());
			stranger.post(sentPath(id) + "/recall").andExpect(status().isNotFound());
			send(stranger, "{\"title\":\"답\",\"body\":\"답\"}", inbox(id) + "/reply", "matrix-3")
				.andExpect(status().isNotFound());
			send(stranger, compose("전달", "본문", null, this.prof), inbox(id) + "/forward", "matrix-4")
				.andExpect(status().isNotFound());
			send(stranger, compose("전달", "본문", null, this.prof), sentPath(id) + "/forward", "matrix-5")
				.andExpect(status().isNotFound());
			stranger.get("/api/messages/inbox").andExpect(jsonPath("$.total").value(0));
		}
		assertThat(readAt(id, this.stu1)).isNull();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM message_recipients WHERE message_id = :m AND canceled_at IS NOT NULL")
			.param("m", id)
			.query(Long.class)
			.single()).isZero();

		// 로그인하지 않은 요청은 401, CSRF token이 없는 변경 요청은 403이고 아무것도 바뀌지 않는다.
		ApiClient anonymous = new ApiClient(this.mvc, null);
		anonymous.get("/api/messages/inbox").andExpect(status().isUnauthorized());
		anonymous.get("/api/messages/sent").andExpect(status().isUnauthorized());
		anonymous.get("/api/messages/unread-count").andExpect(status().isUnauthorized());
		anonymous.get("/api/messages/recipients").andExpect(status().isUnauthorized());
		anonymous.get(inbox(id)).andExpect(status().isUnauthorized());
		anonymous.post(inbox(id) + "/read").andExpect(status().isUnauthorized());
		send(anonymous, compose("t", "b", null, this.stu1), "/api/messages", "matrix-6")
			.andExpect(status().isUnauthorized());
		Cookie profSession = new Cookie("ASTRA_SESSION", this.data.session(this.prof));
		Cookie stuSession = new Cookie("ASTRA_SESSION", this.data.session(this.stu1));
		withoutCsrf(MockMvcRequestBuilders.post("/api/messages").header("Idempotency-Key", "csrf-1"), profSession,
				compose("t", "b", this.course, this.stu1))
			.andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.post(inbox(id) + "/read"), stuSession, null)
			.andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.delete(inbox(id)), stuSession, null).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.post("/api/messages/inbox/delete"), stuSession,
				"{\"messageIds\":[" + id + "]}")
			.andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.post(sentPath(id) + "/recall"), profSession, null)
			.andExpect(status().isForbidden());
		assertThat(readAt(id, this.stu1)).isNull();
		this.student1.get(inbox(id)).andExpect(status().isOk());
		this.professor.get("/api/messages/sent").andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void recipientsAreLimitedToTheServerComputedScope() throws Exception {
		// 학생은 자기 과목 담당자에게만 보낸다. 같은 과목 학생, 다른 과목 사람, 없는 ID, 자기 자신은 모두 같은 거부다.
		send(this.student1, compose("질문", "본문", this.course, this.prof), "scope-1").andExpect(status().isCreated());
		for (long target : List.of(this.stu2, this.otherProf, this.otherStu, 999_999_999L, this.stu1)) {
			send(this.student1, compose("질문", "본문", null, target), "scope-x" + target)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("recipient_not_allowed"))
				.andExpect(jsonPath("$.detail").value("쪽지를 보낼 수 없는 받는사람이 있습니다."));
		}
		// 담당자는 자기 과목 사람에게만 보낸다. 범위 밖이 하나라도 섞이면 아무에게도 보내지 않는다.
		send(this.professor, compose("공지", "본문", null, this.stu1, this.otherStu), "scope-2")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("recipient_not_allowed"));
		this.student1.get("/api/messages/inbox").andExpect(jsonPath("$.total").value(0));
		// 과목을 고르면 그 과목 안에서만 판정한다. 구성원이 아닌 과목은 404다.
		this.data.instructor(this.otherCourse, this.prof);
		send(this.professor, compose("공지", "본문", this.course, this.otherStu), "scope-3")
			.andExpect(status().isBadRequest());
		send(this.professor, compose("공지", "본문", this.otherCourse, this.otherStu), "scope-4")
			.andExpect(status().isCreated());
		send(this.student1, compose("질문", "본문", this.otherCourse, this.prof), "scope-5")
			.andExpect(status().isNotFound());
		assertThat(messagesFrom(this.stu1)).isEqualTo(1);

		// 검색도 같은 범위 안에서만 찾는다. 범위 밖 사람의 이름으로 찾아도 나오지 않는다.
		this.student1.get("/api/messages/recipients")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].userId").value(this.prof))
			.andExpect(jsonPath("$.items[0].role").value("INSTRUCTOR"))
			.andExpect(jsonPath("$.items[0].externalRef").doesNotExist());
		this.student1.get("/api/messages/recipients?q=" + name(this.stu2)).andExpect(jsonPath("$.total").value(0));
		this.student1.get("/api/messages/recipients?q=" + name(this.otherProf)).andExpect(jsonPath("$.total").value(0));
		this.student1.get("/api/messages/recipients?courseId=" + this.otherCourse).andExpect(status().isNotFound());
		this.professor.get("/api/messages/recipients?courseId=" + this.course)
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[*].role").value(everyItem(is("STUDENT"))));
		this.professor.get("/api/messages/recipients?q=" + name(this.stu2))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].userId").value(this.stu2));
		// 이름의 % 와 _ 는 글자 그대로 찾는다. 범위 전체를 펼치는 데 쓰이지 않는다.
		this.professor.get("/api/messages/recipients?q=%25").andExpect(jsonPath("$.total").value(0));
		// 과목을 고르지 않으면 담당하는 두 과목의 학생과 함께 담당하는 교수까지다.
		this.professor.get("/api/messages/recipients?size=1&page=2")
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.items.length()").value(1));
		this.professor.get("/api/messages/recipients?size=51").andExpect(status().isBadRequest());
	}

	@Test
	void deletingFromOneBoxLeavesEveryOtherBoxAlone() throws Exception {
		long id = sentId(send(this.professor, compose("안내", "본문", this.course, this.stu1, this.stu2), "box-1"));

		this.student1.delete(inbox(id)).andExpect(status().isNoContent());
		this.student1.delete(inbox(id)).andExpect(status().isNoContent());
		this.student1.get(inbox(id)).andExpect(status().isNotFound());
		this.student1.get("/api/messages/inbox").andExpect(jsonPath("$.total").value(0));
		this.student2.get(inbox(id)).andExpect(status().isOk());
		this.professor.get(sentPath(id)).andExpect(jsonPath("$.recipients.length()").value(2));

		this.professor.delete(sentPath(id)).andExpect(status().isNoContent());
		this.professor.get(sentPath(id)).andExpect(status().isNotFound());
		this.professor.get("/api/messages/sent").andExpect(jsonPath("$.total").value(0));
		this.professor.post(sentPath(id) + "/recall").andExpect(status().isNotFound());
		this.student2.get(inbox(id)).andExpect(status().isOk());
		this.student2.get("/api/messages/inbox").andExpect(jsonPath("$.unreadCount").value(1));
		// 발신자가 지운 쪽지에도 수신자는 답장할 수 있다.
		send(this.student2, "{\"title\":\"Re: 안내\",\"body\":\"확인했습니다\"}", inbox(id) + "/reply", "box-2")
			.andExpect(status().isCreated());
	}

	@Test
	void bulkDeleteWithAForeignIdChangesNothing() throws Exception {
		long a = sentId(send(this.professor, compose("a", "본문", this.course, this.stu1), "bulk-1"));
		long b = sentId(send(this.professor, compose("b", "본문", this.course, this.stu1), "bulk-2"));
		long foreign = sentId(send(this.professor, compose("c", "본문", this.course, this.stu2), "bulk-3"));
		long otherSent = sentId(send(this.otherProfessor, compose("d", "본문", null, this.otherStu), "bulk-4"));

		// 남의 ID가 하나라도 있으면 전체를 거부하고 아무것도 지우지 않는다(SEC-08).
		this.student1.postJson("/api/messages/inbox/delete", "{\"messageIds\":[" + a + "," + foreign + "]}")
			.andExpect(status().isNotFound());
		this.student1.get("/api/messages/inbox").andExpect(jsonPath("$.total").value(2));
		this.student2.get(inbox(foreign)).andExpect(status().isOk());
		this.professor.postJson("/api/messages/sent/delete", "{\"messageIds\":[" + a + "," + otherSent + "]}")
			.andExpect(status().isNotFound());
		this.professor.get("/api/messages/sent").andExpect(jsonPath("$.total").value(3));
		this.otherProfessor.get(sentPath(otherSent)).andExpect(status().isOk());

		// 모두 자기 것이면 지운다. 이미 지운 ID를 다시 넣어도 자기 것이므로 거부하지 않는다.
		this.student1.postJson("/api/messages/inbox/delete", "{\"messageIds\":[" + a + "]}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(1));
		this.student1.postJson("/api/messages/inbox/delete", "{\"messageIds\":[" + a + "," + b + "]}")
			.andExpect(jsonPath("$.deleted").value(1));
		this.student1.get("/api/messages/inbox").andExpect(jsonPath("$.total").value(0));
		this.professor.postJson("/api/messages/sent/delete", "{\"messageIds\":[" + a + "," + b + "]}")
			.andExpect(jsonPath("$.deleted").value(2));
		this.professor.get("/api/messages/sent").andExpect(jsonPath("$.total").value(1));
		this.student2.get(inbox(foreign)).andExpect(status().isOk());

		this.student1.postJson("/api/messages/inbox/delete", "{}").andExpect(status().isBadRequest());
		this.student1.postJson("/api/messages/inbox/delete", "{\"messageIds\":[" + a + "," + a + "]}")
			.andExpect(status().isBadRequest());
		this.student1.postJson("/api/messages/inbox/delete", "{\"messageIds\":[" + a + "],\"all\":true}")
			.andExpect(status().isBadRequest());
		this.student1.postJson("/api/messages/inbox/delete", "{\"all\":true}").andExpect(status().isBadRequest());
	}

	@Test
	void deleteAllUsesTheCallersFilterAndTheListTime() throws Exception {
		long read = sentId(send(this.professor, compose("읽은 쪽지", "본문", this.course, this.stu1), "all-1"));
		long unread1 = sentId(send(this.professor, compose("안 읽은 쪽지 1", "본문", this.course, this.stu1), "all-2"));
		long unread2 = sentId(send(this.professor, compose("안 읽은 쪽지 2", "본문", this.course, this.stu1), "all-3"));
		long kept = sentId(send(this.professor, compose("빼 둔 쪽지", "본문", this.course, this.stu1), "all-4"));
		long classmate = sentId(send(this.professor, compose("다른 학생", "본문", this.course, this.stu2), "all-5"));
		this.student1.post(inbox(read) + "/read").andExpect(status().isOk());
		String until = ApiClient.json(this.student1.get("/api/messages/inbox?read=false")
			.andExpect(jsonPath("$.total").value(3))).get("serverTime").asString();
		Thread.sleep(20);
		long arrivedLater = sentId(send(this.professor, compose("나중에 온 쪽지", "본문", this.course, this.stu1), "all-6"));

		// 안 읽은 쪽지 전체 선택에서 하나를 뺀 삭제. 읽은 쪽지, 목록 뒤에 온 쪽지, 다른 사람의 쪽지는 남는다(COMM-06).
		this.student1
			.postJson("/api/messages/inbox/delete",
					"{\"all\":true,\"read\":false,\"until\":\"" + until + "\",\"exceptIds\":[" + kept + "]}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.deleted").value(2));
		this.student1.get(inbox(unread1)).andExpect(status().isNotFound());
		this.student1.get(inbox(unread2)).andExpect(status().isNotFound());
		this.student1.get(inbox(read)).andExpect(status().isOk());
		this.student1.get(inbox(kept)).andExpect(status().isOk());
		this.student1.get(inbox(arrivedLater)).andExpect(status().isOk());
		this.student2.get(inbox(classmate)).andExpect(status().isOk());
		this.professor.get("/api/messages/sent").andExpect(jsonPath("$.total").value(6));

		// 보낸함 전체 삭제도 같은 규칙이다. q는 제목 조건이다.
		this.professor
			.postJson("/api/messages/sent/delete", "{\"all\":true,\"q\":\"안 읽은\",\"until\":\"" + until + "\"}")
			.andExpect(jsonPath("$.deleted").value(2));
		this.professor.get("/api/messages/sent").andExpect(jsonPath("$.total").value(4));
		this.professor.postJson("/api/messages/sent/delete", "{\"all\":true,\"read\":true,\"until\":\"" + until + "\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void replayedSendCreatesOneMessage() throws Exception {
		String body = compose("한 번만", "본문", this.course, this.stu1);
		long first = sentId(send(this.professor, body, "replay-1"));
		send(this.professor, body, "replay-1").andExpect(status().isCreated())
			.andExpect(jsonPath("$.messageId").value(first));
		send(this.professor, compose("다른 내용", "본문", this.course, this.stu1), "replay-1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		this.professor.postJson("/api/messages", body)
			.andExpect(status().isBadRequest());
		send(this.professor, body, "bad key!").andExpect(status().isBadRequest());
		assertThat(messagesFrom(this.prof)).isEqualTo(1);

		// 같은 key로 동시에 보내도 쪽지와 수신자 행은 하나씩이다(COMM-03, T-03).
		String concurrent = compose("동시", "본문", this.course, this.stu1, this.stu2);
		ExecutorService pool = Executors.newFixedThreadPool(4);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<String>> results = new ArrayList<>();
			for (int i = 0; i < 4; i++) {
				results.add(pool.submit(() -> {
					start.await();
					var response = send(this.professor, concurrent, "replay-2").andReturn().getResponse();
					return response.getStatus() + ":" + (response.getStatus() == 201
							? ApiClient.json(response).get("messageId").asLong() : "");
				}));
			}
			start.countDown();
			List<String> outcomes = new ArrayList<>();
			for (Future<String> result : results) {
				outcomes.add(result.get());
			}
			// 처리 중인 같은 key를 기다리다 시간이 지나면 409 idempotency_in_progress일 수 있다. 쪽지는 하나만 생긴다.
			assertThat(outcomes.stream().filter((o) -> o.startsWith("201")).collect(Collectors.toSet())).hasSize(1);
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(messagesFrom(this.prof)).isEqualTo(2);
		this.student2.get("/api/messages/inbox").andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void recallTakesBackOnlyUnreadCopies() throws Exception {
		long id = sentId(send(this.professor, compose("잘못 보낸 쪽지", "본문", this.course, this.stu1, this.stu2),
				"recall-1"));
		this.student1.post(inbox(id) + "/read").andExpect(status().isOk());
		// 받는사람마다 알림이 하나 생기고 보낸 사람에게는 생기지 않는다.
		assertThat(messageNotifications(id)).isEqualTo(2);
		this.student2.get("/api/me/notifications")
			.andExpect(jsonPath("$.items[0].kind").value("MESSAGE_RECEIVED"))
			.andExpect(jsonPath("$.items[0].targetId").value(id));
		this.professor.get("/api/me/notifications").andExpect(jsonPath("$.items.length()").value(0));

		this.professor.post(sentPath(id) + "/recall")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.recalledNow").value(1))
			.andExpect(jsonPath("$.recipients[?(@.userId == " + this.stu1 + ")].outcome").value("read"))
			.andExpect(jsonPath("$.recipients[?(@.userId == " + this.stu2 + ")].outcome").value("recalled"));
		this.professor.post(sentPath(id) + "/recall")
			.andExpect(jsonPath("$.recalledNow").value(0))
			.andExpect(jsonPath("$.recipients[?(@.userId == " + this.stu2 + ")].outcome").value("recalled"));
		// 거둔 쪽지는 받은함에서 사라지고 읽거나 답하거나 전달할 수 없다. 읽은 수신자의 쪽지는 남는다.
		this.student2.get("/api/messages/inbox")
			.andExpect(jsonPath("$.total").value(0))
			.andExpect(jsonPath("$.unreadCount").value(0));
		this.student2.get(inbox(id)).andExpect(status().isNotFound());
		assertThat(messageNotifications(id)).isZero();
		this.student2.post(inbox(id) + "/read").andExpect(status().isNotFound());
		send(this.student2, "{\"title\":\"답\",\"body\":\"답\"}", inbox(id) + "/reply", "recall-2")
			.andExpect(status().isNotFound());
		this.student1.get(inbox(id)).andExpect(status().isOk());
		this.professor.get("/api/messages/sent").andExpect(jsonPath("$.items[0].recalledCount").value(1));
		this.professor.get(sentPath(id))
			.andExpect(jsonPath("$.recipients[?(@.userId == " + this.stu2 + ")].recalled").value(true));
	}

	@Test
	void recallRacingReadEndsInExactlyOneOutcome() throws Exception {
		int rounds = 8;
		int perRound = 3;
		for (int round = 0; round < rounds; round++) {
			List<Long> ids = new ArrayList<>();
			for (int i = 0; i < perRound; i++) {
				ids.add(sentId(send(this.professor, compose("경합 " + round + "-" + i, "본문", this.course, this.stu1),
						"race-" + round + "-" + i)));
			}
			ExecutorService pool = Executors.newFixedThreadPool(perRound * 2);
			try {
				CountDownLatch start = new CountDownLatch(1);
				List<Future<Integer>> reads = new ArrayList<>();
				List<Future<String>> recalls = new ArrayList<>();
				for (long id : ids) {
					reads.add(pool.submit(() -> {
						start.await();
						return this.student1.post(inbox(id) + "/read").andReturn().getResponse().getStatus();
					}));
					recalls.add(pool.submit(() -> {
						start.await();
						var response = this.professor.post(sentPath(id) + "/recall").andReturn().getResponse();
						assertThat(response.getStatus()).isEqualTo(200);
						return ApiClient.json(response).get("recipients").get(0).get("outcome").asString();
					}));
				}
				start.countDown();
				for (int i = 0; i < perRound; i++) {
					int read = reads.get(i).get();
					String outcome = recalls.get(i).get();
					// 읽음이 이기면 회수는 read, 회수가 이기면 읽음은 404. 둘 다 이기거나 둘 다 지는 일은 없다.
					assertThat(read == 200 ? "read" : read == 404 ? "recalled" : "status " + read).isEqualTo(outcome);
				}
			}
			finally {
				pool.shutdownNow();
			}
		}
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM message_recipients
				 WHERE recipient_id = :u AND read_at IS NOT NULL AND canceled_at IS NOT NULL
				""").param("u", this.stu1).query(Long.class).single()).isZero();
	}

	@Test
	void replyGoesToTheSenderAndForwardKeepsTheScopeRule() throws Exception {
		long id = sentId(send(this.professor, compose("과제 안내", "본문", this.course, this.stu1), "thread-1"));

		long reply = sentId(send(this.student1, "{\"title\":\"Re: 과제 안내\",\"body\":\"질문 있습니다\"}",
				inbox(id) + "/reply", "thread-2"));
		this.professor.get(inbox(reply))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.sender.userId").value(this.stu1))
			.andExpect(jsonPath("$.originKind").value("REPLY"))
			.andExpect(jsonPath("$.course.courseId").value(this.course));
		// 답장의 받는사람은 서버가 정한다. 본문에 받는사람을 넣을 수 없다.
		send(this.student1, "{\"title\":\"Re\",\"body\":\"b\",\"recipientIds\":[" + this.stu2 + "]}",
				inbox(id) + "/reply", "thread-3")
			.andExpect(status().isBadRequest());
		// 전달은 새 쪽지와 같은 범위 판정을 거친다. 학생은 같은 과목 학생에게 전달하지 못한다.
		send(this.student1, compose("Fw: 과제 안내", "본문", null, this.stu2), inbox(id) + "/forward", "thread-4")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("recipient_not_allowed"));
		long forwarded = sentId(send(this.professor, compose("Fw: 과제 안내", "본문", this.course, this.stu2),
				sentPath(id) + "/forward", "thread-5"));
		this.student2.get(inbox(forwarded)).andExpect(jsonPath("$.originKind").value("FORWARD"));
	}

	@Test
	void inputIsPlainTextWithServerLimits() throws Exception {
		String html = "<script>alert(1)</script>";
		long id = sentId(send(this.professor, compose(html, html, this.course, this.stu1), "input-1"));
		this.student1.get(inbox(id)).andExpect(jsonPath("$.title").value(html)).andExpect(jsonPath("$.body").value(html));
		// 본문 상한은 원본 화면과 같은 2000byte(ASCII 1, 그 밖 2)다(COMM-12).
		send(this.professor, compose("t", "가".repeat(1000), this.course, this.stu1), "input-2")
			.andExpect(status().isCreated());
		send(this.professor, compose("t", "가".repeat(1000) + "a", this.course, this.stu1), "input-3")
			.andExpect(status().isBadRequest());
		send(this.professor, compose(" ", "b", this.course, this.stu1), "input-4").andExpect(status().isBadRequest());
		send(this.professor, compose("t", "  ", this.course, this.stu1), "input-5").andExpect(status().isBadRequest());
		send(this.professor, compose("a\u0007b", "b", this.course, this.stu1), "input-6")
			.andExpect(status().isBadRequest());
		send(this.professor, "{\"title\":\"t\",\"body\":\"b\",\"recipientIds\":[]}", "input-7")
			.andExpect(status().isBadRequest());
		send(this.professor, "{\"title\":\"t\",\"body\":\"b\",\"recipientIds\":[" + this.stu1 + "," + this.stu1 + "]}",
				"input-8")
			.andExpect(status().isBadRequest());
		send(this.professor, "{\"title\":\"t\",\"body\":\"b\",\"recipientIds\":[" + this.stu1 + "],\"senderId\":1}",
				"input-9")
			.andExpect(status().isBadRequest());
		this.student1.get("/api/messages/inbox?cursor=bad").andExpect(status().isBadRequest());
		this.student1.get("/api/messages/inbox?size=0").andExpect(status().isBadRequest());
	}

	@Test
	void unreadCountsArePerUserAndReachTheHomeScreen() throws Exception {
		long a = sentId(send(this.professor, compose("a", "본문", this.course, this.stu1, this.stu2), "count-1"));
		sentId(send(this.professor, compose("b", "본문", this.course, this.stu1), "count-2"));
		this.student1.get("/api/messages/unread-count").andExpect(jsonPath("$.unreadCount").value(2));
		this.student2.get("/api/messages/unread-count").andExpect(jsonPath("$.unreadCount").value(1));
		this.professor.get("/api/messages/unread-count").andExpect(jsonPath("$.unreadCount").value(0));
		this.student1.get("/api/me/home").andExpect(jsonPath("$.unreadMessages").value(2));

		this.student1.post(inbox(a) + "/read").andExpect(jsonPath("$.unreadCount").value(1));
		this.student2.delete(inbox(a)).andExpect(status().isNoContent());
		this.student1.get("/api/me/home").andExpect(jsonPath("$.unreadMessages").value(1));
		this.student2.get("/api/messages/unread-count").andExpect(jsonPath("$.unreadCount").value(0));
		this.student1.get("/api/messages/inbox?read=false")
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].title").value("b"));
		this.student1.get("/api/messages/inbox?read=true")
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].messageId").value(a));
	}

	@Test
	void listsPageWithAStableCursor() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			ids.add(sentId(send(this.professor, compose("쪽지 " + i, "본문", this.course, this.stu1), "page-" + i)));
		}
		JsonNode first = ApiClient.json(this.student1.get("/api/messages/inbox?size=2")
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.total").value(5)));
		assertThat(first.get("items").get(0).get("messageId").asLong()).isEqualTo(ids.get(4));
		// 쪽 사이에 앞쪽 항목을 지워도 다음 쪽이 겹치거나 빠지지 않는다.
		this.student1.delete(inbox(ids.get(4))).andExpect(status().isNoContent());
		JsonNode second = ApiClient.json(this.student1
			.get("/api/messages/inbox?size=2&cursor=" + first.get("nextCursor").asString()));
		assertThat(second.get("items").get(0).get("messageId").asLong()).isEqualTo(ids.get(2));
		JsonNode third = ApiClient.json(this.student1
			.get("/api/messages/inbox?size=2&cursor=" + second.get("nextCursor").asString()));
		assertThat(third.get("items").size()).isEqualTo(1);
		assertThat(third.get("nextCursor").isNull() || third.get("nextCursor").isMissingNode()).isTrue();

		JsonNode sent = ApiClient.json(this.professor.get("/api/messages/sent?size=3&q=쪽지"));
		assertThat(sent.get("total").asLong()).isEqualTo(5);
		JsonNode rest = ApiClient.json(this.professor
			.get("/api/messages/sent?size=3&q=쪽지&cursor=" + sent.get("nextCursor").asString()));
		assertThat(rest.get("items").get(0).get("messageId").asLong()).isEqualTo(ids.get(1));
	}

	/**
	 * 목록의 기준 시각은 첫 쪽의 serverTime이고 다음 쪽 cursor가 그 시각을 이어 받는다. 그래서 어느 쪽의 serverTime을 전체 삭제의 until로
	 * 보내도 같고, 기준 시각 뒤에 보낸 쪽지는 다음 쪽과 total에 끼지 않는다.
	 */
	@Test
	void everyPageOfOneListingKeepsTheFirstPagesServerTimeAndTotal() throws Exception {
		for (int i = 0; i < 3; i++) {
			sentId(send(this.professor, compose("기준 " + i, "본문", this.course, this.stu1), "snap-" + i));
		}
		JsonNode first = ApiClient.json(this.professor.get("/api/messages/sent?size=2"));
		JsonNode firstInbox = ApiClient.json(this.student1.get("/api/messages/inbox?size=2"));
		Thread.sleep(20);
		long later = sentId(send(this.professor, compose("나중에", "본문", this.course, this.stu1), "snap-later"));

		JsonNode second = ApiClient.json(this.professor
			.get("/api/messages/sent?size=2&cursor=" + first.get("nextCursor").asString())
			.andExpect(status().isOk()));
		assertThat(second.get("serverTime").asString()).isEqualTo(first.get("serverTime").asString());
		assertThat(second.get("total").asLong()).isEqualTo(3);
		assertThat(second.get("items")).hasSize(1);
		JsonNode secondInbox = ApiClient.json(this.student1
			.get("/api/messages/inbox?size=2&cursor=" + firstInbox.get("nextCursor").asString()));
		assertThat(secondInbox.get("serverTime").asString()).isEqualTo(firstInbox.get("serverTime").asString());
		assertThat(secondInbox.get("total").asLong()).isEqualTo(3);
		// 새로 읽는 첫 쪽은 지금이 기준이다.
		this.professor.get("/api/messages/sent?size=2")
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.items[0].messageId").value(later));

		// 둘째 쪽의 serverTime으로 전체 삭제해도 첫 쪽과 같은 쪽지만 지워진다.
		this.professor
			.postJson("/api/messages/sent/delete",
					"{\"all\":true,\"until\":\"" + second.get("serverTime").asString() + "\"}")
			.andExpect(jsonPath("$.deleted").value(3));
		this.professor.get(sentPath(later)).andExpect(status().isOk());

		// 잘못된 cursor와 고르기 조건은 필드 이름 없이 한국어 문장으로 알린다.
		this.professor.get("/api/messages/sent?cursor=bm90LWEtY3Vyc29y")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("다음 쪽 위치가 올바르지 않습니다. 목록을 처음부터 다시 불러 주세요."));
		this.professor.postJson("/api/messages/sent/delete", "{\"all\":true}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("전체 삭제에는 목록을 불러온 시각이 필요하고, 쪽지를 따로 고른 채로 전체 삭제할 수 없습니다."));
		this.professor.postJson("/api/messages/sent/delete", "{\"messageIds\":[]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("지울 쪽지를 1개 이상 500개 이하로 고르거나 전체 선택으로 지워야 합니다."));
	}

	@Test
	void attachmentsAreReadableOnlyByPeopleWhoStillHoldTheMessage() throws Exception {
		long upload = attachment(this.professor, "range.txt", "시험 범위");
		String json = "{\"title\":\"시험 범위\",\"body\":\"첨부 확인\",\"courseId\":" + this.course + ",\"recipientIds\":["
				+ this.stu1 + "," + this.stu2 + "],\"uploadIds\":[" + upload + "]}";
		long id = sentId(send(this.professor, json, "attach-1"));
		send(this.professor, json, "attach-1").andExpect(jsonPath("$.messageId").value(id));
		long file = ApiClient.json(this.student1.get(inbox(id)).andExpect(jsonPath("$.attachments.length()").value(1)))
			.get("attachments")
			.get(0)
			.get("fileId")
			.asLong();
		this.professor.get(sentPath(id)).andExpect(jsonPath("$.attachments[0].fileId").value(file));
		for (ApiClient holder : List.of(this.professor, this.student1, this.student2)) {
			holder.get(content(file)).andExpect(status().isOk());
		}
		this.outsider.get(content(file)).andExpect(status().isNotFound());
		this.otherProfessor.get(content(file)).andExpect(status().isNotFound());
		// 쪽지 첨부는 과목 경로로 열리지 않는다.
		this.student1.get("/api/courses/" + this.course + "/files/" + file + "/content").andExpect(status().isNotFound());

		// 회수된 수신자, 받은함에서 지운 수신자, 보낸함에서 지운 발신자는 더 읽지 못한다.
		this.student1.post(inbox(id) + "/read").andExpect(status().isOk());
		this.professor.post(sentPath(id) + "/recall").andExpect(status().isOk());
		this.student2.get(content(file)).andExpect(status().isNotFound());
		this.student1.get(content(file)).andExpect(status().isOk());
		this.student1.delete(inbox(id)).andExpect(status().isNoContent());
		this.student1.get(content(file)).andExpect(status().isNotFound());
		this.professor.get(content(file)).andExpect(status().isOk());
		this.professor.delete(sentPath(id)).andExpect(status().isNoContent());
		this.professor.get(content(file)).andExpect(status().isNotFound());

		// 남의 업로드는 붙이지 못하고, 그때는 쪽지도 보내지 않는다. 답장에도 첨부를 붙인다.
		long others = attachment(this.student1, "mine.txt", "학생 파일");
		long before = messagesFrom(this.prof);
		send(this.professor, "{\"title\":\"t\",\"body\":\"b\",\"recipientIds\":[" + this.stu1 + "],\"uploadIds\":[" + others
				+ "]}", "attach-2").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attachment_not_ready"));
		assertThat(messagesFrom(this.prof)).isEqualTo(before);
		long question = sentId(send(this.student1, compose("질문", "본문", this.course, this.prof), "attach-3"));
		long reply = sentId(send(this.professor, "{\"title\":\"답\",\"body\":\"답장\",\"uploadIds\":[" + attachment(this.professor,
				"answer.txt", "답 파일") + "]}", inbox(question) + "/reply", "attach-4"));
		long replyFile = ApiClient.json(this.student1.get(inbox(reply))).get("attachments").get(0).get("fileId").asLong();
		this.student1.get(content(replyFile)).andExpect(status().isOk());
		this.student2.get(content(replyFile)).andExpect(status().isNotFound());
		// 과목 용도 업로드는 쪽지에 붙지 않는다.
		long courseUpload = ApiClient.json(this.professor.upload(this.course, "post_attachment", "c.txt",
				"과목 파일".getBytes(StandardCharsets.UTF_8), null).andExpect(status().isCreated()))
			.get("uploadId")
			.asLong();
		send(this.professor, "{\"title\":\"t\",\"body\":\"b\",\"recipientIds\":[" + this.stu1 + "],\"uploadIds\":[" + courseUpload
				+ "]}", "attach-5").andExpect(status().isConflict());
	}

	private long attachment(ApiClient client, String name, String text) throws Exception {
		return ApiClient.json(client.uploadOutsideCourse("message_attachment", name,
				text.getBytes(StandardCharsets.UTF_8)).andExpect(status().isCreated()))
			.get("uploadId")
			.asLong();
	}

	private static String content(long fileId) {
		return "/api/files/" + fileId + "/content";
	}

	private long user(String name, String role) {
		return this.data.insert("INSERT INTO users (external_ref, user_name, user_role) VALUES (:ref, :name, :role)",
				Map.of("ref", "m-" + UUID.randomUUID(), "name", name, "role", role));
	}

	private String name(long userId) {
		return this.jdbc.sql("SELECT user_name FROM users WHERE user_id = :u").param("u", userId).query(String.class).single();
	}

	private ApiClient client(long userId) {
		return new ApiClient(this.mvc, this.data.session(userId));
	}

	private ResultActions send(ApiClient client, String json, String key) throws Exception {
		return send(client, json, "/api/messages", key);
	}

	private ResultActions send(ApiClient client, String json, String path, String key) throws Exception {
		return client.send(MockMvcRequestBuilders.post(path)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)
			.header("Idempotency-Key", key));
	}

	private static long sentId(ResultActions result) throws Exception {
		return ApiClient.json(result.andExpect(status().isCreated())).get("messageId").asLong();
	}

	private static String compose(String title, String body, Long courseId, long... recipients) {
		String ids = Arrays.stream(recipients).mapToObj(Long::toString).collect(Collectors.joining(","));
		return "{\"title\":" + quote(title) + ",\"body\":" + quote(body) + ",\"courseId\":" + courseId
				+ ",\"recipientIds\":[" + ids + "]}";
	}

	private static String quote(String text) {
		StringBuilder out = new StringBuilder("\"");
		for (char c : text.toCharArray()) {
			switch (c) {
				case '"' -> out.append("\\\"");
				case '\\' -> out.append("\\\\");
				case '\n' -> out.append("\\n");
				default -> out.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
			}
		}
		return out.append('"').toString();
	}

	private String readAt(long messageId, long recipientId) {
		return this.jdbc.sql("SELECT CAST(read_at AS CHAR) FROM message_recipients WHERE message_id = :m AND recipient_id = :u")
			.param("m", messageId)
			.param("u", recipientId)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	private long messageNotifications(long messageId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM notifications WHERE notification_type = 'MESSAGE_RECEIVED' AND target_ref_id = :m
				""").param("m", messageId).query(Long.class).single();
	}

	private long messagesFrom(long senderId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM messages WHERE sender_id = :u")
			.param("u", senderId)
			.query(Long.class)
			.single();
	}

	private ResultActions withoutCsrf(MockHttpServletRequestBuilder request, Cookie session, String json)
			throws Exception {
		request.cookie(session);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return this.mvc.perform(request);
	}

	private static String inbox(long messageId) {
		return "/api/messages/inbox/" + messageId;
	}

	private static String sentPath(long messageId) {
		return "/api/messages/sent/" + messageId;
	}

}
