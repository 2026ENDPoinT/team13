package com.stackcorp.astra.catalog;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 테스트의 공통 준비. 테스트마다 새 사용자와 과목을 만들고, 알림 행은 DB에서 직접 센다.
 */
abstract class NotificationTestSupport extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	Notifications notifications;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	NotificationSweeper sweeper;

	ApiClient client(long userId) {
		return new ApiClient(this.mvc, this.data.session(userId));
	}

	long course(long professorId) {
		long course = this.data.course();
		this.data.instructor(course, professorId);
		return course;
	}

	long student(long courseId) {
		long user = this.data.user("STUDENT");
		this.data.enroll(courseId, user);
		return user;
	}

	long notice(ApiClient professor, long courseId, String json) throws Exception {
		return ApiClient.json(professor.postJson("/api/courses/" + courseId + "/notices", json)
			.andExpect(status().isCreated())).get("noticeId").asLong();
	}

	/** 그 사용자가 받은 (유형, 대상)의 알림 행 수. 읽기 조건 없이 테이블을 그대로 센다. */
	int rows(long userId, NotificationKind kind, long targetId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM notifications WHERE user_id = :u AND notification_type = :t AND target_ref_id = :r
				""").param("u", userId).param("t", kind.name()).param("r", targetId).query(Integer.class).single();
	}

	/** 그 사용자의 알림 행 전체 수. */
	int rows(long userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM notifications WHERE user_id = :u")
			.param("u", userId)
			.query(Integer.class)
			.single();
	}

	List<String> messages(long userId) {
		return this.jdbc.sql("SELECT message_text FROM notifications WHERE user_id = :u ORDER BY notification_id")
			.param("u", userId)
			.query(String.class)
			.list();
	}

	/** DB 시계(초 단위)에서 옮긴 시각. */
	LocalDateTime dbNowPlusMinutes(int minutes) {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP + INTERVAL " + minutes + " MINUTE")
			.query(LocalDateTime.class)
			.single()
			.truncatedTo(ChronoUnit.SECONDS);
	}

	void cancelEnrollment(long courseId, long userId) {
		this.jdbc.sql("UPDATE course_enrollments SET enrollment_status = 'CANCELLED' WHERE course_id = :c AND user_id = :u")
			.param("c", courseId)
			.param("u", userId)
			.update();
	}

}
