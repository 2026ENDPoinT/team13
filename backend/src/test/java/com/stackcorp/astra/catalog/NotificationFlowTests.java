package com.stackcorp.astra.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications.CourseNotification;
import com.stackcorp.astra.catalog.api.Notifications.DirectNotification;

import org.junit.jupiter.api.Test;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 알림을 일으키는 명령(공지, 자료, 과제)과 예약 알림 worker. 받는 사람마다 사건 하나에 알림 하나이고, 받는 사람은 알림을 만드는 때의 과목
 * 구성원이며, 원래 변경이 되돌아가면 알림도 남지 않는다.
 */
class NotificationFlowTests extends NotificationTestSupport {

	@Test
	void retriedNoticeCommandsNotifyEachStudentOnce() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long alice = student(course);
		long bob = student(course);
		long outsider = this.data.user("STUDENT");
		long otherCourseStudent = student(course(prof));
		ApiClient professor = client(prof);
		String key = "notice-" + UUID.randomUUID();

		long notice = ApiClient.json(professor.send(post("/api/courses/" + course + "/notices", key, "{\"title\":\"중간고사 안내\"}"))
			.andExpect(status().isCreated())).get("noticeId").asLong();
		// 같은 key와 같은 요청의 재전송은 멱등 원장이 처음 응답을 그대로 돌려준다(ADR-0012).
		professor.send(post("/api/courses/" + course + "/notices", key, "{\"title\":\"중간고사 안내\"}"))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.noticeId").value(notice));

		assertThat(rows(alice, NotificationKind.COURSE_NOTICE, notice)).isEqualTo(1);
		assertThat(rows(bob, NotificationKind.COURSE_NOTICE, notice)).isEqualTo(1);
		assertThat(rows(prof)).isZero();
		assertThat(rows(outsider)).isZero();
		assertThat(rows(otherCourseStudent)).isZero();
		assertThat(messages(alice)).containsExactly("새로운 공지사항이 있습니다. \"중간고사 안내\"");

		// 고쳐도 다시 보내지 않는다. 이미 받은 알림의 제목만 바뀌고 읽음은 그대로다.
		client(alice).post("/api/me/notifications/" + notificationId(alice) + "/read").andExpect(status().isOk());
		professor.send(MockMvcRequestBuilders.put("/api/courses/" + course + "/notices/" + notice)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"중간고사 장소 안내\",\"version\":1}")).andExpect(status().isOk());
		assertThat(rows(alice, NotificationKind.COURSE_NOTICE, notice)).isEqualTo(1);
		assertThat(messages(alice)).containsExactly("새로운 공지사항이 있습니다. \"중간고사 장소 안내\"");
		assertThat(readAt(notificationId(alice))).isNotNull();
	}

	@Test
	void retriedMaterialCommandNotifiesOnceAndDraftMaterialNotAtAll() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long alice = student(course);
		ApiClient professor = client(prof);
		String key = "material-" + UUID.randomUUID();
		String body = "{\"weekNo\":2,\"title\":\"9주차 자료\"}";

		long material = ApiClient.json(professor.send(post("/api/courses/" + course + "/materials", key, body))
			.andExpect(status().isCreated())).get("activityId").asLong();
		professor.send(post("/api/courses/" + course + "/materials", key, body)).andExpect(status().isCreated());
		assertThat(rows(alice, NotificationKind.LECTURE_MATERIAL, material)).isEqualTo(1);
		assertThat(rows(alice)).isEqualTo(1);

		professor.postJson("/api/courses/" + course + "/materials", "{\"weekNo\":2,\"title\":\"비공개\",\"published\":false}")
			.andExpect(status().isCreated());
		assertThat(rows(alice)).isEqualTo(1);
	}

	@Test
	void assignmentNotifiesWhenOpenedAndWhenScoresAreReleasedOnce() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long alice = student(course);
		ApiClient professor = client(prof);
		String base = "\"weekNo\":1,\"title\":\"과제 1\",\"closeAt\":\"2999-01-01T00:00:00\",\"maxScore\":10";
		var created = ApiClient.json(professor.postJson("/api/courses/" + course + "/assignments", "{" + base + "}")
			.andExpect(status().isCreated())).get("assignment");
		long assignment = created.get("assignmentId").asLong();
		long activity = created.get("activityId").asLong();
		assertThat(rows(alice, NotificationKind.ASSIGNMENT_OPENED, activity)).isEqualTo(1);
		assertThat(rows(alice, NotificationKind.ASSIGNMENT_GRADED, activity)).isZero();

		String path = "/api/courses/" + course + "/assignments/" + assignment;
		update(professor, path, base + ",\"scoresReleased\":true,\"revision\":1");
		update(professor, path, base + ",\"scoresReleased\":true,\"revision\":2");
		assertThat(rows(alice, NotificationKind.ASSIGNMENT_OPENED, activity)).isEqualTo(1);
		assertThat(rows(alice, NotificationKind.ASSIGNMENT_GRADED, activity)).isEqualTo(1);

		// 점수 공개를 거두면 그 알림이, 과제를 비공개로 돌리면 남은 알림도 사라진다.
		update(professor, path, base + ",\"scoresReleased\":false,\"revision\":3");
		assertThat(rows(alice, NotificationKind.ASSIGNMENT_GRADED, activity)).isZero();
		update(professor, path, base + ",\"published\":false,\"revision\":4");
		assertThat(rows(alice)).isZero();
	}

	@Test
	void rolledBackProducerLeavesNoNotificationAndCallsOutsideATransactionFail() {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long alice = student(course);
		CourseNotification notification = new CourseNotification(NotificationKind.COURSE_NOTICE, course,
				CourseRole.STUDENT, 990_000_000L + alice, "되돌릴 공지", null);

		assertThatThrownBy(() -> this.transaction.executeWithoutResult((tx) -> {
			this.notifications.publish(notification);
			assertThat(rows(alice)).isEqualTo(1);
			throw new IllegalStateException("producer failed");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(rows(alice)).isZero();
		assertThat(events(NotificationKind.COURSE_NOTICE, notification.targetId())).isZero();

		assertThatThrownBy(() -> this.notifications.publish(notification))
			.isInstanceOf(IllegalTransactionStateException.class);
		assertThat(rows(alice)).isZero();
	}

	@Test
	void recipientsAreTheCourseMembersWhenTheNotificationIsCreated() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long alice = student(course);
		long dropped = student(course);
		cancelEnrollment(course, dropped);
		ApiClient professor = client(prof);

		long first = notice(professor, course, "{\"title\":\"첫 공지\"}");
		long late = student(course);
		assertThat(rows(alice, NotificationKind.COURSE_NOTICE, first)).isEqualTo(1);
		assertThat(rows(dropped)).isZero();
		assertThat(rows(late)).isZero();

		// 직접 보내는 알림도 과목이 있으면 그 과목 구성원만 받는다.
		long outsider = this.data.user("STUDENT");
		this.transaction.executeWithoutResult((tx) -> this.notifications.send(new DirectNotification(
				NotificationKind.QUESTION_ANSWERED, List.of(alice, outsider, dropped, alice), course, 77_000L + alice,
				"과제 범위 질문")));
		assertThat(rows(alice, NotificationKind.QUESTION_ANSWERED, 77_000L + alice)).isEqualTo(1);
		assertThat(rows(outsider)).isZero();
		assertThat(rows(dropped)).isZero();

		List<Long> tooMany = new ArrayList<>();
		for (long i = 1; i <= 1001; i++) {
			tooMany.add(i);
		}
		assertThatThrownBy(() -> this.transaction.executeWithoutResult((tx) -> this.notifications
			.send(new DirectNotification(NotificationKind.MESSAGE_RECEIVED, tooMany, null, 1L, "쪽지"))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void scheduledNoticeNotifiesOnceWhenItBecomesVisible() throws Exception {
		long prof = this.data.user("PROFESSOR");
		long course = course(prof);
		long alice = student(course);
		ApiClient professor = client(prof);
		LocalDateTime publishAt = dbNowPlusMinutes(60);

		long notice = notice(professor, course, "{\"title\":\"예약 공지\",\"publishAt\":\"" + publishAt + "\"}");
		assertThat(rows(alice)).isZero();
		this.sweeper.deliverDue(publishAt.minusSeconds(1));
		assertThat(rows(alice)).isZero();

		// 쓴 때가 아니라 보일 때의 수강생이 받는다.
		long late = student(course);
		cancelEnrollment(course, alice);
		this.sweeper.deliverDue(publishAt);
		this.sweeper.deliverDue(publishAt.plusMinutes(5));
		assertThat(rows(late, NotificationKind.COURSE_NOTICE, notice)).isEqualTo(1);
		assertThat(rows(alice)).isZero();

		// 게시한 뒤 다시 미래로 미루면 보낸 알림을 거두고 새 시각에 한 번 다시 보낸다.
		LocalDateTime later = publishAt.plusHours(1);
		professor.send(MockMvcRequestBuilders.put("/api/courses/" + course + "/notices/" + notice)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"예약 공지\",\"publishAt\":\"" + later + "\",\"version\":1}")).andExpect(status().isOk());
		assertThat(rows(late)).isZero();
		this.sweeper.deliverDue(later);
		this.sweeper.deliverDue(later);
		assertThat(rows(late, NotificationKind.COURSE_NOTICE, notice)).isEqualTo(1);

		// 임시 공지로 돌리면 예약도 사라진다.
		professor.send(MockMvcRequestBuilders.put("/api/courses/" + course + "/notices/" + notice)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"title\":\"예약 공지\",\"published\":false,\"version\":2}")).andExpect(status().isOk());
		assertThat(rows(late)).isZero();
		assertThat(events(NotificationKind.COURSE_NOTICE, notice)).isZero();
	}

	@Test
	void twoWorkersDeliverEachScheduledNotificationOnce() throws Exception {
		long prof = this.data.user("PROFESSOR");
		ApiClient professor = client(prof);
		LocalDateTime publishAt = dbNowPlusMinutes(90);
		List<long[]> expected = new ArrayList<>();
		for (int c = 0; c < 6; c++) {
			long course = course(prof);
			long first = student(course);
			long second = student(course);
			for (int n = 0; n < 3; n++) {
				long notice = notice(professor, course,
						"{\"title\":\"예약 " + c + "-" + n + "\",\"publishAt\":\"" + publishAt + "\"}");
				expected.add(new long[] { first, notice });
				expected.add(new long[] { second, notice });
			}
		}

		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Integer>> runs = new ArrayList<>();
			for (int w = 0; w < 2; w++) {
				runs.add(pool.submit(() -> {
					start.await();
					int total = 0;
					int delivered;
					while ((delivered = this.sweeper.deliverDue(publishAt)) > 0) {
						total += delivered;
					}
					return total;
				}));
			}
			start.countDown();
			for (Future<Integer> run : runs) {
				run.get(60, TimeUnit.SECONDS);
			}
		}

		for (long[] pair : expected) {
			assertThat(rows(pair[0], NotificationKind.COURSE_NOTICE, pair[1])).isEqualTo(1);
			assertThat(pending(NotificationKind.COURSE_NOTICE, pair[1])).isZero();
		}
	}

	private long notificationId(long userId) {
		return this.jdbc.sql("SELECT notification_id FROM notifications WHERE user_id = :u")
			.param("u", userId)
			.query(Long.class)
			.single();
	}

	private Object readAt(long notificationId) {
		return this.jdbc.sql("SELECT read_at FROM notifications WHERE notification_id = :id")
			.param("id", notificationId)
			.query(LocalDateTime.class)
			.optional()
			.orElse(null);
	}

	private int events(NotificationKind kind, long targetId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM notification_events WHERE notification_type = :t AND target_ref_id = :r")
			.param("t", kind.name())
			.param("r", targetId)
			.query(Integer.class)
			.single();
	}

	private int pending(NotificationKind kind, long targetId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM notification_events
				 WHERE notification_type = :t AND target_ref_id = :r AND delivered_at IS NULL
				""").param("t", kind.name()).param("r", targetId).query(Integer.class).single();
	}

	private static void update(ApiClient professor, String path, String fields) throws Exception {
		professor.send(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content("{" + fields + "}"))
			.andExpect(status().isOk());
	}

	private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder post(String path,
			String key, String json) {
		return MockMvcRequestBuilders.post(path)
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
	}

}
