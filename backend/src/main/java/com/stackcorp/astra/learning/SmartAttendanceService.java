package com.stackcorp.astra.learning;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.AttendanceCodes.Status;
import com.stackcorp.astra.learning.AttendanceService.Cell;
import com.stackcorp.astra.learning.SmartAttendanceProof.Scope;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

/**
 * 스마트출석 회차(PIN/QR, SEC-12, ADR-0030). 서버가 증명을 직접 검증하고 같은 transaction에서 출석을 기록한다. 브라우저가 검증 결과를
 * 다른 요청으로 넘겨 주는 단계가 없다.
 * <ul>
 * <li>제출은 회차 행을 잠근 뒤 서버 시각을 읽고 판정한다. 같은 회차의 제출, 마감, 취소가 이 잠금으로 차례를 지킨다. 마감·취소·만료 뒤의
 * 제출은 어떤 증명으로도 받지 않는다.</li>
 * <li>회차마다 한 학생의 성공은 한 번이다(used_jti 유일 제약). 다시 보내면 이미 확정된 결과를 돌려준다.</li>
 * <li>틀린 PIN·QR은 회차·학생마다 세고, 한도를 넘으면 그 회차에서는 맞는 값도 받지 않는다. 횟수는 DB에 남으므로 api가 여러 대여도 같다.</li>
 * <li>상태는 서버 시각으로 정한다. 회차를 연 뒤 지각 기준 전이면 출석, 그 뒤 마감 전이면 지각이다.</li>
 * <li>기록은 비어 있거나 미처리일 때만 쓴다. 온라인 학습(ADR-0014)이나 담당자가 이미 정한 상태를 낮추거나 겹쳐 쓰지 않는다.</li>
 * <li>취소한 회차는 없던 회차다. 그 회차가 만든 기록(source smart)을 지운다. 담당자가 그 뒤 고친 기록은 남는다.</li>
 * </ul>
 * 검증에 실패한 제출도 시도와 감사 기록을 commit한 뒤 오류로 알린다.
 */
@Service
class SmartAttendanceService {

	static final String RULE = "smart-v1";

	private static final Logger log = LoggerFactory.getLogger(SmartAttendanceService.class);

	private static final String ROUND = """
			SELECT smart_attendance_round_id, course_id, attendance_session_id, course_group_id, method, pin_length,
			       round_secret, qr_rotation_sec, state, opened_at, late_at, ends_at, closed_at
			  FROM smart_attendance_rounds
			""";

	private final JdbcClient jdbc;

	private final LearningClock clock;

	private final CourseRosters rosters;

	private final AttendanceTransactions transactions;

	private final SmartAttendanceProperties properties;

	private final AuditLog audit;

	SmartAttendanceService(JdbcClient jdbc, LearningClock clock, CourseRosters rosters,
			AttendanceTransactions transactions, SmartAttendanceProperties properties, AuditLog audit) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.rosters = rosters;
		this.transactions = transactions;
		this.properties = properties;
		this.audit = audit;
	}

	/**
	 * 회차를 연다. 차시마다 진행 중 회차는 하나다(open_session_key 유일 제약). 시간이 지나 끝난 회차는 이때 마감 상태로 닫는다.
	 */
	Round open(Actor actor, long courseId, long sessionId, OpenInput input) {
		return this.transactions.execute(() -> {
			String sessionState = this.jdbc.sql("""
					SELECT session_state FROM attendance_sessions WHERE attendance_session_id = :id AND course_id = :course
					   FOR SHARE
					""")
				.param("id", sessionId)
				.param("course", courseId)
				.query(String.class)
				.optional()
				.orElseThrow(ApiException::notFound);
			if ("cancelled".equals(sessionState)) {
				throw new ApiException(HttpStatus.CONFLICT, "session_cancelled", "휴강한 차시에는 출석을 받지 않습니다.");
			}
			if (input.groupId() != null && this.jdbc.sql("""
					SELECT course_group_id FROM course_groups WHERE course_group_id = :group AND course_id = :course FOR SHARE
					""").param("group", input.groupId()).param("course", courseId).query(Long.class).optional().isEmpty()) {
				throw ApiException.invalid("groupId가 이 과목의 그룹이 아닙니다.");
			}
			LocalDateTime now = this.clock.now();
			this.jdbc.sql("""
					UPDATE smart_attendance_rounds SET state = 'closed', closed_at = ends_at, round_secret = NULL
					 WHERE attendance_session_id = :session AND state = 'open' AND ends_at <= :now
					""").param("session", sessionId).param("now", now).update();
			boolean pin = "pin".equals(input.method());
			GeneratedKeyHolder key = new GeneratedKeyHolder();
			try {
				this.jdbc.sql("""
						INSERT INTO smart_attendance_rounds (course_id, attendance_session_id, course_group_id, method, pin_length,
						                                     round_secret, qr_rotation_sec, state, opened_at, late_at, ends_at,
						                                     opened_by)
						VALUES (:course, :session, :group, :method, :pinLength, :secret, :rotation, 'open', :now, :lateAt,
						        :endsAt, :user)
						""")
					.param("course", courseId)
					.param("session", sessionId)
					.param("group", input.groupId())
					.param("method", input.method())
					.param("pinLength", pin ? this.properties.pinLength() : null)
					.param("secret", SmartAttendanceProof.newSecret())
					.param("rotation", pin ? null : (int) this.properties.qrRotation().toSeconds())
					.param("now", now)
					.param("lateAt", now.plusMinutes(input.lateAfterMinutes()))
					.param("endsAt", now.plusMinutes(input.minutes()))
					.param("user", actor.userId())
					.update(key, "smart_attendance_round_id");
			}
			catch (DuplicateKeyException ex) {
				throw new ApiException(HttpStatus.CONFLICT, "round_open", "이 차시에 진행 중인 스마트출석이 있습니다.");
			}
			long roundId = key.getKey().longValue();
			this.audit.success(actor.userId(), actor.sessionId(), "smart_attendance.open", "smart_attendance_round",
					roundId, courseId);
			return find(courseId, roundId).orElseThrow();
		});
	}

	Optional<Round> find(long courseId, long roundId) {
		return this.jdbc.sql(ROUND + " WHERE smart_attendance_round_id = :round AND course_id = :course")
			.param("round", roundId)
			.param("course", courseId)
			.query(SmartAttendanceService::round)
			.optional();
	}

	/** 진행 중이고 마감 전인 과목의 회차. 연 순서다. */
	List<Round> active(long courseId, LocalDateTime now) {
		return this.jdbc.sql(ROUND + """
				 WHERE course_id = :course AND state = 'open' AND ends_at > :now
				 ORDER BY opened_at, smart_attendance_round_id
				""").param("course", courseId).param("now", now).query(SmartAttendanceService::round).list();
	}

	/** 회차를 마감한다. 이미 마감했으면 그대로 둔다. 취소한 회차는 409다. */
	Round close(Actor actor, long courseId, long roundId) {
		return this.transactions.execute(() -> {
			Round round = lock(courseId, roundId);
			if ("cancelled".equals(round.state())) {
				throw new ApiException(HttpStatus.CONFLICT, "round_cancelled", "취소한 스마트출석입니다.");
			}
			if ("open".equals(round.state())) {
				LocalDateTime now = this.clock.now();
				this.jdbc.sql("""
						UPDATE smart_attendance_rounds
						   SET state = 'closed', closed_at = LEAST(:now, ends_at), closed_by = :user, round_secret = NULL
						 WHERE smart_attendance_round_id = :round AND state = 'open'
						""").param("now", now).param("user", actor.userId()).param("round", roundId).update();
				this.audit.success(actor.userId(), actor.sessionId(), "smart_attendance.close", "smart_attendance_round",
						roundId, courseId);
			}
			return find(courseId, roundId).orElseThrow();
		});
	}

	/** 회차를 취소한다. 진행 중이거나 마감한 회차 모두 된다. 이 회차가 만든 smart 기록을 지운다. 이미 취소했으면 그대로 둔다. */
	Round cancel(Actor actor, long courseId, long roundId) {
		return this.transactions.execute(() -> {
			Round round = lock(courseId, roundId);
			if (!"cancelled".equals(round.state())) {
				LocalDateTime now = this.clock.now();
				this.jdbc.sql("""
						UPDATE smart_attendance_rounds
						   SET state = 'cancelled', closed_at = COALESCE(closed_at, LEAST(:now, ends_at)), closed_by = :user,
						       round_secret = NULL
						 WHERE smart_attendance_round_id = :round
						""").param("now", now).param("user", actor.userId()).param("round", roundId).update();
				// 출석부 일괄 지정과 같은 순서(차시의 사용자 ID 순)로 기록을 잠근 뒤 지운다.
				List<Long> records = this.jdbc.sql("""
						SELECT attendance_record_id FROM attendance_records
						  FORCE INDEX (uq_attendance_records_attendance_session_id_user_id)
						 WHERE attendance_session_id = :session AND smart_attendance_round_id = :round AND source = 'smart'
						 ORDER BY user_id
						   FOR UPDATE
						""").param("session", round.sessionId()).param("round", roundId).query(Long.class).list();
				if (!records.isEmpty()) {
					this.jdbc.sql("DELETE FROM attendance_records WHERE attendance_record_id IN (:ids)")
						.param("ids", records)
						.update();
				}
				this.audit.success(actor.userId(), actor.sessionId(), "smart_attendance.cancel", "smart_attendance_round",
						roundId, courseId);
			}
			return find(courseId, roundId).orElseThrow();
		});
	}

	/**
	 * 학생의 제출. 부르는 쪽이 그 과목의 학생인지 판정했다. 결과는 commit한 뒤 돌려주고, 실패 결과의 오류 응답은 부르는 쪽이 만든다.
	 * @param pin PIN 방식이면 값, 아니면 null
	 * @param qrToken QR 방식이면 값, 아니면 null
	 */
	Outcome check(Actor actor, long courseId, long roundId, String pin, String qrToken) {
		long userId = actor.userId();
		List<Long> students = this.rosters.students(courseId);
		return this.transactions.execute(() -> {
			Round round = lock(courseId, roundId);
			LocalDateTime now = this.clock.now();
			Result result = decide(round, userId, students.contains(userId), now, pin, qrToken);
			if (result == Result.OK) {
				write(round, userId, now);
			}
			this.audit.record(userId, actor.sessionId(), "smart_attendance.check", "smart_attendance_round", roundId,
					courseId, result.code);
			return new Outcome(result, round, now);
		});
	}

	// 판정과 시도 기록. 성공은 회차·학생마다 한 번만 소비된다.
	private Result decide(Round round, long userId, boolean student, LocalDateTime now, String pin, String qrToken) {
		Result result;
		if (!student || !target(round, userId)) {
			result = Result.NOT_TARGET;
		}
		else if ("cancelled".equals(round.state())) {
			result = Result.CANCELLED;
		}
		else if (confirmed(round.roundId(), userId)) {
			result = Result.DUPLICATE;
		}
		else if ("closed".equals(round.state())) {
			result = Result.CLOSED;
		}
		else if (!now.isBefore(round.endsAt())) {
			result = Result.EXPIRED;
		}
		else if (failures(round.roundId(), userId) >= this.properties.maxFailures()) {
			result = Result.LOCKED;
		}
		else if (verified(round, now, pin, qrToken)) {
			result = Result.OK;
		}
		else {
			result = "pin".equals(round.method()) ? Result.BAD_PIN : Result.BAD_QR;
		}
		try {
			this.jdbc.sql("""
					INSERT INTO smart_attendance_attempts (smart_attendance_round_id, user_id, attempted_at, result, used_jti)
					VALUES (:round, :user, :now, :result, :jti)
					""")
				.param("round", round.roundId())
				.param("user", userId)
				.param("now", now)
				.param("result", result.code)
				.param("jti", result == Result.OK ? "r" + round.roundId() + "-u" + userId : null)
				.update();
		}
		catch (DuplicateKeyException ex) {
			// 회차 잠금 아래에서는 일어나지 않지만, 일어나도 같은 학생의 성공은 두 번 소비되지 않는다.
			return Result.DUPLICATE;
		}
		if (result == Result.BAD_PIN || result == Result.BAD_QR) {
			long total = this.jdbc.sql("""
					SELECT COUNT(*) FROM smart_attendance_attempts
					 WHERE smart_attendance_round_id = :round AND result IN ('bad_pin', 'bad_qr')
					""").param("round", round.roundId()).query(Long.class).single();
			if (total == this.properties.roundFailureAlert()) {
				log.warn("smart attendance round failures reached roundId={} failures={}", round.roundId(), total);
			}
		}
		return result;
	}

	private boolean verified(Round round, LocalDateTime now, String pin, String qrToken) {
		Scope scope = scope(round);
		if ("pin".equals(round.method())) {
			return pin != null && SmartAttendanceProof.pinMatches(round.secret(), scope, round.pinLength(), pin);
		}
		return qrToken != null && SmartAttendanceProof.qrMatches(round.secret(), scope, qrToken, round.openedAt(), now,
				Duration.ofSeconds(round.qrRotationSec()), this.properties.qrGrace());
	}

	// 비어 있거나 미처리인 기록만 채운다. 이미 판정된 기록(온라인 학습, 담당자)은 그대로 둔다.
	private void write(Round round, long userId, LocalDateTime now) {
		String status = now.isBefore(round.lateAt()) ? Status.PRESENT.db : Status.LATE.db;
		this.jdbc.sql("""
				INSERT INTO attendance_records (attendance_session_id, user_id, status, source, smart_attendance_round_id,
				                                decided_at, rule_version)
				VALUES (:session, :user, :status, 'smart', :round, :now, :rule)
				ON DUPLICATE KEY UPDATE attendance_record_id = attendance_record_id
				""")
			.param("session", round.sessionId())
			.param("user", userId)
			.param("status", status)
			.param("round", round.roundId())
			.param("now", now)
			.param("rule", RULE)
			.update();
		this.jdbc.sql("""
				UPDATE attendance_records
				   SET status = :status, source = 'smart', smart_attendance_round_id = :round, decided_at = :now,
				       rule_version = :rule, row_version = row_version + 1
				 WHERE attendance_session_id = :session AND user_id = :user AND status = '미처리'
				""")
			.param("status", status)
			.param("round", round.roundId())
			.param("now", now)
			.param("rule", RULE)
			.param("session", round.sessionId())
			.param("user", userId)
			.update();
	}

	/**
	 * 학생 한 명이 본 회차 상태. 조회만 한다. 대상이 아니면 not_target, 취소했으면 cancelled, 이 회차에서 출석이 확정됐으면 confirmed,
	 * 담당자가 마감했으면 closed, 시간이 지났으면 expired, 틀린 횟수가 한도에 닿았으면 locked, 그 밖에는 pending이다.
	 */
	String stateOf(Round round, long userId, boolean student, LocalDateTime now) {
		if (!student || !target(round, userId)) {
			return "not_target";
		}
		if ("cancelled".equals(round.state())) {
			return "cancelled";
		}
		if (confirmed(round.roundId(), userId)) {
			return "confirmed";
		}
		if ("closed".equals(round.state())) {
			return round.closedAt() != null && round.closedAt().isBefore(round.endsAt()) ? "closed" : "expired";
		}
		if (!now.isBefore(round.endsAt())) {
			return "expired";
		}
		if (failures(round.roundId(), userId) >= this.properties.maxFailures()) {
			return "locked";
		}
		return "pending";
	}

	/** 담당자 화면의 실시간 수. */
	Counts counts(Round round) {
		long targets;
		List<Long> students = this.rosters.students(round.courseId());
		if (round.groupId() == null) {
			targets = students.size();
		}
		else {
			targets = this.jdbc.sql("SELECT user_id FROM course_group_members WHERE course_group_id = :group")
				.param("group", round.groupId())
				.query(Long.class)
				.list()
				.stream()
				.filter(students::contains)
				.count();
		}
		return this.jdbc.sql("""
				SELECT (SELECT COUNT(DISTINCT user_id) FROM smart_attendance_attempts
				         WHERE smart_attendance_round_id = :round AND result = 'ok'),
				       (SELECT COUNT(*) FROM attendance_records WHERE smart_attendance_round_id = :round AND status = '출석'),
				       (SELECT COUNT(*) FROM attendance_records WHERE smart_attendance_round_id = :round AND status = '지각'),
				       (SELECT COUNT(*) FROM smart_attendance_attempts
				         WHERE smart_attendance_round_id = :round AND result IN ('bad_pin', 'bad_qr')),
				       (SELECT COUNT(*) FROM (SELECT a.user_id FROM smart_attendance_attempts a
				                               WHERE a.smart_attendance_round_id = :round AND a.result IN ('bad_pin', 'bad_qr')
				                                 AND NOT EXISTS (SELECT 1 FROM smart_attendance_attempts o
				                                                  WHERE o.smart_attendance_round_id = a.smart_attendance_round_id
				                                                    AND o.user_id = a.user_id AND o.result = 'ok')
				                               GROUP BY a.user_id HAVING COUNT(*) >= :max) failed_students)
				""")
			.param("round", round.roundId())
			.param("max", this.properties.maxFailures())
			.query((rs, n) -> new Counts(targets, rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
					rs.getLong(5)))
			.single();
	}

	int failuresLeft(long roundId, long userId) {
		return (int) Math.max(0, this.properties.maxFailures() - failures(roundId, userId));
	}

	/** 학생 본인의 그 차시 기록. */
	Optional<Cell> record(long sessionId, long userId) {
		return this.jdbc.sql("""
				SELECT attendance_record_id, status, source, decided_at, row_version FROM attendance_records
				 WHERE attendance_session_id = :session AND user_id = :user
				""")
			.param("session", sessionId)
			.param("user", userId)
			.query((rs, n) -> new Cell(rs.getLong(1), rs.getString(2), rs.getString(3),
					rs.getObject(4, LocalDateTime.class), rs.getInt(5)))
			.optional();
	}

	/** 지금 보여 줄 PIN. 진행 중이고 마감 전인 PIN 회차에만 있다. */
	Optional<String> pin(Round round, LocalDateTime now) {
		if (!"pin".equals(round.method()) || !round.live(now)) {
			return Optional.empty();
		}
		return Optional.of(SmartAttendanceProof.pin(round.secret(), scope(round), round.pinLength()));
	}

	/** 지금 보여 줄 QR token. 진행 중이고 마감 전인 QR 회차에만 있다. */
	Optional<QrToken> qr(Round round, LocalDateTime now) {
		if (!"qr".equals(round.method()) || !round.live(now)) {
			return Optional.empty();
		}
		Duration rotation = Duration.ofSeconds(round.qrRotationSec());
		long slot = SmartAttendanceProof.slot(round.openedAt(), now, rotation);
		LocalDateTime refreshAt = SmartAttendanceProof.slotEnd(round.openedAt(), slot, rotation);
		return Optional.of(new QrToken(SmartAttendanceProof.qrToken(round.secret(), scope(round), slot),
				refreshAt.isAfter(round.endsAt()) ? round.endsAt() : refreshAt));
	}

	private boolean target(Round round, long userId) {
		if (round.groupId() == null) {
			return true;
		}
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM course_group_members WHERE course_group_id = :group AND user_id = :user)
				""").param("group", round.groupId()).param("user", userId).query(Boolean.class).single();
	}

	private boolean confirmed(long roundId, long userId) {
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM smart_attendance_attempts
				                WHERE smart_attendance_round_id = :round AND user_id = :user AND result = 'ok')
				""").param("round", roundId).param("user", userId).query(Boolean.class).single();
	}

	private long failures(long roundId, long userId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM smart_attendance_attempts
				 WHERE smart_attendance_round_id = :round AND user_id = :user AND result IN ('bad_pin', 'bad_qr')
				""").param("round", roundId).param("user", userId).query(Long.class).single();
	}

	private Round lock(long courseId, long roundId) {
		return this.jdbc.sql(ROUND + " WHERE smart_attendance_round_id = :round AND course_id = :course FOR UPDATE")
			.param("round", roundId)
			.param("course", courseId)
			.query(SmartAttendanceService::round)
			.optional()
			.orElseThrow(ApiException::notFound);
	}

	private static Scope scope(Round round) {
		return new Scope(round.courseId(), round.sessionId(), round.roundId(), round.method());
	}

	private static Round round(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Round(rs.getLong("smart_attendance_round_id"), rs.getLong("course_id"),
				rs.getLong("attendance_session_id"), rs.getObject("course_group_id", Long.class), rs.getString("method"),
				rs.getObject("pin_length", Integer.class), rs.getBytes("round_secret"),
				rs.getObject("qr_rotation_sec", Integer.class), rs.getString("state"),
				rs.getObject("opened_at", LocalDateTime.class), rs.getObject("late_at", LocalDateTime.class),
				rs.getObject("ends_at", LocalDateTime.class), rs.getObject("closed_at", LocalDateTime.class));
	}

	/**
	 * @param method pin|qr
	 * @param minutes 진행 시간(분)
	 * @param lateAfterMinutes 연 뒤 이 시간(분)이 지나면 지각
	 * @param groupId 대상 그룹. null이면 수강생 전체
	 */
	record OpenInput(String method, int minutes, int lateAfterMinutes, Long groupId) {
	}

	/**
	 * @param state open|closed|cancelled(DB 값). 시간이 지난 open은 {@link #live}가 false다
	 */
	record Round(long roundId, long courseId, long sessionId, Long groupId, String method, Integer pinLength,
			byte[] secret, Integer qrRotationSec, String state, LocalDateTime openedAt, LocalDateTime lateAt,
			LocalDateTime endsAt, LocalDateTime closedAt) {

		boolean live(LocalDateTime now) {
			return "open".equals(this.state) && now.isBefore(this.endsAt) && this.secret != null;
		}

		/** 화면에 보일 회차 상태. 시간이 지난 회차는 expired다. */
		String displayState(LocalDateTime now) {
			if ("open".equals(this.state)) {
				return now.isBefore(this.endsAt) ? "open" : "expired";
			}
			if ("closed".equals(this.state) && (this.closedAt == null || !this.closedAt.isBefore(this.endsAt))) {
				return "expired";
			}
			return this.state;
		}

	}

	record QrToken(String token, LocalDateTime refreshAt) {
	}

	/**
	 * @param targets 대상 학생 수
	 * @param confirmed 이 회차에서 확정된 학생 수
	 * @param present 이 회차가 출석으로 기록한 수
	 * @param late 이 회차가 지각으로 기록한 수
	 * @param failedAttempts 틀린 PIN·QR 제출 수
	 * @param lockedStudents 틀린 횟수가 한도에 닿아 잠긴 학생 수
	 */
	record Counts(long targets, long confirmed, long present, long late, long failedAttempts, long lockedStudents) {
	}

	/**
	 * @param round 제출을 판정할 때 잠근 회차
	 * @param now 판정한 서버 시각
	 */
	record Outcome(Result result, Round round, LocalDateTime now) {
	}

	enum Result {

		OK("ok"), DUPLICATE("duplicate"), BAD_PIN("bad_pin"), BAD_QR("bad_qr"), LOCKED("locked"), EXPIRED("expired"),
		CLOSED("closed"), CANCELLED("cancelled"), NOT_TARGET("not_target");

		final String code;

		Result(String code) {
			this.code = code;
		}

	}

}
