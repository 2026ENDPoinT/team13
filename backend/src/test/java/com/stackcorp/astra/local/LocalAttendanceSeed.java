package com.stackcorp.astra.local;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 로컬 실행용 출석부. 데이터베이스 과목(bi, 목요일 수업)에 출석 설정(지각 10분, 지각 3회는 결석 1회)과 지난 여섯 주의 차시를 넣는다.
 * 1주차 차시는 오늘 이전 마지막 목요일에서 다섯 주 앞이다. 4주차는 휴강했고 그 주 토요일에 보강 차시가 있다. 기록은 교수가 한 명씩
 * 입력한 것(AttendanceService.set)과 같다: 출결 행(row_version 1)과 변경 이력 한 줄. 출석, 지각, 결석, 공결이 모두 있다. 과목에 차시가
 * 이미 있으면 넣지 않는다.
 */
final class LocalAttendanceSeed {

	/** 학생 아홉 명(박민준이 처음) × 차시 다섯(1, 2, 3, 5, 6주차) + 보강. 출, 지, 결, 공. */
	private static final String[] MARKS = { "출출출출출출", "출지출출출출", "출출결출출출", "지출출출공출", "출출출지출출", "출출출출출결",
			"공출출출출출", "출출지출출출", "결출출출지출" };

	private LocalAttendanceSeed() {
	}

	static void seed(JdbcClient jdbc, long student, long professor) {
		long bi = LocalSql.course(jdbc, "bi");
		if (LocalSql.exists(jdbc, "SELECT 1 FROM attendance_sessions WHERE course_id = :c", LocalSql.params("c", bi))) {
			return;
		}
		jdbc.sql("""
				INSERT INTO attendance_settings (course_id, late_after_minutes, round_minutes, lates_per_absence, row_version,
				                                 updated_by)
				VALUES (:c, 10, 10, 3, 1, :u)
				""").param("c", bi).param("u", professor).update();
		List<Long> students = new ArrayList<>();
		students.add(student);
		LocalPeopleSeed.refs().forEach((ref) -> students.add(LocalSql.user(jdbc, ref)));

		List<Long> marked = new ArrayList<>();
		for (int week = 1; week <= 6; week++) {
			// 오늘 이전 마지막 목요일에서 (6 - week)주 앞.
			String date = "CURRENT_DATE - INTERVAL ((DAYOFWEEK(CURRENT_DATE) + 1) %% 7 + 1 + %d) DAY".formatted(7 * (6 - week));
			long session = session(jdbc, bi, week, date, null, professor);
			if (week == 4) {
				jdbc.sql("UPDATE attendance_sessions SET session_state = 'cancelled', row_version = row_version + 1 WHERE attendance_session_id = :s")
					.param("s", session)
					.update();
				marked.add(session(jdbc, bi, week, date + " + INTERVAL 2 DAY", session, professor));
			}
			else {
				marked.add(session);
			}
		}
		// MARKS 열 순서: 1, 2, 3주차, 4주차 보강, 5, 6주차.
		for (int s = 0; s < marked.size(); s++) {
			for (int i = 0; i < students.size(); i++) {
				record(jdbc, marked.get(s), students.get(i), MARKS[i].charAt(s), professor);
			}
		}
	}

	/** 차시 만들기(일반 출석, 목요일 14:00~17:00). 보강이면 makeupOf에 휴강한 차시를 준다. */
	private static long session(JdbcClient jdbc, long course, int week, String date, Long makeupOf, long professor) {
		return LocalSql.insert(jdbc, """
				INSERT INTO attendance_sessions (course_id, course_week_id, session_no, attendance_method, class_date,
				                                 class_start_time, class_end_time, makeup_of_session_id, makeup_yn, created_by,
				                                 created_at)
				VALUES (:c, :w, 1, '일반', %1$s, '14:00', '17:00', :makeupOf, :makeup, :u, TIMESTAMP(%1$s) - INTERVAL 1 DAY)
				""".formatted(date), LocalSql.params("c", course, "w", LocalSql.week(jdbc, course, week), "makeupOf", makeupOf,
				"makeup", makeupOf != null, "u", professor));
	}

	/** 교수가 수업 끝에 입력한 출결과 그 변경 이력. */
	private static void record(JdbcClient jdbc, long session, long user, char mark, long professor) {
		String status = switch (mark) {
			case '출' -> "출석";
			case '지' -> "지각";
			case '결' -> "결석";
			default -> "공결";
		};
		String reason = switch (mark) {
			case '출' -> "출석 확인";
			case '지' -> "15분 늦게 들어옴";
			case '결' -> "연락 없이 결석";
			default -> "병결 서류 제출";
		};
		String decided = "(SELECT TIMESTAMP(class_date, class_end_time) FROM attendance_sessions WHERE attendance_session_id = :s)";
		long record = LocalSql.insert(jdbc, """
				INSERT INTO attendance_records (attendance_session_id, user_id, status, source, decided_at, decided_by, rule_version)
				VALUES (:s, :u, :status, 'manual', %s, :by, 'manual')
				""".formatted(decided), LocalSql.params("s", session, "u", user, "status", status, "by", professor));
		jdbc.sql("""
				INSERT INTO attendance_record_changes (attendance_record_id, previous_status, previous_source, new_status, reason,
				                                       changed_by, changed_at)
				SELECT attendance_record_id, NULL, NULL, status, :reason, decided_by, decided_at
				  FROM attendance_records WHERE attendance_record_id = :r
				""").param("reason", reason).param("r", record).update();
	}

}
