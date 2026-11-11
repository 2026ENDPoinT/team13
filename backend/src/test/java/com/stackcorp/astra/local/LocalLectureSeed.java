package com.stackcorp.astra.local;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 온라인 강의. 영상 바이트 없이 넣으므로 재생할 수 있는 콘텐츠는 없다.
 * <ul>
 * <li>데이터베이스(bi) 1주차 강의(지난 기간, 콘텐츠 둘): 수강생이 볼 때는 재생됐고 그 뒤 원본이 내려가 콘텐츠가 removed(재생 경로 없음)다.
 * 학습 기록은 LearningSessionService가 남기는 것과 같다: 끝난 학습 session, 60초 heartbeat와 인정 시간 ledger, 콘텐츠 진도, 활동
 * 진도(모두 인정되면 완료), 이어보기 위치.</li>
 * <li>데이터베이스 10주차와 기계학습(ai) 6주차 강의(지금 기간): 콘텐츠가 변환 중(processing)이다. 진도는 없다.</li>
 * </ul>
 * 재생할 수 있는 강의는 교수 화면에서 영상을 올려 만든다(README). 과목에 강의가 이미 있으면 넣지 않는다.
 */
final class LocalLectureSeed {

	/** 콘텐츠마다 인정 기준 시간(초). heartbeat 간격은 60초다. */
	private static final int REQUIRED_SEC = 300;

	private LocalLectureSeed() {
	}

	static void seed(JdbcClient jdbc, long student, long professor) {
		long bi = LocalSql.course(jdbc, "bi");
		if (LocalSql.exists(jdbc, "SELECT 1 FROM learning_activities WHERE course_id = :c AND activity_type = 'lecture_weeks'",
				LocalSql.params("c", bi))) {
			return;
		}
		long past = LocalSql.activity(jdbc, bi, 1, "lecture_weeks", "1주차 온라인 강의: 데이터베이스 개요", "데이터베이스와 DBMS의 역할을 소개한다.",
				"-40 DAY", "-33 DAY", false, professor);
		long overview = item(jdbc, past, 1, "데이터베이스란", "removed");
		long dbms = item(jdbc, past, 2, "DBMS의 구성", "removed");
		List<Long> students = new ArrayList<>();
		students.add(student);
		LocalPeopleSeed.refs().forEach((ref) -> students.add(LocalSql.user(jdbc, ref)));
		for (int i = 0; i < students.size(); i++) {
			long user = students.get(i);
			// 박민준과 앞의 다섯 명은 둘 다 인정 시간을 채웠고, 나머지는 첫 콘텐츠를 일부만 봤다.
			int day = 38 - i % 3;
			if (i < 6) {
				watch(jdbc, bi, past, overview, user, day, 5);
				watch(jdbc, bi, past, dbms, user, day - 1, 5);
			}
			else {
				watch(jdbc, bi, past, overview, user, day, 2);
			}
		}

		long current = LocalSql.activity(jdbc, bi, 10, "lecture_weeks", "10주차 온라인 강의: 트랜잭션", "트랜잭션과 ACID 성질을 다룬다.",
				"-1 DAY", "+6 DAY", false, professor);
		item(jdbc, current, 1, "트랜잭션과 ACID", "processing");
		long ai = LocalSql.course(jdbc, "ai");
		long ml = LocalSql.activity(jdbc, ai, 6, "lecture_weeks", "6주차 온라인 강의: 선형 회귀", "손실 함수와 경사하강법을 다룬다.", "-2 DAY",
				"+5 DAY", false, professor);
		item(jdbc, ml, 1, "경사하강법", "processing");
	}

	/** 재생 경로가 없는 영상 콘텐츠. 길이는 영상이 준비돼야 알 수 있어 비워 둔다. */
	private static long item(JdbcClient jdbc, long activity, int seq, String title, String state) {
		return LocalSql.insert(jdbc, """
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, required_sec, video_state)
				VALUES (:a, :seq, :title, 'video', :required, :state)
				""", LocalSql.params("a", activity, "seq", seq, "title", title, "required", REQUIRED_SEC, "state", state));
	}

	/**
	 * 학습 session 하나: minutes번의 heartbeat마다 60초를 인정하고 버튼으로 끝냈다.
	 * @param day 며칠 전에 봤는지
	 */
	private static void watch(JdbcClient jdbc, long course, long activity, long item, long user, int day, int minutes) {
		String start = "(CURRENT_TIMESTAMP(3) - INTERVAL %d DAY)".formatted(day);
		String end = "(%s + INTERVAL %d SECOND)".formatted(start, 60 * minutes);
		long session = LocalSql.insert(jdbc, """
				INSERT INTO learning_sessions (user_id, course_id, activity_id, online_lecture_item_id, state, started_at,
				                               last_heartbeat_at, ended_at, end_reason, heartbeat_interval_sec, lease_sec,
				                               created_at)
				VALUES (:u, :c, :a, :i, 'ended', %1$s, %2$s, %2$s, 'button', 60, 180, %1$s)
				""".formatted(start, end), LocalSql.params("u", user, "c", course, "a", activity, "i", item));
		for (int seq = 1; seq <= minutes; seq++) {
			String from = "(%s + INTERVAL %d SECOND)".formatted(start, 60 * (seq - 1));
			String to = "(%s + INTERVAL %d SECOND)".formatted(start, 60 * seq);
			jdbc.sql("""
					INSERT INTO learning_heartbeats (learning_session_id, seq_no, received_at, position_sec, playing_yn, credited_sec)
					VALUES (:s, :seq, %s, :pos, 1, 60)
					""".formatted(to)).param("s", session).param("seq", seq).param("pos", 60 * seq).update();
			jdbc.sql("""
					INSERT INTO learning_credit_ledger (learning_session_id, from_at, to_at, credited_sec, in_period_yn, rule_version)
					VALUES (:s, %s, %s, 60, 1, 'v1')
					""".formatted(from, to)).param("s", session).update();
		}
		int credited = 60 * minutes;
		boolean met = credited >= REQUIRED_SEC;
		jdbc.sql("""
				INSERT INTO online_lecture_item_progress (user_id, online_lecture_item_id, credited_in_sec, max_position_sec,
				                                          last_position_sec, last_position_at, decided_status, met_at, rule_version)
				VALUES (:u, :i, :sec, :sec, :sec, %1$s, :status, %2$s, 'v1')
				""".formatted(end, met ? end : "NULL"))
			.param("u", user)
			.param("i", item)
			.param("sec", credited)
			.param("status", met ? "met" : "under_required")
			.update();
		jdbc.sql("""
				INSERT INTO activity_progress (user_id, activity_id, first_viewed_at, last_viewed_at, view_count)
				VALUES (:u, :a, %1$s, %1$s, 1) AS n
				ON DUPLICATE KEY UPDATE first_viewed_at = COALESCE(activity_progress.first_viewed_at, n.first_viewed_at),
				                        last_viewed_at = n.last_viewed_at, view_count = activity_progress.view_count + 1
				""".formatted(start)).param("u", user).param("a", activity).update();
		// 활동의 콘텐츠가 모두 인정되면 활동을 완료한다(LearningSessionService.decide).
		jdbc.sql("""
				UPDATE activity_progress SET completed_at = COALESCE(completed_at, %s)
				 WHERE user_id = :u AND activity_id = :a
				   AND NOT EXISTS (SELECT 1 FROM online_lecture_items i
				                    WHERE i.activity_id = :a AND i.deleted_at IS NULL
				                      AND NOT EXISTS (SELECT 1 FROM online_lecture_item_progress p
				                                       WHERE p.online_lecture_item_id = i.online_lecture_item_id
				                                         AND p.user_id = :u AND p.met_at IS NOT NULL))
				""".formatted(end)).param("u", user).param("a", activity).update();
		jdbc.sql("""
				INSERT INTO course_continue_pointers (user_id, course_id, activity_id, menu_code, recorded_at)
				VALUES (:u, :c, :a, 'lecture_weeks', %s) AS n
				ON DUPLICATE KEY UPDATE activity_id = n.activity_id, menu_code = n.menu_code, recorded_at = n.recorded_at
				""".formatted(start)).param("u", user).param("c", course).param("a", activity).update();
	}

}
