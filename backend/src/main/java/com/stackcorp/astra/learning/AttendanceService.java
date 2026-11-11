package com.stackcorp.astra.learning;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.AttendanceCodes.Method;
import com.stackcorp.astra.learning.AttendanceCodes.Status;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 출석 차시(수업 회차)와 담당자의 출석 상태 지정(EP-0011, EP-0012의 대체 계약 중 쓰기). 권한은 부르는 쪽이 먼저 판정한다.
 * <ul>
 * <li>차시 수정·삭제는 차시 행을 잠근 뒤 If-Match version과 비교한다. 출석 기록, 스마트출석 회차, 연결된 학습활동, 보강 차시가 있는
 * 차시는 지우지 않는다(409).</li>
 * <li>담당자가 정한 상태는 가장 강한 판정이다. 자동 판정(온라인 학습 ADR-0014, 스마트출석 ADR-0030)은 기록이 없거나 미처리일 때만
 * 쓰고, 담당자가 정한 상태를 어떤 자동 판정도 바꾸지 않는다. 담당자의 변경은 사유와 함께 attendance_record_changes에 남는다.</li>
 * <li>한 명 지정은 기록의 version(기록이 없으면 0)으로 lost update를 막는다. 일괄 지정은 판정 전인 학생만 채우고 이미 판정된 기록은
 * 바꾸지 않는다.</li>
 * </ul>
 */
@Service
class AttendanceService {

	static final String MANUAL_RULE = "manual";

	private final JdbcClient jdbc;

	private final CourseWeeks weeks;

	private final CourseRosters rosters;

	private final LearningClock clock;

	private final AttendanceTransactions transactions;

	private final AuditLog audit;

	AttendanceService(JdbcClient jdbc, CourseWeeks weeks, CourseRosters rosters, LearningClock clock,
			AttendanceTransactions transactions, AuditLog audit) {
		this.jdbc = jdbc;
		this.weeks = weeks;
		this.rosters = rosters;
		this.clock = clock;
		this.transactions = transactions;
		this.audit = audit;
	}

	/**
	 * 부르는 쪽 transaction(멱등 원장) 안에서 차시를 만든다.
	 * @return 차시 ID
	 */
	long create(long userId, long courseId, SessionInput input, Long makeupOfSessionId) {
		if (makeupOfSessionId != null) {
			String state = this.jdbc.sql("""
					SELECT session_state FROM attendance_sessions
					 WHERE attendance_session_id = :id AND course_id = :course AND makeup_yn = 0
					   FOR SHARE
					""")
				.param("id", makeupOfSessionId)
				.param("course", courseId)
				.query(String.class)
				.optional()
				.orElseThrow(() -> ApiException.invalid("makeupOfSessionId가 이 과목의 차시가 아닙니다."));
			if (!"cancelled".equals(state)) {
				throw new ApiException(HttpStatus.CONFLICT, "session_not_cancelled", "휴강한 차시만 보강할 수 있습니다.");
			}
		}
		long week = this.weeks.weekId(courseId, input.weekNo());
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		try {
			this.jdbc.sql("""
					INSERT INTO attendance_sessions (course_id, course_week_id, session_no, attendance_method, class_date,
					                                 class_start_time, class_end_time, makeup_of_session_id, makeup_yn, created_by)
					VALUES (:course, :week, :no, :method, :date, :start, :end, :makeupOf, :makeup, :user)
					""")
				.param("course", courseId)
				.param("week", week)
				.param("no", input.sessionNo())
				.param("method", input.method().db)
				.param("date", input.classDate())
				.param("start", input.startTime())
				.param("end", input.endTime())
				.param("makeupOf", makeupOfSessionId)
				.param("makeup", makeupOfSessionId != null)
				.param("user", userId)
				.update(key, "attendance_session_id");
		}
		catch (DuplicateKeyException ex) {
			throw sessionExists();
		}
		return key.getKey().longValue();
	}

	/** 차시를 고친다. 진행 중인 스마트출석 회차가 있으면 휴강으로 바꾸지 않는다. */
	@Transactional
	void update(Actor actor, long courseId, long sessionId, int expectedVersion, SessionInput input,
			boolean cancelled) {
		lock(courseId, sessionId, expectedVersion);
		if (cancelled && openRound(sessionId)) {
			throw new ApiException(HttpStatus.CONFLICT, "round_open", "진행 중인 스마트출석이 있는 차시는 휴강으로 바꿀 수 없습니다.");
		}
		long week = this.weeks.weekId(courseId, input.weekNo());
		try {
			this.jdbc.sql("""
					UPDATE attendance_sessions
					   SET course_week_id = :week, session_no = :no, attendance_method = :method, class_date = :date,
					       class_start_time = :start, class_end_time = :end,
					       session_state = IF(:cancelled, 'cancelled', IF(session_state = 'cancelled', 'planned', session_state)),
					       row_version = row_version + 1
					 WHERE attendance_session_id = :id
					""")
				.param("week", week)
				.param("no", input.sessionNo())
				.param("method", input.method().db)
				.param("date", input.classDate())
				.param("start", input.startTime())
				.param("end", input.endTime())
				.param("cancelled", cancelled)
				.param("id", sessionId)
				.update();
		}
		catch (DuplicateKeyException ex) {
			throw sessionExists();
		}
		this.audit.success(actor.userId(), actor.sessionId(), "attendance_session.update", "attendance_session",
				sessionId, courseId);
	}

	/**
	 * 차시를 지운다. 출석 기록, 스마트출석 회차, 보강 차시, 지우지 않은 학습활동이 가리키면 409다. 지운 학습활동(ADR-0011)의 연결은
	 * 끊는다. 학습 기록은 활동에 남고 출석부에는 반영되지 않은 연결이다.
	 */
	@Transactional
	void delete(Actor actor, long courseId, long sessionId, int expectedVersion) {
		lock(courseId, sessionId, expectedVersion);
		boolean inUse = this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM attendance_records WHERE attendance_session_id = :id)
				    OR EXISTS (SELECT 1 FROM smart_attendance_rounds WHERE attendance_session_id = :id)
				    OR EXISTS (SELECT 1 FROM attendance_sessions WHERE makeup_of_session_id = :id)
				    OR EXISTS (SELECT 1 FROM learning_activities WHERE attendance_session_id = :id AND deleted_at IS NULL)
				""").param("id", sessionId).query(Boolean.class).single();
		if (inUse) {
			throw new ApiException(HttpStatus.CONFLICT, "session_in_use", "출석 기록이나 연결된 활동이 있는 차시는 지울 수 없습니다.");
		}
		this.jdbc.sql("""
				UPDATE learning_activities SET attendance_session_id = NULL
				 WHERE attendance_session_id = :id AND deleted_at IS NOT NULL
				""").param("id", sessionId).update();
		this.jdbc.sql("DELETE FROM attendance_sessions WHERE attendance_session_id = :id").param("id", sessionId).update();
		this.audit.success(actor.userId(), actor.sessionId(), "attendance_session.delete", "attendance_session",
				sessionId, courseId);
	}

	/**
	 * 한 학생의 상태를 정한다. 기록의 version이 다르면(기록이 없으면 0) 아무것도 바꾸지 않고 409다. 그 사이 스마트출석이나 온라인
	 * 학습이 기록을 만들었어도 같다. 휴강한 차시에는 정하지 않는다.
	 */
	Cell set(Actor actor, long courseId, long sessionId, long studentId, int expectedVersion, Status status,
			String reason) {
		long actorId = actor.userId();
		if (!this.rosters.students(courseId).contains(studentId)) {
			throw ApiException.notFound();
		}
		return this.transactions.execute(() -> {
			lockForRecords(courseId, sessionId);
			LocalDateTime now = this.clock.now();
			Optional<Cell> current = this.jdbc.sql("""
					SELECT attendance_record_id, status, source, decided_at, row_version FROM attendance_records
					 WHERE attendance_session_id = :session AND user_id = :user
					   FOR UPDATE
					""").param("session", sessionId).param("user", studentId).query(AttendanceService::cell).optional();
			int version = current.map(Cell::version).orElse(0);
			if (version != expectedVersion) {
				throw Versions.conflict();
			}
			long recordId;
			if (current.isEmpty()) {
				try {
					recordId = insertManual(sessionId, studentId, status, actorId, now);
				}
				catch (DuplicateKeyException ex) {
					// 비어 있던 자리에 다른 판정이 먼저 들어왔다. 읽은 version(0)이 낡았다.
					throw Versions.conflict();
				}
			}
			else {
				recordId = current.get().recordId();
				updateManual(recordId, status, actorId, now);
			}
			change(recordId, current.orElse(null), status, reason, actorId, now);
			this.audit.success(actorId, actor.sessionId(), "attendance_record.set", "attendance_record", recordId,
					courseId);
			return new Cell(recordId, status.db, "manual", now, version + 1);
		});
	}

	/**
	 * 판정 전(기록 없음, 미처리)인 수강생 모두를 한 상태로 채운다. 이미 판정된 기록은 출처와 관계없이 그대로 둔다.
	 * @return 바꾼 학생 수
	 */
	int fill(Actor actor, long courseId, long sessionId, Status status, String reason) {
		long actorId = actor.userId();
		List<Long> students = this.rosters.students(courseId);
		return this.transactions.execute(() -> {
			lockForRecords(courseId, sessionId);
			LocalDateTime now = this.clock.now();
			Map<Long, Cell> existing = new HashMap<>();
			this.jdbc.sql("""
					SELECT user_id, attendance_record_id, status, source, decided_at, row_version FROM attendance_records
					  FORCE INDEX (uq_attendance_records_attendance_session_id_user_id)
					 WHERE attendance_session_id = :session
					 ORDER BY user_id
					   FOR UPDATE
					""").param("session", sessionId).query((rs) -> {
				existing.put(rs.getLong("user_id"), cell(rs, 0));
			});
			int changed = 0;
			for (long studentId : students) {
				Cell current = existing.get(studentId);
				if (current != null && !Status.UNMARKED.db.equals(current.status())) {
					continue;
				}
				long recordId;
				if (current == null) {
					try {
						recordId = insertManual(sessionId, studentId, status, actorId, now);
					}
					catch (DuplicateKeyException ex) {
						// 그 사이 다른 판정이 들어온 학생은 판정된 학생이다.
						continue;
					}
				}
				else {
					recordId = current.recordId();
					updateManual(recordId, status, actorId, now);
				}
				change(recordId, current, status, reason, actorId, now);
				changed++;
			}
			this.audit.success(actorId, actor.sessionId(), "attendance_record.fill", "attendance_session", sessionId,
					courseId);
			return changed;
		});
	}

	// 기록을 바꾸는 동안 차시가 지워지거나 다른 과목의 차시로 쓰이지 않게 공유 잠금을 잡는다. 잠금 순서는 차시 → 기록이다.
	private void lockForRecords(long courseId, long sessionId) {
		String state = this.jdbc.sql("""
				SELECT session_state FROM attendance_sessions WHERE attendance_session_id = :id AND course_id = :course
				   FOR SHARE
				""").param("id", sessionId).param("course", courseId).query(String.class).optional().orElseThrow(ApiException::notFound);
		if ("cancelled".equals(state)) {
			throw new ApiException(HttpStatus.CONFLICT, "session_cancelled", "휴강한 차시에는 출석을 정하지 않습니다.");
		}
	}

	private long insertManual(long sessionId, long studentId, Status status, long actorId, LocalDateTime now) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO attendance_records (attendance_session_id, user_id, status, source, decided_at, decided_by, rule_version)
				VALUES (:session, :user, :status, 'manual', :now, :actor, :rule)
				""")
			.param("session", sessionId)
			.param("user", studentId)
			.param("status", status.db)
			.param("now", now)
			.param("actor", actorId)
			.param("rule", MANUAL_RULE)
			.update(key, "attendance_record_id");
		return key.getKey().longValue();
	}

	private void updateManual(long recordId, Status status, long actorId, LocalDateTime now) {
		this.jdbc.sql("""
				UPDATE attendance_records
				   SET status = :status, source = 'manual', smart_attendance_round_id = NULL, decided_at = :now,
				       decided_by = :actor, rule_version = :rule, row_version = row_version + 1
				 WHERE attendance_record_id = :id
				""")
			.param("status", status.db)
			.param("now", now)
			.param("actor", actorId)
			.param("rule", MANUAL_RULE)
			.param("id", recordId)
			.update();
	}

	private void change(long recordId, Cell previous, Status status, String reason, long actorId, LocalDateTime now) {
		this.jdbc.sql("""
				INSERT INTO attendance_record_changes (attendance_record_id, previous_status, previous_source, new_status,
				                                       reason, changed_by, changed_at)
				VALUES (:record, :previousStatus, :previousSource, :status, :reason, :actor, :now)
				""")
			.param("record", recordId)
			.param("previousStatus", previous == null ? null : previous.status())
			.param("previousSource", previous == null ? null : previous.source())
			.param("status", status.db)
			.param("reason", reason)
			.param("actor", actorId)
			.param("now", now)
			.update();
	}

	private void lock(long courseId, long sessionId, int expectedVersion) {
		int version = this.jdbc.sql("""
				SELECT row_version FROM attendance_sessions WHERE attendance_session_id = :id AND course_id = :course
				   FOR UPDATE
				""").param("id", sessionId).param("course", courseId).query(Integer.class).optional().orElseThrow(ApiException::notFound);
		if (version != expectedVersion) {
			throw Versions.conflict();
		}
	}

	private boolean openRound(long sessionId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM smart_attendance_rounds
				                WHERE attendance_session_id = :id AND state = 'open' AND ends_at > :now)
				""").param("id", sessionId).param("now", this.clock.now()).query(Boolean.class).single();
	}

	private static ApiException sessionExists() {
		return new ApiException(HttpStatus.CONFLICT, "session_exists", "같은 주차에 같은 차시가 이미 있습니다.");
	}

	private static Cell cell(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Cell(rs.getLong("attendance_record_id"), rs.getString("status"), rs.getString("source"),
				rs.getObject("decided_at", LocalDateTime.class), rs.getInt("row_version"));
	}

	/**
	 * @param method 출석 방식
	 */
	record SessionInput(int weekNo, int sessionNo, LocalDate classDate, LocalTime startTime, LocalTime endTime,
			Method method) {
	}

	/**
	 * 출석 기록 한 칸. status와 source는 DB 값이다.
	 */
	record Cell(long recordId, String status, String source, LocalDateTime decidedAt, int version) {
	}

}
