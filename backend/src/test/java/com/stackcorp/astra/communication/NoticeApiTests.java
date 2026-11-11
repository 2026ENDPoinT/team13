package com.stackcorp.astra.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
 * 강의실 공지. 권한은 과목 관계, 게시 일시(DB 시계), 경로의 과목으로만 정해진다. 조회는 상태를 바꾸지 않고 읽음·확인은 명시적 명령이다.
 */
class NoticeApiTests extends IntegrationTest {

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

	ApiClient outsider;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.prof = this.data.user("PROFESSOR");
		this.stu = this.data.user("STUDENT");
		this.data.instructor(this.course, this.prof);
		this.data.enroll(this.course, this.stu);
		this.professor = new ApiClient(this.mvc, this.data.session(this.prof));
		this.student = new ApiClient(this.mvc, this.data.session(this.stu));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	@Test
	void instructorPublishesAndStudentReadsWithoutGetChangingState() throws Exception {
		JsonNode created = ApiClient.json(this.professor
			.postJson(notices(this.course), "{\"title\":\"  중간고사 공지 \",\"body\":\"<b>10월 21일</b>\\n본관 101호\"}")
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.title").value("중간고사 공지"))
			.andExpect(jsonPath("$.published").value(true))
			.andExpect(jsonPath("$.pinned").value(false))
			.andExpect(jsonPath("$.authorName").value("테스트 PROFESSOR"))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.readCount").value(0)));
		long id = created.get("noticeId").asLong();

		this.student.get(notices(this.course))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].noticeId").value(id))
			// 본문은 저장한 텍스트 그대로 돌려준다. 미리보기는 줄바꿈을 공백으로 바꾼 앞부분이다.
			.andExpect(jsonPath("$.items[0].excerpt").value("<b>10월 21일</b> 본관 101호"))
			.andExpect(jsonPath("$.items[0].read").value(false))
			.andExpect(jsonPath("$.items[0].readCount").doesNotExist());
		// 상세를 여러 번 열어도 읽음, 조회수가 바뀌지 않는다(SC-04).
		this.student.get(notice(this.course, id)).andExpect(status().isOk());
		this.student.get(notice(this.course, id))
			.andExpect(jsonPath("$.body").value("<b>10월 21일</b>\n본관 101호"))
			.andExpect(jsonPath("$.read").value(false))
			.andExpect(jsonPath("$.viewCount").value(0))
			.andExpect(jsonPath("$.readCount").doesNotExist())
			.andExpect(jsonPath("$.confirmCount").doesNotExist());
		assertThat(receipts(id)).isZero();

		// 읽음은 몇 번 보내도 한 행이고 조회수는 처음 한 번만 오른다.
		this.student.post(notice(this.course, id) + "/read")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.read").value(true))
			.andExpect(jsonPath("$.confirmed").value(false));
		this.student.post(notice(this.course, id) + "/read").andExpect(status().isOk());
		assertThat(receipts(id)).isEqualTo(1);
		this.student.get(notice(this.course, id))
			.andExpect(jsonPath("$.read").value(true))
			.andExpect(jsonPath("$.viewCount").value(1));
		// 사람마다 따로다.
		long other = this.data.user("STUDENT");
		this.data.enroll(this.course, other);
		ApiClient classmate = new ApiClient(this.mvc, this.data.session(other));
		classmate.get(notices(this.course)).andExpect(jsonPath("$.items[0].read").value(false));
		classmate.post(notice(this.course, id) + "/read").andExpect(status().isOk());

		this.student.post(notice(this.course, id) + "/confirm")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.read").value(true))
			.andExpect(jsonPath("$.confirmed").value(true));
		this.student.post(notice(this.course, id) + "/confirm").andExpect(jsonPath("$.confirmed").value(true));
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM post_confirmations WHERE post_id = :p")
			.param("p", id)
			.query(Long.class)
			.single()).isEqualTo(1);
		classmate.get(notice(this.course, id)).andExpect(jsonPath("$.confirmed").value(false));

		// 담당자만 열람·확인 수를 본다. 작성자 본인의 열람은 세지 않는다.
		this.professor.post(notice(this.course, id) + "/read").andExpect(status().isOk());
		this.professor.get(notices(this.course))
			.andExpect(jsonPath("$.items[0].readCount").value(2))
			.andExpect(jsonPath("$.items[0].confirmCount").value(1));
		this.professor.get(notice(this.course, id))
			.andExpect(jsonPath("$.viewCount").value(3))
			.andExpect(jsonPath("$.readCount").value(2))
			.andExpect(jsonPath("$.confirmCount").value(1))
			.andExpect(jsonPath("$.updatedAt").doesNotExist());
	}

	@Test
	void everyEndpointFollowsTheAuthorizationMatrix() throws Exception {
		long id = create(this.professor, this.course, "{\"title\":\"공지\"}");
		String body = "{\"title\":\"고친 공지\",\"version\":1}";

		// 학생: 읽기와 읽음·확인만. 쓰기는 존재를 드러내지 않는 404다.
		this.student.get(notices(this.course)).andExpect(status().isOk());
		this.student.get(notice(this.course, id)).andExpect(status().isOk());
		this.student.post(notice(this.course, id) + "/read").andExpect(status().isOk());
		this.student.post(notice(this.course, id) + "/confirm").andExpect(status().isOk());
		this.student.postJson(notices(this.course), "{\"title\":\"학생 공지\"}").andExpect(status().isNotFound());
		put(this.student, notice(this.course, id), body).andExpect(status().isNotFound());
		this.student.delete(notice(this.course, id)).andExpect(status().isNotFound());

		// 과목 밖의 사용자: 모두 404.
		this.outsider.get(notices(this.course)).andExpect(status().isNotFound());
		this.outsider.get(notice(this.course, id)).andExpect(status().isNotFound());
		this.outsider.post(notice(this.course, id) + "/read").andExpect(status().isNotFound());
		this.outsider.post(notice(this.course, id) + "/confirm").andExpect(status().isNotFound());
		this.outsider.postJson(notices(this.course), "{\"title\":\"공지\"}").andExpect(status().isNotFound());
		put(this.outsider, notice(this.course, id), body).andExpect(status().isNotFound());
		this.outsider.delete(notice(this.course, id)).andExpect(status().isNotFound());

		// 다른 과목의 경로로는 이 과목의 공지에 닿지 않는다. 두 과목을 모두 담당·수강해도 같다(SC-03).
		long otherCourse = this.data.course();
		this.data.instructor(otherCourse, this.prof);
		this.data.enroll(otherCourse, this.stu);
		this.professor.get(notice(otherCourse, id)).andExpect(status().isNotFound());
		put(this.professor, notice(otherCourse, id), body).andExpect(status().isNotFound());
		this.professor.delete(notice(otherCourse, id)).andExpect(status().isNotFound());
		this.student.get(notice(otherCourse, id)).andExpect(status().isNotFound());
		this.student.post(notice(otherCourse, id) + "/read").andExpect(status().isNotFound());
		this.student.post(notice(otherCourse, id) + "/confirm").andExpect(status().isNotFound());
		this.student.get(notices(otherCourse)).andExpect(jsonPath("$.total").value(0));
		assertThat(receipts(id)).isEqualTo(1);

		// 로그인하지 않은 요청은 401, CSRF token이 없는 변경 요청은 403이다.
		ApiClient anonymous = new ApiClient(this.mvc, null);
		anonymous.get(notices(this.course)).andExpect(status().isUnauthorized());
		anonymous.get(notice(this.course, id)).andExpect(status().isUnauthorized());
		anonymous.post(notice(this.course, id) + "/read").andExpect(status().isUnauthorized());
		anonymous.post(notice(this.course, id) + "/confirm").andExpect(status().isUnauthorized());
		anonymous.postJson(notices(this.course), "{\"title\":\"공지\"}").andExpect(status().isUnauthorized());
		put(anonymous, notice(this.course, id), body).andExpect(status().isUnauthorized());
		anonymous.delete(notice(this.course, id)).andExpect(status().isUnauthorized());
		Cookie session = new Cookie("ASTRA_SESSION", this.data.session(this.prof));
		withoutCsrf(MockMvcRequestBuilders.post(notices(this.course)), session, "{\"title\":\"공지\"}")
			.andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.put(notice(this.course, id)), session, body).andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.delete(notice(this.course, id)), session, null)
			.andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.post(notice(this.course, id) + "/read"), session, null)
			.andExpect(status().isForbidden());
		withoutCsrf(MockMvcRequestBuilders.post(notice(this.course, id) + "/confirm"), session, null)
			.andExpect(status().isForbidden());

		// 담당자: 모두 된다.
		put(this.professor, notice(this.course, id), body).andExpect(status().isOk());
		this.professor.delete(notice(this.course, id)).andExpect(status().isNoContent());
		this.professor.get(notice(this.course, id)).andExpect(status().isNotFound());
		this.professor.delete(notice(this.course, id)).andExpect(status().isNotFound());
		this.student.get(notices(this.course)).andExpect(jsonPath("$.total").value(0));
		this.student.post(notice(this.course, id) + "/read").andExpect(status().isNotFound());
	}

	@Test
	void studentsSeeNothingOfDraftOrScheduledNoticesUntilThePublishTimePasses() throws Exception {
		long draft = create(this.professor, this.course, "{\"title\":\"임시\",\"published\":false}");
		long scheduled = create(this.professor, this.course, "{\"title\":\"예약\",\"publishAt\":\"2999-01-01T09:00:00\"}");
		long due = create(this.professor, this.course, "{\"title\":\"곧 게시\",\"publishAt\":\"2999-01-01T09:00:00\"}");
		long visible = create(this.professor, this.course, "{\"title\":\"게시됨\"}");
		// 게시 일시를 DB 시계의 바로 전으로 옮긴다. 판정은 클라이언트가 아니라 DB 시계로 한다(SC-06).
		this.jdbc.sql("UPDATE posts SET published_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE post_id = :p")
			.param("p", due)
			.update();

		this.student.get(notices(this.course))
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[*].noticeId").value(org.hamcrest.Matchers.containsInAnyOrder((int) due, (int) visible)));
		for (long hidden : List.of(draft, scheduled)) {
			this.student.get(notice(this.course, hidden)).andExpect(status().isNotFound());
			this.student.post(notice(this.course, hidden) + "/read").andExpect(status().isNotFound());
			this.student.post(notice(this.course, hidden) + "/confirm").andExpect(status().isNotFound());
		}
		assertThat(receipts(draft) + receipts(scheduled)).isZero();
		this.student.get(notice(this.course, due)).andExpect(status().isOk()).andExpect(jsonPath("$.published").value(true));

		// 담당자는 임시·예약 공지도 본다. 임시 공지는 고정 공지 다음, 게시된 공지보다 앞이다.
		this.professor.get(notices(this.course))
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.items[0].noticeId").value(draft))
			.andExpect(jsonPath("$.items[0].published").value(false))
			.andExpect(jsonPath("$.items[0].publishedAt").doesNotExist())
			.andExpect(jsonPath("$.items[1].noticeId").value(scheduled))
			.andExpect(jsonPath("$.items[1].published").value(false));

		// 임시 공지를 게시하면 학생에게 보인다.
		put(this.professor, notice(this.course, draft), "{\"title\":\"임시\",\"version\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.published").value(true));
		this.student.get(notice(this.course, draft)).andExpect(status().isOk());
		this.professor.postJson(notices(this.course), "{\"title\":\"임시\",\"published\":false,\"publishAt\":\"2999-01-01T09:00:00\"}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void staleEditIsRejectedWithConflictInsteadOfOverwriting() throws Exception {
		long id = create(this.professor, this.course, "{\"title\":\"원래 제목\",\"pinned\":true}");
		long ta = this.data.user("TA");
		this.data.instructor(this.course, ta);
		ApiClient assistant = new ApiClient(this.mvc, this.data.session(ta));
		String publishedAt = ApiClient.json(this.professor.get(notice(this.course, id))).get("publishedAt").asString();

		put(this.professor, notice(this.course, id), "{\"title\":\"교수 수정\",\"body\":\"본문\",\"pinned\":true,\"version\":1}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.updatedAt").exists())
			// 이미 게시된 공지를 고쳐도 게시 일시는 그대로다.
			.andExpect(jsonPath("$.publishedAt").value(publishedAt));
		put(assistant, notice(this.course, id), "{\"title\":\"조교 수정\",\"version\":1}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		this.student.get(notice(this.course, id))
			.andExpect(jsonPath("$.title").value("교수 수정"))
			.andExpect(jsonPath("$.body").value("본문"))
			.andExpect(jsonPath("$.pinned").value(true));
		put(assistant, notice(this.course, id), "{\"title\":\"조교 수정\",\"version\":2}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.pinned").value(false))
			.andExpect(jsonPath("$.body").doesNotExist());
		put(assistant, notice(this.course, id), "{\"title\":\"버전 없음\"}").andExpect(status().isBadRequest());
		this.professor.delete(notice(this.course, id)).andExpect(status().isNoContent());
		put(this.professor, notice(this.course, id), "{\"title\":\"지운 뒤\",\"version\":3}").andExpect(status().isNotFound());
	}

	@Test
	void rejectsMalformedInputWithoutEchoingIt() throws Exception {
		this.professor.postJson(notices(this.course), "{\"title\":\"   \"}").andExpect(status().isBadRequest());
		this.professor.postJson(notices(this.course), "{\"title\":\"a\\u0007b\"}").andExpect(status().isBadRequest());
		this.professor.postJson(notices(this.course), "{\"title\":\"" + "가".repeat(301) + "\"}")
			.andExpect(status().isBadRequest());
		this.professor.postJson(notices(this.course), "{\"title\":\"t\",\"body\":\"" + "가".repeat(20_001) + "\"}")
			.andExpect(status().isBadRequest());
		// 작성자나 과목은 요청에서 받지 않는다(SC-02).
		this.professor.postJson(notices(this.course), "{\"title\":\"t\",\"authorId\":1}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.detail").value("요청 형식이 올바르지 않습니다."));
		this.professor.postJson(notices(this.course), "{\"title\":\"t\",\"version\":1}").andExpect(status().isBadRequest());
		this.student.get(notices(this.course) + "?size=51").andExpect(status().isBadRequest());
		this.student.get(notices(this.course) + "?page=0").andExpect(status().isBadRequest());
		this.student.get(notices(this.course) + "?q=" + "a".repeat(101)).andExpect(status().isBadRequest());
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM posts p JOIN boards b ON b.board_id = p.board_id WHERE b.course_id = :c
				""").param("c", this.course).query(Long.class).single()).isZero();
	}

	@Test
	void listsPinnedFirstThenNewestWithStablePagesAndLiteralTitleSearch() throws Exception {
		List<Long> ids = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			ids.add(create(this.professor, this.course, "{\"title\":\"" + (i == 2 ? "할인 100%" : "공지 " + i) + "\"}"));
		}
		long pinned = create(this.professor, this.course, "{\"title\":\"고정\",\"pinned\":true}");
		// 게시 일시를 같게 만들어도 ID 역순으로 순서가 정해진다.
		this.jdbc.sql("UPDATE posts SET published_at = '2026-11-01 09:00:00' WHERE post_id IN (:ids)").param("ids", ids).update();
		this.jdbc.sql("UPDATE posts SET published_at = '2026-10-01 09:00:00' WHERE post_id = :p").param("p", pinned).update();

		this.student.get(notices(this.course) + "?page=1&size=4")
			.andExpect(jsonPath("$.total").value(6))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(4))
			.andExpect(jsonPath("$.items[0].noticeId").value(pinned))
			.andExpect(jsonPath("$.items[0].pinned").value(true))
			.andExpect(jsonPath("$.items[1].noticeId").value(ids.get(4)))
			.andExpect(jsonPath("$.items[3].noticeId").value(ids.get(2)));
		this.student.get(notices(this.course) + "?page=2&size=4")
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].noticeId").value(ids.get(1)))
			.andExpect(jsonPath("$.items[1].noticeId").value(ids.get(0)));
		this.student.get(notices(this.course) + "?page=3&size=4")
			.andExpect(jsonPath("$.total").value(6))
			.andExpect(jsonPath("$.items.length()").value(0));

		// 검색어의 %와 _는 글자 그대로 비교한다.
		this.student.send(MockMvcRequestBuilders.get(notices(this.course)).param("q", "%"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].noticeId").value(ids.get(2)));
		this.student.send(MockMvcRequestBuilders.get(notices(this.course)).param("q", "_"))
			.andExpect(jsonPath("$.total").value(0));
		this.student.send(MockMvcRequestBuilders.get(notices(this.course)).param("q", "공지"))
			.andExpect(jsonPath("$.total").value(4));
	}

	@Test
	void retriedCreateWithTheSameIdempotencyKeyMakesOneNotice() throws Exception {
		String key = "notice-retry-1";
		long first = ApiClient.json(createWithKey(this.professor, "{\"title\":\"한 번만\"}", key).andExpect(status().isCreated()))
			.get("noticeId")
			.asLong();
		createWithKey(this.professor, "{\"title\":\"한 번만\"}", key).andExpect(status().isOk())
			.andExpect(jsonPath("$.noticeId").value(first));
		this.student.get(notices(this.course)).andExpect(jsonPath("$.total").value(1));
		// 같은 과목의 다른 담당자가 같은 key를 쓰면 남의 공지를 돌려주지 않는다.
		long ta = this.data.user("TA");
		this.data.instructor(this.course, ta);
		createWithKey(new ApiClient(this.mvc, this.data.session(ta)), "{\"title\":\"다른 사람\"}", key)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		createWithKey(this.professor, "{\"title\":\"t\"}", "bad key!").andExpect(status().isBadRequest());
	}

	@Test
	void concurrentFirstNoticesShareOneBoard() throws Exception {
		int writers = 4;
		ExecutorService pool = Executors.newFixedThreadPool(writers);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> results = new ArrayList<>();
			for (int i = 0; i < writers; i++) {
				String title = "{\"title\":\"동시 " + i + "\"}";
				Callable<Integer> write = () -> {
					start.await();
					return this.professor.postJson(notices(this.course), title).andReturn().getResponse().getStatus();
				};
				results.add(pool.submit(write));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get()).isEqualTo(201);
			}
		}
		finally {
			pool.shutdownNow();
		}
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM boards WHERE course_id = :c AND board_type = 'K001'")
			.param("c", this.course)
			.query(Long.class)
			.single()).isEqualTo(1);
		this.student.get(notices(this.course)).andExpect(jsonPath("$.total").value(writers));
	}

	private long create(ApiClient client, long courseId, String json) throws Exception {
		return ApiClient.json(client.postJson(notices(courseId), json).andExpect(status().isCreated()))
			.get("noticeId")
			.asLong();
	}

	private ResultActions createWithKey(ApiClient client, String json, String key) throws Exception {
		return client.send(MockMvcRequestBuilders.post(notices(this.course))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)
			.header("Idempotency-Key", key));
	}

	private long receipts(long noticeId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM post_read_receipts WHERE post_id = :p")
			.param("p", noticeId)
			.query(Long.class)
			.single();
	}

	/** ApiClient와 같이 session과 CSRF token을 붙인 PUT. */
	private ResultActions put(ApiClient client, String path, String json) throws Exception {
		return client.send(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions withoutCsrf(MockHttpServletRequestBuilder request, Cookie session, String json)
			throws Exception {
		request.cookie(session);
		if (json != null) {
			request.contentType(MediaType.APPLICATION_JSON).content(json);
		}
		return this.mvc.perform(request);
	}

	static String notices(long courseId) {
		return "/api/courses/" + courseId + "/notices";
	}

	static String notice(long courseId, long noticeId) {
		return notices(courseId) + "/" + noticeId;
	}

}
