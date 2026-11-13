package com.stackcorp.astra.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications.CourseNotification;
import com.stackcorp.astra.catalog.api.Notifications.DirectNotification;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 내 알림함. 본인이 지금 볼 수 있는 알림만 보이고, 조회는 읽음을 바꾸지 않으며, 읽음·삭제는 본인 알림에만 몇 번 보내도 같은 결과다.
 */
class NotificationApiTests extends NotificationTestSupport {

	/** 알림 대상 ID. 대상 행은 다른 모듈의 것이라 값만 다르면 된다. */
	private static final AtomicLong TARGETS = new AtomicLong(System.currentTimeMillis() * 1000);

	@Test
	void listsOwnNotificationsNewestFirstWithKeysetPagesAndFilters() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long other = course(prof);
		long alice = student(course);
		this.data.enroll(other, alice);
		long bob = student(course);
		List<Long> targets = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			targets.add(publish(NotificationKind.COURSE_NOTICE, course, "공지 " + i));
		}
		long material = this.data.activity(other, "lecture_material", true, null, null);
		publish(NotificationKind.LECTURE_MATERIAL, other, "자료", material);
		ApiClient client = client(alice);

		JsonNode first = ApiClient.json(client.get("/api/me/notifications?size=2")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items[*].targetId").value(contains((Object) (int) material, targets.get(3))))
			.andExpect(jsonPath("$.items[0].kind").value("LECTURE_MATERIAL"))
			.andExpect(jsonPath("$.items[0].targetType").value("ACTIVITY"))
			.andExpect(jsonPath("$.items[0].courseName").value("Database"))
			.andExpect(jsonPath("$.items[0].read").value(false))
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.unreadCount").value(5)));
		// 쪽 사이에 새 알림이 생겨도 다음 쪽이 겹치거나 빠지지 않는다.
		publish(NotificationKind.COURSE_NOTICE, course, "새 공지");
		JsonNode second = ApiClient.json(client.get("/api/me/notifications?size=2&cursor=" + first.get("nextCursor").asString())
			.andExpect(jsonPath("$.items[*].targetId").value(contains(targets.get(2), targets.get(1))))
			.andExpect(jsonPath("$.total").value(6)));
		client.get("/api/me/notifications?size=2&cursor=" + second.get("nextCursor").asString())
			.andExpect(jsonPath("$.items[*].targetId").value(contains(targets.get(0))))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());

		client.get("/api/me/notifications?courseId=" + other)
			.andExpect(jsonPath("$.items[*].targetId").value(contains((int) material)))
			.andExpect(jsonPath("$.total").value(1));
		client.get("/api/me/notifications?kind=LECTURE_MATERIAL")
			.andExpect(jsonPath("$.items[*].targetId").value(contains((int) material)))
			.andExpect(jsonPath("$.total").value(1));
		client(bob).get("/api/me/notifications?courseId=" + other)
			.andExpect(jsonPath("$.items.length()").value(0))
			.andExpect(jsonPath("$.total").value(0));
		// 수강을 취소한 과목의 알림은 목록과 같이 수에서도 빠진다.
		cancelEnrollment(other, alice);
		client.get("/api/me/notifications").andExpect(jsonPath("$.total").value(5));

		client.get("/api/me/notifications?kind=DROP_TABLE").andExpect(status().isBadRequest());
		client.get("/api/me/notifications?cursor=not-a-cursor").andExpect(status().isBadRequest());
		client.get("/api/me/notifications?size=0").andExpect(status().isBadRequest());
		client.get("/api/me/notifications?size=51").andExpect(status().isBadRequest());
		new ApiClient(this.mvc, null).get("/api/me/notifications").andExpect(status().isUnauthorized());
		new ApiClient(this.mvc, null).get("/api/me/notifications/unread-count").andExpect(status().isUnauthorized());
	}

	@Test
	void readingIsAnExplicitIdempotentCommandOnOwnNotificationsOnly() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long alice = student(course);
		long bob = student(course);
		publish(NotificationKind.COURSE_NOTICE, course, "하나");
		publish(NotificationKind.COURSE_NOTICE, course, "둘");
		publish(NotificationKind.COURSE_NOTICE, course, "셋");
		ApiClient aliceClient = client(alice);
		ApiClient bobClient = client(bob);
		List<Long> mine = ids(alice);
		long bobs = ids(bob).getFirst();

		// 목록과 수 조회는 읽음을 남기지 않는다.
		aliceClient.get("/api/me/notifications").andExpect(jsonPath("$.unreadCount").value(3));
		aliceClient.get("/api/me/notifications/unread-count")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(3));

		aliceClient.post("/api/me/notifications/" + mine.get(0) + "/read")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.notification.read").value(true))
			.andExpect(jsonPath("$.notification.targetType").value("ARTICLE"))
			.andExpect(jsonPath("$.unreadCount").value(2));
		aliceClient.post("/api/me/notifications/" + mine.get(0) + "/read")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.unreadCount").value(2));
		aliceClient.get("/api/me/notifications?unread=true").andExpect(jsonPath("$.items.length()").value(2));

		// 다른 사람의 알림은 ID를 알아도 읽거나 지울 수 없다.
		aliceClient.post("/api/me/notifications/" + bobs + "/read").andExpect(status().isNotFound());
		aliceClient.delete("/api/me/notifications/" + bobs).andExpect(status().isNotFound());
		aliceClient.post("/api/me/notifications/999999999/read").andExpect(status().isNotFound());
		bobClient.get("/api/me/notifications/unread-count").andExpect(jsonPath("$.unreadCount").value(3));
		assertThat(rows(bob)).isEqualTo(3);

		aliceClient.post("/api/me/notifications/read-all")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.updated").value(2))
			.andExpect(jsonPath("$.unreadCount").value(0));
		aliceClient.post("/api/me/notifications/read-all").andExpect(jsonPath("$.updated").value(0));
		bobClient.get("/api/me/notifications/unread-count").andExpect(jsonPath("$.unreadCount").value(3));

		aliceClient.delete("/api/me/notifications/" + mine.get(1)).andExpect(status().isNoContent());
		aliceClient.delete("/api/me/notifications/" + mine.get(1)).andExpect(status().isNotFound());
		assertThat(rows(alice)).isEqualTo(2);

		// 상태를 바꾸는 요청은 CSRF token이 있어야 하고, 로그인하지 않으면 401이다.
		Cookie session = new Cookie("ASTRA_SESSION", this.data.session(alice));
		this.mvc.perform(post("/api/me/notifications/" + mine.get(2) + "/read").cookie(session))
			.andExpect(status().isForbidden());
		this.mvc.perform(MockMvcRequestBuilders.delete("/api/me/notifications/" + mine.get(2)).cookie(session))
			.andExpect(status().isForbidden());
		new ApiClient(this.mvc, null).post("/api/me/notifications/" + mine.get(2) + "/read")
			.andExpect(status().isUnauthorized());
		assertThat(rows(alice)).isEqualTo(2);
	}

	@Test
	void notificationsDisappearWhenTheObjectOrTheMembershipIsGone() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long alice = student(course);
		long bob = student(course);
		ApiClient professor = client(prof);
		long deleted = notice(professor, course, "{\"title\":\"지울 공지\"}");
		long drafted = notice(professor, course, "{\"title\":\"임시로 돌릴 공지\"}");
		long kept = notice(professor, course, "{\"title\":\"남는 공지\"}");
		ApiClient aliceClient = client(alice);
		aliceClient.get("/api/me/notifications/unread-count").andExpect(jsonPath("$.unreadCount").value(3));

		professor.delete("/api/courses/" + course + "/notices/" + deleted).andExpect(status().isNoContent());
		professor.send(MockMvcRequestBuilders.put("/api/courses/" + course + "/notices/" + drafted)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"임시로 돌릴 공지\",\"published\":false,\"version\":1}")).andExpect(status().isOk());
		aliceClient.get("/api/me/notifications")
			.andExpect(jsonPath("$.items[*].targetId").value(contains((int) kept)))
			.andExpect(jsonPath("$.unreadCount").value(1));

		// 수강을 취소하면 그 과목의 알림은 목록, 수, 읽음, 삭제 어디서도 보이지 않는다.
		long keptId = ids(alice).getFirst();
		cancelEnrollment(course, alice);
		aliceClient.get("/api/me/notifications").andExpect(jsonPath("$.items.length()").value(0));
		aliceClient.get("/api/me/notifications/unread-count").andExpect(jsonPath("$.unreadCount").value(0));
		aliceClient.post("/api/me/notifications/" + keptId + "/read").andExpect(status().isNotFound());
		aliceClient.delete("/api/me/notifications/" + keptId).andExpect(status().isNotFound());
		aliceClient.post("/api/me/notifications/read-all").andExpect(jsonPath("$.updated").value(0));
		client(bob).get("/api/me/notifications/unread-count").andExpect(jsonPath("$.unreadCount").value(1));

		// 과목 없는 알림(쪽지)은 과목 관계와 관계없이 받는 사람에게 보인다.
		this.transaction.executeWithoutResult((tx) -> this.notifications
			.send(new DirectNotification(NotificationKind.MESSAGE_RECEIVED, List.of(alice), null, 55_000L + alice, "안녕하세요")));
		aliceClient.get("/api/me/notifications")
			.andExpect(jsonPath("$.items[*].kind").value(contains("MESSAGE_RECEIVED")))
			.andExpect(jsonPath("$.items[0].courseId").doesNotExist())
			.andExpect(jsonPath("$.items[0].message").value("새 쪽지가 도착했습니다. \"안녕하세요\""));
	}

	@Test
	void expiredNotificationsAreHiddenAndPurged() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long alice = student(course);
		publish(NotificationKind.COURSE_NOTICE, course, "오래된 공지");
		publish(NotificationKind.COURSE_NOTICE, course, "새 공지");
		long old = ids(alice).getFirst();
		this.jdbc.sql("UPDATE notifications SET expires_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE notification_id = :id")
			.param("id", old)
			.update();

		ApiClient client = client(alice);
		client.get("/api/me/notifications").andExpect(jsonPath("$.items.length()").value(1));
		client.post("/api/me/notifications/" + old + "/read").andExpect(status().isNotFound());
		this.sweeper.purgeExpired(dbNowPlusMinutes(0));
		assertThat(rows(alice)).isEqualTo(1);
	}

	/** 공지 유형처럼 학습활동이 아닌 대상으로 수강생 모두에게 지금 보낸다. @return 대상 ID */
	private long publish(NotificationKind kind, long courseId, String subject) {
		return publish(kind, courseId, subject, TARGETS.incrementAndGet());
	}

	private long publish(NotificationKind kind, long courseId, String subject, long target) {
		this.transaction.executeWithoutResult((tx) -> this.notifications
			.publish(new CourseNotification(kind, courseId, CourseRole.STUDENT, target, subject, null)));
		return target;
	}

	private List<Long> ids(long userId) {
		return this.jdbc.sql("SELECT notification_id FROM notifications WHERE user_id = :u ORDER BY notification_id")
			.param("u", userId)
			.query(Long.class)
			.list();
	}

}
