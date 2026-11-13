package com.stackcorp.astra.learning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.learning.AttendanceCodes.Status;
import com.stackcorp.astra.learning.AttendanceService.Cell;
import com.stackcorp.astra.learning.api.AttendanceSummaries;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 출석부 조회. 아무것도 바꾸지 않는다(SC-04). 휴강한 차시는 요약에서 세지 않는다.
 */
@Component
class AttendanceQueries implements AttendanceSummaries {

	private final JdbcClient jdbc;

	AttendanceQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 과목의 차시. 주차, 수업일, 차시 번호, 보강 순서다. */
	List<SessionRow> sessions(long courseId, Integer weekNo) {
		return this.jdbc.sql("""
				SELECT s.attendance_session_id, w.week_no, s.session_no, s.class_date, s.class_start_time, s.class_end_time,
				       s.attendance_method, s.session_state, s.makeup_yn, s.makeup_of_session_id, s.row_version
				  FROM attendance_sessions s JOIN course_weeks w ON w.course_week_id = s.course_week_id
				 WHERE s.course_id = :course AND (:week IS NULL OR w.week_no = :week)
				 ORDER BY w.week_no, s.class_date, s.makeup_yn, s.session_no, s.attendance_session_id
				""").param("course", courseId).param("week", weekNo).query((rs, n) -> new SessionRow(rs.getLong(1),
				rs.getInt(2), rs.getInt(3), rs.getObject(4, LocalDate.class), rs.getObject(5, LocalTime.class),
				rs.getObject(6, LocalTime.class), rs.getString(7), rs.getString(8), rs.getBoolean(9),
				rs.getObject(10, Long.class), rs.getInt(11))).list();
	}

	/** 한 사용자의 그 과목 기록. 차시 ID별. */
	Map<Long, Cell> recordsOf(long courseId, long userId) {
		Map<Long, Cell> cells = new HashMap<>();
		this.jdbc.sql("""
				SELECT r.attendance_session_id, r.attendance_record_id, r.status, r.source, r.decided_at, r.row_version
				  FROM attendance_records r JOIN attendance_sessions s ON s.attendance_session_id = r.attendance_session_id
				 WHERE s.course_id = :course AND r.user_id = :user
				""").param("course", courseId).param("user", userId).query((rs) -> {
			cells.put(rs.getLong(1), new Cell(rs.getLong(2), rs.getString(3), rs.getString(4),
					rs.getObject(5, LocalDateTime.class), rs.getInt(6)));
		});
		return cells;
	}

	/** 여러 학생의 그 과목 기록. 사용자 ID → 차시 ID → 기록. */
	Map<Long, Map<Long, Cell>> recordsOf(long courseId, Collection<Long> userIds) {
		Map<Long, Map<Long, Cell>> cells = new HashMap<>();
		if (userIds.isEmpty()) {
			return cells;
		}
		this.jdbc.sql("""
				SELECT r.user_id, r.attendance_session_id, r.attendance_record_id, r.status, r.source, r.decided_at,
				       r.row_version
				  FROM attendance_records r JOIN attendance_sessions s ON s.attendance_session_id = r.attendance_session_id
				 WHERE s.course_id = :course AND r.user_id IN (:users)
				""").param("course", courseId).param("users", userIds).query((rs) -> {
			cells.computeIfAbsent(rs.getLong(1), (u) -> new HashMap<>())
				.put(rs.getLong(2), new Cell(rs.getLong(3), rs.getString(4), rs.getString(5),
						rs.getObject(6, LocalDateTime.class), rs.getInt(7)));
		});
		return cells;
	}

	/**
	 * 차시마다 상태별 기록 수. 담당자 화면용이다. 수강을 취소한 학생의 기록도 센다.
	 * @return 차시 ID → 상태 코드 → 수
	 */
	Map<Long, Map<String, Integer>> countsBySession(long courseId) {
		Map<Long, Map<String, Integer>> counts = new HashMap<>();
		this.jdbc.sql("""
				SELECT r.attendance_session_id, r.status, COUNT(*)
				  FROM attendance_records r JOIN attendance_sessions s ON s.attendance_session_id = r.attendance_session_id
				 WHERE s.course_id = :course
				 GROUP BY r.attendance_session_id, r.status
				""").param("course", courseId).query((rs) -> {
			counts.computeIfAbsent(rs.getLong(1), (s) -> new LinkedHashMap<>())
				.put(Status.ofDb(rs.getString(2)).code, rs.getInt(3));
		});
		return counts;
	}

	@Override
	public Map<Long, AttendanceSummary> of(long userId, Collection<Long> courseIds) {
		Map<Long, AttendanceSummary> summaries = new LinkedHashMap<>();
		if (courseIds.isEmpty()) {
			return summaries;
		}
		this.jdbc.sql("""
				SELECT s.course_id, COUNT(*),
				       COALESCE(SUM(r.status = '출석'), 0), COALESCE(SUM(r.status = '지각'), 0),
				       COALESCE(SUM(r.status = '결석'), 0), COALESCE(SUM(r.status = '공결'), 0),
				       COALESCE(MAX(st.lates_per_absence), :defaultLates)
				  FROM attendance_sessions s
				  LEFT JOIN attendance_records r ON r.attendance_session_id = s.attendance_session_id AND r.user_id = :user
				  LEFT JOIN attendance_settings st ON st.course_id = s.course_id
				 WHERE s.course_id IN (:courses) AND s.session_state <> 'cancelled'
				 GROUP BY s.course_id
				""")
			.param("defaultLates", AttendancePolicy.DEFAULT_LATES_PER_ABSENCE)
			.param("user", userId)
			.param("courses", courseIds)
			.query((rs) -> {
				long courseId = rs.getLong(1);
				summaries.put(courseId, summary(courseId, rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
						rs.getInt(6), rs.getInt(7)));
			});
		for (long courseId : courseIds) {
			summaries.putIfAbsent(courseId, summary(courseId, 0, 0, 0, 0, 0, 0));
		}
		return summaries;
	}

	static AttendanceSummary summary(long courseId, int sessions, int present, int late, int absent, int excused,
			int latesPerAbsence) {
		int absenceEquivalent = absent + (latesPerAbsence > 0 ? late / latesPerAbsence : 0);
		return new AttendanceSummary(courseId, sessions, present, late, absent, excused,
				sessions - present - late - absent - excused, absenceEquivalent);
	}

	/**
	 * @param method DB 값(스마트|온라인|일반)
	 * @param state planned|open|closed|cancelled
	 */
	record SessionRow(long sessionId, int weekNo, int sessionNo, LocalDate classDate, LocalTime startTime,
			LocalTime endTime, String method, String state, boolean makeup, Long makeupOfSessionId, int version) {

		boolean cancelled() {
			return "cancelled".equals(this.state);
		}

	}

}
