package com.stackcorp.astra.learning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolActivity;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolWeek;

/**
 * 학교 강의실에서 가져온 주차·학습활동 목록과 학생 본인의 완료 표시(ADR-0042). 과목 하나를 읽을 때마다 그 과목만 한 transaction으로
 * 맞춘다. 강의실 주차 목록({@code GET /api/courses/{courseId}/weeks})에 붙여 내보낸다.
 */
@Component
class SchoolActivities {

	/** 학교 메뉴 ID → 화면 유형(프론트엔드 TYPE_LABEL·TYPE_COLOR의 키). 없으면 post. */
	private static final Map<String, String> VIEW_TYPES = Map.of("lecture_weeks", "video", "lecture_material",
			"material", "report", "report", "test", "test", "survey", "survey", "zoom", "zoom");

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	SchoolActivities(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/**
	 * 과목 하나의 학교 강의실 화면을 맞춘다. 행은 (과목, 학교 메뉴 ID, 학교 활동 번호)로 찾아 고치거나 넣고, 이 학생의 완료 표시를 둔다.
	 * 화면에 활동이 하나라도 있을 때만 화면에서 사라진 주차와 활동(그 활동의 모든 학생 상태 포함)을 지운다. 빈 화면은 학교 쪽 일시 장애일 수
	 * 있어 지우지 않는다.
	 */
	void save(long courseId, long userId, SchoolCourseRoom room) {
		this.transaction.executeWithoutResult((status) -> {
			for (SchoolWeek week : room.weeks()) {
				this.jdbc.sql("""
						INSERT INTO school_course_weeks (course_id, week_no, starts_on, ends_on, synced_at)
						VALUES (:course, :week, :starts, :ends, CURRENT_TIMESTAMP(3)) AS new
						ON DUPLICATE KEY UPDATE starts_on = new.starts_on, ends_on = new.ends_on, synced_at = new.synced_at
						""")
					.param("course", courseId)
					.param("week", week.weekNo())
					.param("starts", week.startsOn())
					.param("ends", week.endsOn())
					.update();
			}
			int order = 0;
			for (SchoolWeek week : room.weeks()) {
				for (SchoolActivity activity : week.activities()) {
					this.jdbc.sql("""
							INSERT INTO school_activities (course_id, school_type, school_kind, school_item_id, week_no,
							                               sort_order, title, open_at, close_at, expired_yn, attendance_label,
							                               learning_time_sec, offline_yn, school_url, synced_at)
							VALUES (:course, :type, :kind, :item, :week, :order, :title, :open, :close, :expired, :attendance,
							        :time, :offline, :url, CURRENT_TIMESTAMP(3)) AS new
							ON DUPLICATE KEY UPDATE school_kind = new.school_kind, week_no = new.week_no,
							                        sort_order = new.sort_order, title = new.title, open_at = new.open_at,
							                        close_at = new.close_at, expired_yn = new.expired_yn,
							                        attendance_label = new.attendance_label,
							                        learning_time_sec = new.learning_time_sec, offline_yn = new.offline_yn,
							                        school_url = new.school_url, synced_at = new.synced_at
							""")
						.param("course", courseId)
						.param("type", activity.type())
						.param("kind", activity.kind())
						.param("item", activity.itemId())
						.param("week", week.weekNo())
						.param("order", order++)
						.param("title", activity.title())
						.param("open", activity.openAt())
						.param("close", activity.closeAt())
						.param("expired", activity.expired())
						.param("attendance", activity.attendance())
						.param("time", activity.learningTimeSec())
						.param("offline", activity.offline())
						.param("url", activity.url())
						.update();
				}
			}
			if (order == 0) {
				return;
			}
			Map<String, Long> ids = new HashMap<>();
			this.jdbc.sql("SELECT school_activity_id, school_type, school_item_id FROM school_activities WHERE course_id = :course")
				.param("course", courseId)
				.query((rs) -> {
					ids.put(rs.getString(2) + "/" + rs.getString(3), rs.getLong(1));
				});
			List<Long> seen = new ArrayList<>();
			for (SchoolWeek week : room.weeks()) {
				for (SchoolActivity activity : week.activities()) {
					long id = ids.get(activity.type() + "/" + activity.itemId());
					seen.add(id);
					this.jdbc.sql("""
							INSERT INTO school_activity_states (school_activity_id, user_id, done_yn, synced_at)
							VALUES (:activity, :user, :done, CURRENT_TIMESTAMP(3)) AS new
							ON DUPLICATE KEY UPDATE done_yn = new.done_yn, synced_at = new.synced_at
							""").param("activity", id).param("user", userId).param("done", activity.done()).update();
				}
			}
			this.jdbc.sql("DELETE FROM school_activities WHERE course_id = :course AND school_activity_id NOT IN (:seen)")
				.param("course", courseId)
				.param("seen", seen)
				.update();
			this.jdbc.sql("DELETE FROM school_course_weeks WHERE course_id = :course AND week_no NOT IN (:weeks)")
				.param("course", courseId)
				.param("weeks", room.weeks().stream().map(SchoolWeek::weekNo).toList())
				.update();
		});
	}

	/**
	 * 같은 학생이 {@code intervalSeconds} 안에 가져오기를 시작했으면 false. 아니면 지금을 시작 시각으로 적고 true. 같은 학생의 동시
	 * 로그인 둘 가운데 하나만 true다.
	 */
	boolean claim(long userId, long intervalSeconds) {
		int updated = this.jdbc.sql("""
				UPDATE school_activity_imports
				   SET started_at = CURRENT_TIMESTAMP(3), finished_at = NULL, courses_read = NULL, courses_failed = NULL
				 WHERE user_id = :user AND started_at <= CURRENT_TIMESTAMP(3) - INTERVAL :seconds SECOND
				""").param("user", userId).param("seconds", intervalSeconds).update();
		if (updated > 0) {
			return true;
		}
		return this.jdbc.sql("""
				INSERT IGNORE INTO school_activity_imports (user_id, started_at) VALUES (:user, CURRENT_TIMESTAMP(3))
				""").param("user", userId).update() > 0;
	}

	void finish(long userId, int read, int failed) {
		this.jdbc.sql("""
				UPDATE school_activity_imports
				   SET finished_at = CURRENT_TIMESTAMP(3), courses_read = :read, courses_failed = :failed
				 WHERE user_id = :user
				""").param("user", userId).param("read", read).param("failed", failed).update();
	}

	/**
	 * 강의실 주차 목록에 붙일 학교 주차와 활동. 주차 번호 순, 주 안에서는 학교 화면 순서다.
	 * @param userId 완료 표시를 볼 사용자. 그 사용자의 가져오기가 본 적 없는 행은 done이 null이다
	 */
	Map<Integer, Week> weeks(long courseId, long userId, LocalDateTime now) {
		Map<Integer, Week> weeks = new LinkedHashMap<>();
		this.jdbc.sql("SELECT week_no, starts_on, ends_on FROM school_course_weeks WHERE course_id = :course ORDER BY week_no")
			.param("course", courseId)
			.query((rs) -> {
				weeks.put(rs.getInt(1), new Week(rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class),
						new ArrayList<>()));
			});
		this.jdbc.sql("""
				SELECT a.school_activity_id, a.school_type, a.school_kind, a.week_no, a.title, a.open_at, a.close_at,
				       a.expired_yn, a.attendance_label, a.learning_time_sec, a.offline_yn, a.school_url, s.done_yn
				  FROM school_activities a
				  LEFT JOIN school_activity_states s ON s.school_activity_id = a.school_activity_id AND s.user_id = :user
				 WHERE a.course_id = :course
				 ORDER BY a.week_no, a.sort_order, a.school_activity_id
				""").param("course", courseId).param("user", userId).query((rs) -> {
			LocalDateTime openAt = rs.getObject("open_at", LocalDateTime.class);
			LocalDateTime closeAt = rs.getObject("close_at", LocalDateTime.class);
			boolean expired = rs.getBoolean("expired_yn");
			Object done = rs.getObject("done_yn");
			Integer time = rs.getObject("learning_time_sec", Integer.class);
			String type = rs.getString("school_type");
			weeks.computeIfAbsent(rs.getInt("week_no"), (n) -> new Week(null, null, new ArrayList<>()))
				.activities()
				.add(new Item("school-" + rs.getLong("school_activity_id"), Item.SCHOOL,
						VIEW_TYPES.getOrDefault(type, "post"), type, rs.getString("school_kind"), rs.getString("title"),
						openAt, closeAt, true, expired ? "closed" : ActivityPhase.of(openAt, closeAt, null, now),
						done == null ? null : rs.getBoolean("done_yn"), rs.getString("attendance_label"), time,
						rs.getBoolean("offline_yn"), rs.getString("school_url")));
		});
		return weeks;
	}

	record Week(LocalDate startsOn, LocalDate endsOn, List<Item> activities) {
	}

	/**
	 * 강의실 주차 목록의 학교 활동 한 행. 학생 화면 계약은 ADR-0042다.
	 * @param activityId {@code school-<번호>}. 서비스 활동의 숫자 ID와 겹치지 않는다
	 * @param origin 늘 {@code school}. 서비스 활동 행에는 이 필드가 없다
	 * @param published 늘 true
	 * @param type 화면 유형: video, material, report, test, survey, zoom, post
	 * @param schoolType 학교 메뉴 ID
	 * @param schoolKind 세부 유형(report1, test2 등). 없으면 null
	 * @param phase not_open, open, closed(만료 표시 포함). DB 시계다
	 * @param done 요청한 사용자 본인의 학교 완료 표시. 그 사용자의 가져오기가 본 적 없으면 null
	 * @param schoolUrl 학교 e-Class에서 이 활동을 여는 주소
	 */
	record Item(String activityId, String origin, String type, String schoolType, String schoolKind, String title,
			LocalDateTime openAt, LocalDateTime closeAt, boolean published, String phase, Boolean done,
			String attendance, Integer learningTimeSec, boolean offline, String schoolUrl)
			implements LearningController.WeekActivity {

		static final String SCHOOL = "school";

	}

}
