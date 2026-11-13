package com.stackcorp.astra.learning;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.learning.AttendanceCodes.Method;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 온라인 강의 충족을 출석부에 반영한다(ADR-0014 7번, ADR-0030 8번). 연결은 저장하지 않고 판정할 때마다 지금 상태로 정한다.
 * <ul>
 * <li>활동의 대상 차시: 활동이 가리키는 차시({@code learning_activities.attendance_session_id})가 있으면 그 차시다. 없으면 활동
 * 주차의 휴강하지 않은 온라인 차시가 정확히 하나일 때 그 차시다. 없거나 둘 이상이면 출석부에 쓰지 않는다.</li>
 * <li>차시에 걸린 활동: 그 차시를 가리키는 영상 활동과, 그 차시가 주차의 유일한 온라인 차시이면 같은 주차에서 차시를 가리키지 않는
 * 영상 활동이다. 지우지 않았고 공개한 활동만 센다. 학생이 걸린 활동의 모든 콘텐츠를 충족하면 그 차시에 {@code 출석}이다.</li>
 * <li>휴강한 차시에는 쓰지 않는다. 기록이 없거나 {@code 미처리}일 때만 쓰고(ADR-0030 1번) 쓴 기록은 어떤 사건으로도 지우지 않는다.</li>
 * </ul>
 * 잠금은 주차 행이다. 콘텐츠를 처음 충족하는 하트비트는 진도를 쓰기 전에 활동 주차를 공유 잠금하고, 차시나 영상 활동을 바꾸는 명령은
 * 바꾸기 전에 관련 주차를 ID 순으로 배타 잠금한 뒤 같은 transaction에서 {@link #reconcile}로 다시 판정한다. 판정의 읽기는 잠금
 * 읽기라 transaction의 snapshot과 관계없이 commit된 최신 값을 본다. 그래서 충족과 차시 변경이 동시에 일어나도 한쪽이 다른 쪽의 결과를
 * 보고 판정한다. 충족하지 않는 하트비트는 출석부를 바꾸지 않으므로 주차를 잠그지 않는다.
 */
@Component
class OnlineAttendance {

	private static final String ONLINE = Method.ONLINE.db;

	/** 한 번에 쓰는 기록 수. */
	static final int BATCH = 200;

	private final JdbcClient jdbc;

	private final CourseRosters rosters;

	private final LearningClock clock;

	OnlineAttendance(JdbcClient jdbc, CourseRosters rosters, LearningClock clock) {
		this.jdbc = jdbc;
		this.rosters = rosters;
		this.clock = clock;
	}

	/**
	 * 주차 행을 ID 순서로 잠근다. 차시·영상 활동 명령은 배타 잠금, 적립 하트비트는 공유 잠금이다.
	 */
	void lockWeeks(boolean exclusive, Collection<Long> weekIds) {
		for (long week : new TreeSet<>(weekIds)) {
			this.jdbc.sql("SELECT course_week_id FROM course_weeks WHERE course_week_id = :week"
					+ (exclusive ? " FOR UPDATE" : " FOR SHARE")).param("week", week).query(Long.class).optional();
		}
	}

	/**
	 * 학생이 방금 콘텐츠 하나를 충족했다. 대상 차시가 있고 그 차시에 걸린 활동을 모두 충족했으면 출석부에 쓴다. 판정은 잠금 읽기라
	 * 부르는 쪽 snapshot이 그 사이 바뀐 활동·콘텐츠를 못 봐도 맞다.
	 * @param lockedWeek 부르는 쪽이 진도를 쓰기 전에 공유 잠금한 주차. 잠그지 않았으면 null이고 여기서 잠근다
	 * @param rule 이 충족을 만든 적립 규칙 버전
	 * @throws WeekMoved 잠근 뒤 활동이 다른 주차로 옮겨졌다. 옮긴 주차를 순서를 어겨 더 잠그면 두 주차를 ID 순으로 잠그는 명령과
	 * 교착할 수 있어, 부르는 쪽 transaction을 처음부터 다시 실행하게 한다
	 */
	void credit(long userId, long activityId, Long lockedWeek, LocalDateTime now, String rule) {
		Optional<Link> link = this.jdbc.sql("""
				SELECT course_week_id, attendance_session_id FROM learning_activities
				 WHERE activity_id = :activity AND deleted_at IS NULL
				   FOR SHARE
				""")
			.param("activity", activityId)
			.query((rs, n) -> new Link(rs.getLong(1), rs.getObject(2, Long.class)))
			.optional();
		if (link.isEmpty()) {
			return;
		}
		if (lockedWeek == null) {
			lockWeeks(false, List.of(link.get().weekId()));
		}
		else if (lockedWeek != link.get().weekId()) {
			throw new WeekMoved();
		}
		Long target = link.get().sessionId() != null ? link.get().sessionId()
				: single(onlineSessions(link.get().weekId())).orElse(null);
		if (target != null) {
			target(target).ifPresent((t) -> decide(t, List.of(userId), now, rule));
		}
	}

	/**
	 * 주차의 대상 차시와 주어진 차시를 지금 상태로 다시 판정해 수강생 중 충족한 학생을 채운다. 부르는 쪽은 주차를 배타 잠금했다.
	 * @param weekIds 차시나 영상 활동이 있었거나 옮겨 간 주차
	 * @param sessionIds 바뀐 차시. 다른 주차의 활동이 가리킬 수 있어 따로 판정한다
	 */
	void reconcile(long courseId, Collection<Long> weekIds, Collection<Long> sessionIds) {
		Set<Long> targets = new TreeSet<>(sessionIds);
		for (long week : new TreeSet<>(weekIds)) {
			single(onlineSessions(week)).ifPresent(targets::add);
			targets.addAll(this.jdbc.sql("""
					SELECT DISTINCT attendance_session_id FROM learning_activities
					 WHERE course_week_id = :week AND activity_type = 'lecture_weeks' AND attendance_session_id IS NOT NULL
					   AND deleted_at IS NULL
					""").param("week", week).query(Long.class).list());
		}
		if (targets.isEmpty()) {
			return;
		}
		List<Long> students = this.rosters.students(courseId).stream().sorted().toList();
		LocalDateTime now = this.clock.now();
		for (long id : targets) {
			target(id).filter((t) -> t.courseId() == courseId).ifPresent((t) -> decide(t, students, now, null));
		}
	}

	/**
	 * 걸린 콘텐츠를 모두 충족했고 아직 판정되지 않은 학생을 사용자 ID 순서로 한꺼번에 채운다. 읽기는 콘텐츠, 진도, 판정된 기록 세 번이고
	 * 쓰기는 {@value #BATCH}명마다 한 번이다.
	 * @param rule 규칙 버전. null이면 학생이 충족한 진도의 최신 규칙 버전이다
	 */
	private void decide(Target target, List<Long> users, LocalDateTime now, String rule) {
		if (users.isEmpty()) {
			return;
		}
		List<Long> items = this.jdbc.sql("""
				SELECT i.online_lecture_item_id
				  FROM learning_activities a FORCE INDEX (idx_learning_activities_course_id_activity_type)
				  JOIN online_lecture_items i ON i.activity_id = a.activity_id
				 WHERE a.course_id = :course AND a.activity_type = 'lecture_weeks' AND a.deleted_at IS NULL
				   AND a.published_yn = 1 AND i.deleted_at IS NULL
				   AND (a.attendance_session_id = :session
				        OR (:auto AND a.attendance_session_id IS NULL AND a.course_week_id = :week))
				   FOR SHARE
				""")
			.param("course", target.courseId())
			.param("session", target.id())
			.param("auto", target.auto())
			.param("week", target.weekId())
			.query(Long.class)
			.list();
		if (items.isEmpty()) {
			return;
		}
		Map<Long, Integer> metCount = new HashMap<>();
		Map<Long, String> rules = new HashMap<>();
		this.jdbc.sql("""
				SELECT user_id, rule_version FROM online_lecture_item_progress
				 WHERE user_id IN (:users) AND online_lecture_item_id IN (:items) AND met_at IS NOT NULL
				   FOR SHARE
				""").param("users", users).param("items", items).query((rs) -> {
			long user = rs.getLong(1);
			metCount.merge(user, 1, Integer::sum);
			String version = rs.getString(2);
			rules.merge(user, version == null ? "" : version, (a, b) -> a.compareTo(b) >= 0 ? a : b);
		});
		List<Long> met = users.stream().filter((u) -> metCount.getOrDefault(u, 0) == items.size()).toList();
		if (met.isEmpty()) {
			return;
		}
		// 이미 판정된 학생은 쓰지 않는다. 이 읽기가 낡아도 아래 쓰기가 미처리일 때만 바꾸므로 판정은 같다.
		Set<Long> decided = new HashSet<>(this.jdbc.sql("""
				SELECT user_id FROM attendance_records
				 WHERE attendance_session_id = :session AND user_id IN (:users) AND status <> '미처리'
				""").param("session", target.id()).param("users", met).query(Long.class).list());
		List<Long> pending = met.stream().filter((u) -> !decided.contains(u)).toList();
		for (int from = 0; from < pending.size(); from += BATCH) {
			write(target.id(), pending.subList(from, Math.min(pending.size(), from + BATCH)), now,
					(u) -> rule != null ? rule : rules.get(u));
		}
	}

	/**
	 * 기록이 없으면 넣고, 미처리면 바꾼다. 이미 판정된 기록(담당자, 스마트출석, 먼저 쓴 온라인)은 그대로 둔다. 상태는 다른 열이 바꾸기 전
	 * 상태를 보도록 마지막에 정한다.
	 */
	private void write(long sessionId, List<Long> users, LocalDateTime now, Function<Long, String> rule) {
		StringBuilder values = new StringBuilder();
		Map<String, Object> params = new HashMap<>();
		params.put("session", sessionId);
		params.put("now", now);
		for (int i = 0; i < users.size(); i++) {
			values.append(i == 0 ? "" : ", ").append("(:session, :u").append(i)
				.append(", '출석', 'online', 100.00, :now, :r").append(i).append(')');
			params.put("u" + i, users.get(i));
			params.put("r" + i, rule.apply(users.get(i)));
		}
		this.jdbc.sql("""
				INSERT INTO attendance_records (attendance_session_id, user_id, status, source, progress_percent, decided_at,
				                                rule_version)
				VALUES %s AS n
				ON DUPLICATE KEY UPDATE
				  source = IF(attendance_records.status = '미처리', n.source, attendance_records.source),
				  progress_percent = IF(attendance_records.status = '미처리', n.progress_percent, attendance_records.progress_percent),
				  decided_at = IF(attendance_records.status = '미처리', n.decided_at, attendance_records.decided_at),
				  rule_version = IF(attendance_records.status = '미처리', n.rule_version, attendance_records.rule_version),
				  row_version = IF(attendance_records.status = '미처리', attendance_records.row_version + 1,
				                   attendance_records.row_version),
				  status = IF(attendance_records.status = '미처리', n.status, attendance_records.status)
				""".formatted(values)).params(params).update();
	}

	private List<Long> onlineSessions(long weekId) {
		return this.jdbc.sql("""
				SELECT attendance_session_id FROM attendance_sessions
				 WHERE course_week_id = :week AND attendance_method = :online AND session_state <> 'cancelled'
				   FOR SHARE
				""").param("week", weekId).param("online", ONLINE).query(Long.class).list();
	}

	/** 휴강한 차시는 대상이 아니다. */
	private Optional<Target> target(long sessionId) {
		return this.jdbc.sql("""
				SELECT course_id, course_week_id, attendance_method, session_state FROM attendance_sessions
				 WHERE attendance_session_id = :id
				   FOR SHARE
				""")
			.param("id", sessionId)
			.query((rs, n) -> new Target(sessionId, rs.getLong(1), rs.getLong(2), rs.getString(3),
					rs.getString(4)))
			.optional()
			.filter((t) -> !"cancelled".equals(t.state()))
			.map((t) -> ONLINE.equals(t.method()) && single(onlineSessions(t.weekId())).isPresent() ? t.automatic()
					: t);
	}

	private static Optional<Long> single(List<Long> sessions) {
		return sessions.size() == 1 ? Optional.of(sessions.getFirst()) : Optional.empty();
	}

	private record Link(long weekId, Long sessionId) {
	}

	/**
	 * @param auto 주차의 유일한 온라인 차시라 같은 주차의 연결 없는 영상 활동도 걸린다
	 */
	private record Target(long id, long courseId, long weekId, String method, String state, boolean auto) {

		Target(long id, long courseId, long weekId, String method, String state) {
			this(id, courseId, weekId, method, state, false);
		}

		Target automatic() {
			return new Target(this.id, this.courseId, this.weekId, this.method, this.state, true);
		}

	}

	/** 공유 잠금한 뒤 활동이 다른 주차로 옮겨졌다. 부르는 쪽 transaction을 되돌리고 처음부터 다시 실행한다. */
	static final class WeekMoved extends RuntimeException {

		WeekMoved() {
			super("영상 활동이 다른 주차로 옮겨졌습니다.", null, false, false);
		}

	}

}
