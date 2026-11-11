package com.stackcorp.astra.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stackcorp.astra.catalog.CatalogQueries.OfferingRow;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * 수강 원장(course_enrollments), 상태 이벤트, 청강 사유, 좌석 카운터 쓰기(ADR-0026). 행은 지우지 않고 상태만 바꾼다.
 * 잠금 순서는 비정규 과정 행 → 수강 행 → 좌석 카운터다. 모두 기본 키로 잠근다.
 */
@Repository
class EnrollmentRecords {

	private static final String ENROLLMENT = """
			SELECT enrollment_id, course_id, user_id, enrollment_kind, enrollment_role, enrollment_status, holds_seat,
			       version, applied_at
			  FROM course_enrollments
			""";

	private final JdbcClient jdbc;

	EnrollmentRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * 비정규 과정 행을 잠그고 최신 값을 읽는다. 같은 과정의 신청, 승인, 취소는 이 잠금으로 한 줄로 선다. 폐강 과목도 잠근다. 접수
	 * 여부는 부르는 쪽이 {@link OfferingRules#state}로 판정한다.
	 */
	Optional<OfferingRow> lockOffering(long courseId) {
		return this.jdbc
			.sql(CatalogQueries.OFFERING_COLUMNS + " WHERE c.course_type = '" + CatalogQueries.NON_REGULAR
					+ "' AND o.course_id = :course FOR UPDATE OF o")
			.param("course", courseId)
			.query(CatalogQueries::offering)
			.optional();
	}

	Optional<Enrollment> lock(long enrollmentId) {
		return this.jdbc.sql(ENROLLMENT + " WHERE enrollment_id = :id FOR UPDATE")
			.param("id", enrollmentId)
			.query(EnrollmentRecords::enrollment)
			.optional();
	}

	Optional<Enrollment> find(long enrollmentId) {
		return this.jdbc.sql(ENROLLMENT + " WHERE enrollment_id = :id")
			.param("id", enrollmentId)
			.query(EnrollmentRecords::enrollment)
			.optional();
	}

	/** 사용자가 그 과목에 낸 가장 최근 신청. 활성 행이 있으면 그 행이다(활성 행은 하나뿐이고 가장 최근에 만들어졌다). */
	Optional<Enrollment> latest(long courseId, long userId, String kind) {
		return this.jdbc.sql(ENROLLMENT + """
				 WHERE course_id = :course AND user_id = :user AND enrollment_kind = :kind
				 ORDER BY enrollment_id DESC LIMIT 1
				""")
			.param("course", courseId)
			.param("user", userId)
			.param("kind", kind)
			.query(EnrollmentRecords::enrollment)
			.optional();
	}

	/**
	 * 새 신청. 같은 (과목, 사용자, 구분)의 활성 행이 있으면 uq_course_enrollments_active 위반으로 DuplicateKeyException이다.
	 * 처음 행의 version은 1이다.
	 */
	long insert(long courseId, long userId, String kind, String role, String status, boolean holdsSeat,
			LocalDateTime now) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status,
				                                holds_seat, applied_at, decided_at, source)
				VALUES (:course, :user, :kind, :role, :status, :seat, :now, :decided, 'USER')
				""")
			.param("course", courseId)
			.param("user", userId)
			.param("kind", kind)
			.param("role", role)
			.param("status", status)
			.param("seat", holdsSeat)
			.param("now", now)
			.param("decided", "ENROLLED".equals(status) ? now : null)
			.update(key, "enrollment_id");
		return key.getKey().longValue();
	}

	/**
	 * 잠근 행의 상태를 바꾸고 version을 올린다. 승인·반려는 decided_at, 취소는 cancelled_at에 DB 시각을 남긴다.
	 * @return 바뀐 뒤의 version
	 */
	int transition(Enrollment row, String to, boolean holdsSeat, LocalDateTime now) {
		String stamp = "CANCELLED".equals(to) ? "cancelled_at" : "decided_at";
		int updated = this.jdbc.sql("UPDATE course_enrollments SET enrollment_status = :to, holds_seat = :seat, " + stamp
				+ " = :now, version = version + 1 WHERE enrollment_id = :id AND enrollment_status = :from AND version = :version")
			.param("to", to)
			.param("seat", holdsSeat)
			.param("now", now)
			.param("id", row.enrollmentId())
			.param("from", row.status())
			.param("version", row.version())
			.update();
		if (updated != 1) {
			// 행을 잠근 뒤라 일어나지 않는다. 일어나면 잠금 규칙이 깨진 것이므로 명령 전체를 되돌린다.
			throw new IllegalStateException("locked enrollment changed: " + row.enrollmentId());
		}
		return row.version() + 1;
	}

	/** 청강 사유를 바꾸고 version을 올린다. REQUESTED이고 version이 같을 때만 바뀐다. */
	boolean updateReason(long enrollmentId, int version, String reason) {
		int updated = this.jdbc.sql("""
				UPDATE course_enrollments SET version = version + 1
				 WHERE enrollment_id = :id AND enrollment_status = 'REQUESTED' AND version = :version
				""").param("id", enrollmentId).param("version", version).update();
		if (updated == 0) {
			return false;
		}
		this.jdbc.sql("""
				UPDATE audit_request_details SET request_reason = :reason, reason_updated_at = CURRENT_TIMESTAMP(3)
				 WHERE enrollment_id = :id
				""").param("reason", reason).param("id", enrollmentId).update();
		return true;
	}

	void insertAuditReason(long enrollmentId, String reason) {
		this.jdbc.sql("INSERT INTO audit_request_details (enrollment_id, request_reason) VALUES (:id, :reason)")
			.param("id", enrollmentId)
			.param("reason", reason)
			.update();
	}

	Optional<String> auditReason(long enrollmentId) {
		return this.jdbc.sql("SELECT request_reason FROM audit_request_details WHERE enrollment_id = :id")
			.param("id", enrollmentId)
			.query(String.class)
			.optional();
	}

	/**
	 * 상태 이벤트를 덧붙인다. 순번은 그 이벤트가 만든 수강 행의 version이다. version은 행 잠금 아래에서만 오르므로 순번이 겹치지
	 * 않는다. 사유 수정처럼 이벤트 없이 version만 오르면 순번이 건너뛴다.
	 * @param source USER|PROFESSOR|STAFF
	 */
	void event(long enrollmentId, int seq, String type, String from, String to, long actorUserId, String source,
			String reason, LocalDateTime now) {
		this.jdbc.sql("""
				INSERT INTO enrollment_events (enrollment_id, event_seq, event_type, from_status, to_status, actor_user_id,
				                               event_source, reason, occurred_at)
				VALUES (:id, :seq, :type, :from, :to, :actor, :source, :reason, :now)
				""")
			.param("id", enrollmentId)
			.param("seq", seq)
			.param("type", type)
			.param("from", from)
			.param("to", to)
			.param("actor", actorUserId)
			.param("source", source)
			.param("reason", reason)
			.param("now", now)
			.update();
	}

	/**
	 * 좌석 하나를 잡는다. 과정 행을 잠근 뒤에 부른다. 카운터가 없으면 정원 제한 없이 만든다. 없는 행을 잠금 읽기로 찾으면 gap
	 * 잠금끼리 교착하므로 먼저 upsert해 행을 만든다. 정원이 찼으면 false다. 조건부 갱신이 마지막 방어선이고, 그 밑에
	 * held_count &lt;= seat_capacity CHECK 제약이 있다.
	 */
	boolean takeSeat(long courseId) {
		this.jdbc.sql("""
				INSERT INTO course_seat_counters (course_id, seat_capacity, held_count) VALUES (:course, NULL, 0)
				ON DUPLICATE KEY UPDATE course_id = course_id
				""").param("course", courseId).update();
		return this.jdbc.sql("""
				UPDATE course_seat_counters SET held_count = held_count + 1
				 WHERE course_id = :course AND (seat_capacity IS NULL OR held_count < seat_capacity)
				""").param("course", courseId).update() == 1;
	}

	/** 좌석을 돌려준다. 좌석을 점유한 행의 상태를 바꾸는 같은 transaction에서만 부른다. */
	void returnSeat(long courseId) {
		int updated = this.jdbc.sql("""
				UPDATE course_seat_counters SET held_count = held_count - 1 WHERE course_id = :course AND held_count > 0
				""").param("course", courseId).update();
		if (updated != 1) {
			throw new IllegalStateException("seat counter out of balance: " + courseId);
		}
	}

	/** 사용자가 그 과목의 교수 또는 비정규 담당자로 등록돼 있으면 그 역할. 조교는 신청을 처리하지 않는다. */
	Optional<String> deciderRole(long userId, long courseId) {
		return this.jdbc.sql("""
				SELECT instructor_role FROM course_instructors
				 WHERE course_id = :course AND user_id = :user AND instructor_role IN ('PROFESSOR', 'MANAGER')
				 ORDER BY instructor_role = 'PROFESSOR' DESC LIMIT 1
				""").param("course", courseId).param("user", userId).query(String.class).optional();
	}

	/** 처리를 기다리는 신청(비정규 PENDING, 청강 REQUESTED). 먼저 낸 신청이 먼저다. */
	List<Request> pending(long courseId, int offset, int size) {
		return this.jdbc.sql("""
				SELECT e.enrollment_id, e.user_id, e.enrollment_kind, e.enrollment_status, e.version, e.applied_at,
				       d.request_reason
				  FROM course_enrollments e LEFT JOIN audit_request_details d ON d.enrollment_id = e.enrollment_id
				 WHERE e.course_id = :course AND e.enrollment_status IN ('PENDING', 'REQUESTED')
				 ORDER BY e.applied_at, e.enrollment_id
				 LIMIT :size OFFSET :offset
				""")
			.param("course", courseId)
			.param("size", size)
			.param("offset", offset)
			.query((rs, n) -> new Request(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4), rs.getInt(5),
					rs.getObject(6, LocalDateTime.class), rs.getString(7)))
			.list();
	}

	long pendingCount(long courseId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM course_enrollments
				 WHERE course_id = :course AND enrollment_status IN ('PENDING', 'REQUESTED')
				""").param("course", courseId).query(Long.class).single();
	}

	/**
	 * 사용자의 확정된 수강 행. 과목마다 학사 원장, 비정규, 청강 순으로 하나를 고른다.
	 */
	Map<Long, Enrollment> enrolledOf(long userId, Collection<Long> courseIds) {
		if (courseIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Enrollment> result = new HashMap<>();
		this.jdbc.sql(ENROLLMENT + """
				 WHERE user_id = :user AND course_id IN (:courses) AND enrollment_status = 'ENROLLED'
				   AND enrollment_role IN ('STUDENT', 'AUDITOR')
				 ORDER BY FIELD(enrollment_kind, 'REGISTRAR', 'NONREGULAR', 'AUDIT'), enrollment_id
				""")
			.param("user", userId)
			.param("courses", courseIds)
			.query(EnrollmentRecords::enrollment)
			.list()
			.forEach((e) -> result.putIfAbsent(e.courseId(), e));
		return result;
	}

	private static Enrollment enrollment(ResultSet rs, int n) throws SQLException {
		return new Enrollment(rs.getLong("enrollment_id"), rs.getLong("course_id"), rs.getLong("user_id"),
				rs.getString("enrollment_kind"), rs.getString("enrollment_role"), rs.getString("enrollment_status"),
				rs.getBoolean("holds_seat"), rs.getInt("version"), rs.getObject("applied_at", LocalDateTime.class));
	}

	/**
	 * @param kind REGISTRAR|AUDIT|NONREGULAR
	 */
	record Enrollment(long enrollmentId, long courseId, long userId, String kind, String role, String status,
			boolean holdsSeat, int version, LocalDateTime appliedAt) {

		boolean active() {
			return switch (this.status) {
				case "REQUESTED", "PENDING", "WAITLISTED", "ENROLLED" -> true;
				default -> false;
			};
		}

	}

	/**
	 * @param reason 청강 사유. 비정규 신청이면 null
	 */
	record Request(long enrollmentId, long userId, String kind, String status, int version, LocalDateTime appliedAt,
			String reason) {
	}

}
