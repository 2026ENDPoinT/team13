package com.stackcorp.astra.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;

/**
 * 메인 화면 집계와 할 일. 요청한 사용자의 과목만 담고, 공지·활동의 공개 여부와 기간은 DB 시계로 판정한다.
 */
class HomeApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long prof;

	ApiClient professor;

	@BeforeEach
	void professor() {
		this.prof = this.data.user("PROFESSOR");
		this.professor = new ApiClient(this.mvc, this.data.session(this.prof));
	}

	@Test
	void containsOnlyTheCallersCoursesNoticesAndTodos() throws Exception {
		long c1 = course();
		long c2 = course();
		long alice = this.data.user("STUDENT");
		long bob = this.data.user("STUDENT");
		this.data.enroll(c1, alice);
		this.data.enroll(c2, bob);
		long n1 = notice(c1, "{\"title\":\"1반 공지\"}");
		long n2 = notice(c2, "{\"title\":\"2반 공지\"}");
		long a1 = activity(c1, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		long a2 = activity(c2, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		ApiClient aliceClient = new ApiClient(this.mvc, this.data.session(alice));
		ApiClient bobClient = new ApiClient(this.mvc, this.data.session(bob));

		aliceClient.get("/api/me/home")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.serverTime").exists())
			.andExpect(jsonPath("$.courses[*].courseId").value(contains((int) c1)))
			.andExpect(jsonPath("$.courses[0].role").value("STUDENT"))
			.andExpect(jsonPath("$.courses[0].unreadNotices").value(1))
			.andExpect(jsonPath("$.notices[*].noticeId").value(contains((int) n1)))
			.andExpect(jsonPath("$.notices[0].courseName").value("Database"))
			.andExpect(jsonPath("$.notices[0].read").value(false))
			.andExpect(jsonPath("$.todo.items[*].activityId").value(contains((int) a1)))
			.andExpect(jsonPath("$.todo.total").value(1));
		bobClient.get("/api/me/home")
			.andExpect(jsonPath("$.courses[*].courseId").value(contains((int) c2)))
			.andExpect(jsonPath("$.notices[*].noticeId").value(contains((int) n2)))
			.andExpect(jsonPath("$.todo.items[*].activityId").value(contains((int) a2)))
			.andExpect(jsonPath("$.todo.total").value(1));
		bobClient.get("/api/me/todo").andExpect(jsonPath("$.items[*].activityId").value(contains((int) a2)));

		// 담당자는 두 과목을 담당자로 본다. 자기가 쓴 공지는 안 읽은 수에 들지 않고, 담당 과목의 활동은 할 일이 아니다.
		this.professor.get("/api/me/home")
			.andExpect(jsonPath("$.courses[*].courseId").value(containsInAnyOrder((int) c1, (int) c2)))
			.andExpect(jsonPath("$.courses[*].role").value(contains("INSTRUCTOR", "INSTRUCTOR")))
			.andExpect(jsonPath("$.courses[*].unreadNotices").value(contains(0, 0)))
			.andExpect(jsonPath("$.notices[*].noticeId").value(contains((int) n2, (int) n1)))
			.andExpect(jsonPath("$.todo.items.length()").value(0))
			.andExpect(jsonPath("$.todo.total").value(0));

		// 읽음은 명시적 명령으로만 바뀌고 그 사람에게만 반영된다.
		aliceClient.post("/api/courses/" + c1 + "/notices/" + n1 + "/read").andExpect(status().isOk());
		aliceClient.get("/api/me/home")
			.andExpect(jsonPath("$.courses[0].unreadNotices").value(0))
			.andExpect(jsonPath("$.notices[0].read").value(true));
		long carol = this.data.user("STUDENT");
		this.data.enroll(c1, carol);
		new ApiClient(this.mvc, this.data.session(carol)).get("/api/me/home")
			.andExpect(jsonPath("$.courses[0].unreadNotices").value(1))
			.andExpect(jsonPath("$.notices[0].read").value(false));

		new ApiClient(this.mvc, null).get("/api/me/home").andExpect(status().isUnauthorized());
		new ApiClient(this.mvc, null).get("/api/me/todo").andExpect(status().isUnauthorized());
	}

	@Test
	void draftAndScheduledNoticesStayOutOfCountsAndLatest() throws Exception {
		long c = course();
		long stu = this.data.user("STUDENT");
		this.data.enroll(c, stu);
		notice(c, "{\"title\":\"임시\",\"published\":false}");
		notice(c, "{\"title\":\"예약\",\"publishAt\":\"2999-01-01T09:00:00\"}");
		long visible = notice(c, "{\"title\":\"게시\"}");
		long deleted = notice(c, "{\"title\":\"지울 공지\"}");
		this.professor.delete("/api/courses/" + c + "/notices/" + deleted).andExpect(status().isNoContent());
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));

		student.get("/api/me/home")
			.andExpect(jsonPath("$.courses[0].unreadNotices").value(1))
			.andExpect(jsonPath("$.notices[*].noticeId").value(contains((int) visible)));
		// 담당자의 메인에도 아직 게시되지 않은 공지는 나오지 않는다.
		this.professor.get("/api/me/home").andExpect(jsonPath("$.notices[*].noticeId").value(contains((int) visible)));
	}

	@Test
	void todoFollowsPublishedOpenAndCloseBoundariesOnTheDatabaseClock() throws Exception {
		long c = course();
		long taught = course();
		long stu = this.data.user("STUDENT");
		this.data.enroll(c, stu);
		this.data.instructor(taught, stu);
		long noOpenAt = activity(c, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 2 DAY");
		long openedNow = activity(c, true, "CURRENT_TIMESTAMP", "CURRENT_TIMESTAMP + INTERVAL 3 DAY");
		long closingSoon = activity(c, true, "CURRENT_TIMESTAMP - INTERVAL 1 DAY", "CURRENT_TIMESTAMP + INTERVAL 1 MINUTE");
		activity(c, true, "CURRENT_TIMESTAMP + INTERVAL 1 HOUR", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		activity(c, false, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		activity(c, true, "NULL", "CURRENT_TIMESTAMP - INTERVAL 1 SECOND");
		// 마감 일시가 지금과 같으면 이미 마감이다(openAt <= now < closeAt, SC-06).
		activity(c, true, "NULL", "CURRENT_TIMESTAMP");
		activity(c, true, "NULL", "NULL");
		activity(taught, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));

		student.get("/api/me/todo")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items[*].activityId").value(contains((int) closingSoon, (int) noOpenAt, (int) openedNow)))
			.andExpect(jsonPath("$.items[0].courseName").value("Database"))
			.andExpect(jsonPath("$.items[0].type").value("report"))
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		student.get("/api/me/home")
			.andExpect(jsonPath("$.todo.items[*].activityId").value(contains((int) closingSoon, (int) noOpenAt, (int) openedNow)))
			.andExpect(jsonPath("$.todo.total").value(3));

		// 마감이 지나면 다음 요청부터 빠진다.
		this.jdbc.sql("UPDATE learning_activities SET close_at = CURRENT_TIMESTAMP - INTERVAL 1 SECOND WHERE activity_id = :a")
			.param("a", closingSoon)
			.update();
		student.get("/api/me/todo").andExpect(jsonPath("$.total").value(2));
	}

	@Test
	void pagesTheTodoListWithAnOpaqueCursor() throws Exception {
		long c = course();
		long stu = this.data.user("STUDENT");
		this.data.enroll(c, stu);
		List<Long> ids = new ArrayList<>();
		for (int i = 1; i <= 2; i++) {
			ids.add(activity(c, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL " + i + " DAY"));
		}
		// 마감 일시가 같으면 ID 순서다.
		String sameClose = "'" + this.jdbc.sql("SELECT CAST(CURRENT_TIMESTAMP + INTERVAL 3 DAY AS CHAR)")
			.query(String.class)
			.single() + "'";
		ids.add(activity(c, true, "NULL", sameClose));
		ids.add(activity(c, true, "NULL", sameClose));
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));

		JsonNode first = ApiClient.json(student.get("/api/me/todo?size=2")
			.andExpect(jsonPath("$.items[*].activityId").value(contains(ids.get(0).intValue(), ids.get(1).intValue())))
			.andExpect(jsonPath("$.total").value(4)));
		String cursor = first.get("nextCursor").asString();
		JsonNode second = ApiClient.json(student.get("/api/me/todo?size=2&cursor=" + cursor)
			.andExpect(jsonPath("$.items[*].activityId").value(contains(ids.get(2).intValue(), ids.get(3).intValue())))
			.andExpect(jsonPath("$.total").value(4)));
		assertThat(second.get("nextCursor").isNull()).isTrue();

		student.get("/api/me/todo?cursor=not-a-cursor").andExpect(status().isBadRequest());
		student.get("/api/me/todo?size=0").andExpect(status().isBadRequest());
		student.get("/api/me/todo?size=51").andExpect(status().isBadRequest());
	}

	@Test
	void droppedEnrollmentRemovesTheCourseItsNoticesAndTodos() throws Exception {
		long c = course();
		long stu = this.data.user("STUDENT");
		this.data.enroll(c, stu);
		long n = notice(c, "{\"title\":\"공지\"}");
		activity(c, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));
		student.get("/api/me/home").andExpect(jsonPath("$.courses.length()").value(1));

		this.jdbc.sql("""
				UPDATE course_enrollments SET enrollment_status = 'CANCELLED', cancelled_at = CURRENT_TIMESTAMP(3)
				 WHERE course_id = :c AND user_id = :u
				""").param("c", c).param("u", stu).update();

		student.get("/api/me/home")
			.andExpect(jsonPath("$.courses.length()").value(0))
			.andExpect(jsonPath("$.notices.length()").value(0))
			.andExpect(jsonPath("$.todo.items.length()").value(0))
			.andExpect(jsonPath("$.todo.total").value(0));
		student.get("/api/me/todo").andExpect(jsonPath("$.total").value(0));
		student.get("/api/courses/" + c + "/notices").andExpect(status().isNotFound());
		student.get("/api/courses/" + c + "/notices/" + n).andExpect(status().isNotFound());
		student.post("/api/courses/" + c + "/notices/" + n + "/read").andExpect(status().isNotFound());
	}

	@Test
	void unreadNotificationCountFollowsTheInbox() throws Exception {
		long c = course();
		long stu = this.data.user("STUDENT");
		this.data.enroll(c, stu);
		notice(c, "{\"title\":\"알림 1\"}");
		notice(c, "{\"title\":\"알림 2\"}");
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));

		student.get("/api/me/home").andExpect(jsonPath("$.unreadNotifications").value(2));
		this.professor.get("/api/me/home").andExpect(jsonPath("$.unreadNotifications").value(0));
		student.post("/api/me/notifications/read-all").andExpect(status().isOk());
		student.get("/api/me/home").andExpect(jsonPath("$.unreadNotifications").value(0));
	}

	@Test
	void statementCountDoesNotGrowWithTheNumberOfCourses() throws Exception {
		ApiClient few = studentWithCourses(2);
		ApiClient many = studentWithCourses(10);
		few.get("/api/me/home").andExpect(jsonPath("$.courses.length()").value(2));
		many.get("/api/me/home")
			.andExpect(jsonPath("$.courses.length()").value(10))
			.andExpect(jsonPath("$.notices.length()").value(5))
			.andExpect(jsonPath("$.todo.total").value(10))
			.andExpect(jsonPath("$.unreadNotifications").value(10));

		long fewStatements = statements(few);
		long manyStatements = statements(many);
		// 과목마다 쿼리를 더 보내지 않는다. 시계, 과목, 할 일 2, 공지 2, 알림 수 1과 session 확인, transaction 문장뿐이다.
		assertThat(manyStatements).isEqualTo(fewStatements);
		assertThat(manyStatements).isLessThanOrEqualTo(20);
	}

	private ApiClient studentWithCourses(int count) throws Exception {
		long stu = this.data.user("STUDENT");
		for (int i = 0; i < count; i++) {
			long c = course();
			this.data.enroll(c, stu);
			notice(c, "{\"title\":\"공지 " + i + "\"}");
			activity(c, true, "NULL", "CURRENT_TIMESTAMP + INTERVAL 1 DAY");
		}
		return new ApiClient(this.mvc, this.data.session(stu));
	}

	/**
	 * GET /api/me/home 한 번 동안 DB가 받은 문장 수. 서버 전체 counter라 다른 작업이 끼면 늘어나기만 하므로 세 번 재어 가장 작은 값을 쓴다.
	 */
	private long statements(ApiClient client) throws Exception {
		long min = Long.MAX_VALUE;
		for (int i = 0; i < 3; i++) {
			long before = questions();
			client.get("/api/me/home").andExpect(status().isOk());
			min = Math.min(min, questions() - before);
		}
		return min;
	}

	private long questions() {
		return this.jdbc.sql("SHOW GLOBAL STATUS LIKE 'Questions'").query((rs, n) -> rs.getLong(2)).single();
	}

	private long course() {
		long c = this.data.course();
		this.data.instructor(c, this.prof);
		return c;
	}

	private long notice(long courseId, String json) throws Exception {
		return ApiClient.json(this.professor.postJson("/api/courses/" + courseId + "/notices", json)
			.andExpect(status().isCreated())).get("noticeId").asLong();
	}

	/**
	 * @param openAt SQL 식. DB 시계 기준으로 쓴다
	 * @param closeAt SQL 식
	 */
	private long activity(long courseId, boolean published, String openAt, String closeAt) {
		long week = this.data.insert("INSERT INTO course_weeks (course_id, week_no) VALUES (:c, :n)",
				Map.of("c", courseId, "n", (int) (Math.random() * 1_000_000) + 1));
		return this.data.insert("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, published_yn, open_at, close_at)
				VALUES (:c, :w, 'report', '과제', :pub, %s, %s)
				""".formatted(openAt, closeAt), Map.of("c", courseId, "w", week, "pub", published));
	}

}
