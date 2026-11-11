package com.stackcorp.astra.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications.CourseNotification;
import com.stackcorp.astra.catalog.api.Notifications.SiteNotification;
import com.stackcorp.astra.identity.api.UserAudiences.UserAudience;

import org.junit.jupiter.api.Test;

import org.springframework.transaction.IllegalTransactionStateException;

/**
 * 과목 없는 전체 알림(알림마당 공지, ADR-0036). 만든 쪽 transaction에는 사건 하나만 남고, worker가 보일 시각 뒤의 대상 사용자에게
 * 묶음마다 transaction 하나로 보낸다. 받는 사람마다 하나이고, worker가 둘이어도 같다. 테스트는 끝에서 사건을 거둬 다른 테스트의
 * 사용자에게 남기지 않는다.
 */
class SiteNotificationTests extends NotificationTestSupport {

	private static final AtomicLong TARGETS = new AtomicLong(System.currentTimeMillis() * 1000 + 500_000);

	@Test
	void fansOutToTheAudienceAtDeliveryTimeInBatches() throws Exception {
		long student = this.data.user("STUDENT");
		long professor = this.data.user("PROFESSOR");
		long staff = this.data.user("STAFF");
		long suspended = this.data.user("STUDENT");
		this.jdbc.sql("UPDATE users SET account_status = 'SUSPENDED' WHERE user_id = :u").param("u", suspended).update();
		long breakGlass = breakGlassUser();
		long all = TARGETS.incrementAndGet();
		long students = TARGETS.incrementAndGet();
		long employees = TARGETS.incrementAndGet();
		try {
			publish(all, UserAudience.ALL, "학사 일정 안내", null);
			publish(students, UserAudience.STUDENT, "장학 안내", null);
			publish(employees, UserAudience.STAFF, "교직원 연수", null);
			// 만든 transaction은 사건만 남긴다. 사용자는 worker가 나눠 보낸다.
			assertThat(targetRows(all) + targetRows(students) + targetRows(employees)).isZero();

			deliverAll(dbNowPlusMinutes(1), all, students, employees);

			assertThat(rows(student, NotificationKind.SITE_NOTICE, all)).isEqualTo(1);
			assertThat(rows(professor, NotificationKind.SITE_NOTICE, all)).isEqualTo(1);
			assertThat(rows(staff, NotificationKind.SITE_NOTICE, all)).isEqualTo(1);
			assertThat(rows(suspended, NotificationKind.SITE_NOTICE, all)).isZero();
			assertThat(rows(breakGlass, NotificationKind.SITE_NOTICE, all)).isZero();
			assertThat(targetRows(all)).isEqualTo(audienceSize(UserAudience.ALL));
			assertThat(rows(student, NotificationKind.SITE_NOTICE, students)).isEqualTo(1);
			assertThat(rows(professor, NotificationKind.SITE_NOTICE, students)).isZero();
			assertThat(targetRows(students)).isEqualTo(audienceSize(UserAudience.STUDENT));
			assertThat(rows(student, NotificationKind.SITE_NOTICE, employees)).isZero();
			assertThat(rows(staff, NotificationKind.SITE_NOTICE, employees)).isEqualTo(1);
			assertThat(targetRows(employees)).isEqualTo(audienceSize(UserAudience.STAFF));
			// 시험 설정의 묶음(25명)보다 대상이 많아 여러 묶음으로 나눠 보냈다.
			assertThat(audienceSize(UserAudience.ALL)).isGreaterThan(25);
			assertThat(pending(all)).isZero();

			// 받는 사람 본인만 보고, 과목이 없어 과목 이름 없이 나온다. 다른 사람은 ID를 알아도 404다.
			ApiClient mine = client(student);
			mine.get("/api/me/notifications?kind=SITE_NOTICE")
				.andExpect(jsonPath("$.items[?(@.targetId == %d)].message", students).value("새로운 공지사항이 있습니다. \"장학 안내\""))
				.andExpect(jsonPath("$.items[?(@.targetId == %d)].targetType", all).value("ARTICLE"))
				.andExpect(jsonPath("$.total").value(2));
			long id = notificationOf(student, all);
			client(professor).post("/api/me/notifications/" + id + "/read").andExpect(status().isNotFound());
			mine.post("/api/me/notifications/" + id + "/read").andExpect(status().isOk());
			// 보존 기간은 다른 알림과 같다.
			this.jdbc.sql("UPDATE notifications SET expires_at = CURRENT_TIMESTAMP(3) - INTERVAL 1 SECOND WHERE notification_id = :id")
				.param("id", id)
				.update();
			mine.get("/api/me/notifications?kind=SITE_NOTICE").andExpect(jsonPath("$.total").value(1));
		}
		finally {
			withdraw(all, students, employees);
		}
		assertThat(targetRows(all) + targetRows(students) + targetRows(employees)).isZero();
		assertThat(events(all) + events(students) + events(employees)).isZero();
	}

	@Test
	void scheduledRepublishAndWithdrawFollowTheEventModel() {
		long student = this.data.user("STUDENT");
		long professor = this.data.user("PROFESSOR");
		long target = TARGETS.incrementAndGet();
		LocalDateTime visibleAt = dbNowPlusMinutes(60 * 24 * 30);
		try {
			publish(target, UserAudience.ALL, "예약 공지", visibleAt);
			this.sweeper.deliverDue(visibleAt.minusSeconds(1));
			assertThat(targetRows(target)).isZero();
			// 보일 시각의 대상이 받는다. 예약한 뒤에 생긴 사용자도 받는다.
			long late = this.data.user("STAFF");
			deliverAll(visibleAt, target);
			assertThat(rows(late, NotificationKind.SITE_NOTICE, target)).isEqualTo(1);
			int delivered = targetRows(target);
			assertThat(delivered).isEqualTo(audienceSize(UserAudience.ALL));

			// 다시 게시해도 다시 보내지 않고 문장만 바꾼다.
			publish(target, UserAudience.ALL, "예약 공지(수정)", null);
			deliverAll(visibleAt, target);
			assertThat(targetRows(target)).isEqualTo(delivered);
			assertThat(rows(student, NotificationKind.SITE_NOTICE, target)).isEqualTo(1);
			assertThat(messages(student)).containsExactly("새로운 공지사항이 있습니다. \"예약 공지(수정)\"");
			assertThat(events(target)).isEqualTo(1);

			// 대상을 바꾸면 보낸 알림을 거두고 새 대상에게 처음부터 보낸다.
			publish(target, UserAudience.STUDENT, "학생 공지", null);
			assertThat(targetRows(target)).isZero();
			deliverAll(dbNowPlusMinutes(1), target);
			assertThat(rows(student, NotificationKind.SITE_NOTICE, target)).isEqualTo(1);
			assertThat(rows(professor, NotificationKind.SITE_NOTICE, target)).isZero();
			assertThat(targetRows(target)).isEqualTo(audienceSize(UserAudience.STUDENT));

			// 보낸 뒤 미래로 미루면 거두고 그 시각에 다시 보낸다.
			LocalDateTime later = visibleAt.plusDays(30);
			publish(target, UserAudience.STUDENT, "학생 공지", later);
			assertThat(targetRows(target)).isZero();
			assertThat(pending(target)).isEqualTo(1);
			deliverAll(later, target);
			assertThat(rows(student, NotificationKind.SITE_NOTICE, target)).isEqualTo(1);
		}
		finally {
			withdraw(target);
		}
		assertThat(targetRows(target)).isZero();
		assertThat(events(target)).isZero();
	}

	@Test
	void twoWorkersSendEachUserOneNotification() throws Exception {
		long target = TARGETS.incrementAndGet();
		LocalDateTime now = dbNowPlusMinutes(1);
		try {
			publish(target, UserAudience.ALL, "동시 발송", null);
			CountDownLatch start = new CountDownLatch(1);
			try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
				List<Future<Integer>> runs = new ArrayList<>();
				for (int w = 0; w < 2; w++) {
					runs.add(pool.submit(() -> {
						start.await();
						int total = 0;
						for (int i = 0; i < 200 && pending(target) > 0; i++) {
							total += this.sweeper.deliverDue(now);
						}
						return total;
					}));
				}
				start.countDown();
				for (Future<Integer> run : runs) {
					run.get(120, TimeUnit.SECONDS);
				}
			}
			assertThat(pending(target)).isZero();
			assertThat(targetRows(target)).isEqualTo(audienceSize(UserAudience.ALL));
			assertThat(this.jdbc.sql("""
					SELECT COUNT(*) FROM (SELECT user_id FROM notifications WHERE notification_type = 'SITE_NOTICE'
					                       AND target_ref_id = :r GROUP BY user_id HAVING COUNT(*) > 1) d
					""").param("r", target).query(Integer.class).single()).isZero();
		}
		finally {
			withdraw(target);
		}
	}

	@Test
	void siteAndCourseKindsCannotBeMixedAndCallsNeedTheProducersTransaction() {
		long target = TARGETS.incrementAndGet();
		assertThatThrownBy(() -> this.transaction.executeWithoutResult((tx) -> this.notifications.publishSite(
				new SiteNotification(NotificationKind.COURSE_NOTICE, UserAudience.ALL, target, "x", null))))
			.isInstanceOf(IllegalArgumentException.class);
		long course = course(this.data.user("PROFESSOR"));
		assertThatThrownBy(() -> this.transaction.executeWithoutResult((tx) -> this.notifications.publish(
				new CourseNotification(NotificationKind.SITE_NOTICE, course, CourseRole.STUDENT, target, "x", null))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> this.notifications.publishSite(
				new SiteNotification(NotificationKind.SITE_NOTICE, UserAudience.ALL, target, "x", null)))
			.isInstanceOf(IllegalTransactionStateException.class);
		// 되돌린 transaction은 사건을 남기지 않는다.
		assertThatThrownBy(() -> this.transaction.executeWithoutResult((tx) -> {
			this.notifications.publishSite(new SiteNotification(NotificationKind.SITE_NOTICE, UserAudience.ALL, target,
					"되돌릴 공지", null));
			throw new IllegalStateException("producer failed");
		})).isInstanceOf(IllegalStateException.class);
		assertThat(events(target)).isZero();
	}

	private void publish(long target, UserAudience audience, String title, LocalDateTime visibleAt) {
		this.transaction.executeWithoutResult((tx) -> this.notifications
			.publishSite(new SiteNotification(NotificationKind.SITE_NOTICE, audience, target, title, visibleAt)));
	}

	private void withdraw(long... targets) {
		this.transaction.executeWithoutResult((tx) -> {
			for (long target : targets) {
				this.notifications.withdraw(NotificationKind.SITE_NOTICE, target);
			}
		});
	}

	/** 다른 worker(시험 context의 주기 실행)가 잡고 있으면 건너뛰므로 사건이 끝날 때까지 몇 번 다시 부른다. */
	private void deliverAll(LocalDateTime now, long... targets) {
		for (int i = 0; i < 200; i++) {
			this.sweeper.deliverDue(now);
			long left = 0;
			for (long target : targets) {
				left += pending(target);
			}
			if (left == 0) {
				return;
			}
		}
		throw new AssertionError("site notification not delivered");
	}

	private long breakGlassUser() {
		return this.jdbc.sql("SELECT user_id FROM users WHERE external_ref = :ref")
			.param("ref", BREAK_GLASS_REF)
			.query(Long.class)
			.optional()
			.orElseGet(() -> this.data.insert(
					"INSERT INTO users (external_ref, user_name, user_role) VALUES (:ref, '관리자', 'STAFF')",
					Map.of("ref", BREAK_GLASS_REF)));
	}

	private int audienceSize(UserAudience audience) {
		String role = switch (audience) {
			case ALL -> "";
			case STUDENT -> " AND user_role = 'STUDENT'";
			case STAFF -> " AND user_role <> 'STUDENT'";
		};
		return this.jdbc.sql("SELECT COUNT(*) FROM users WHERE account_status = 'ACTIVE' AND external_ref <> :ref" + role)
			.param("ref", BREAK_GLASS_REF)
			.query(Integer.class)
			.single();
	}

	private int targetRows(long target) {
		return this.jdbc.sql("SELECT COUNT(*) FROM notifications WHERE notification_type = 'SITE_NOTICE' AND target_ref_id = :r")
			.param("r", target)
			.query(Integer.class)
			.single();
	}

	private int events(long target) {
		return this.jdbc.sql("SELECT COUNT(*) FROM notification_events WHERE notification_type = 'SITE_NOTICE' AND target_ref_id = :r")
			.param("r", target)
			.query(Integer.class)
			.single();
	}

	private int pending(long target) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM notification_events
				 WHERE notification_type = 'SITE_NOTICE' AND target_ref_id = :r AND delivered_at IS NULL
				""").param("r", target).query(Integer.class).single();
	}

	private long notificationOf(long userId, long target) {
		return this.jdbc.sql("""
				SELECT notification_id FROM notifications WHERE user_id = :u AND notification_type = 'SITE_NOTICE'
				   AND target_ref_id = :r
				""").param("u", userId).param("r", target).query(Long.class).single();
	}

}
