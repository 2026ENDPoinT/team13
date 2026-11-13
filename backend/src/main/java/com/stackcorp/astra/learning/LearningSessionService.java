package com.stackcorp.astra.learning;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.files.api.DeliveryCookies;
import com.stackcorp.astra.files.api.DeliveryCookies.DeliveryCookie;
import com.stackcorp.astra.identity.api.LearningAuthGrants;
import com.stackcorp.astra.identity.api.LearningAuthGrants.Grant;
import com.stackcorp.astra.learning.CreditRule.Credit;
import com.stackcorp.astra.learning.CreditRule.Outcome;
import com.stackcorp.astra.learning.CreditRule.Window;
import com.stackcorp.astra.learning.LearningAccess.Activity;
import com.stackcorp.astra.learning.LectureVideoController.Playback;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 온라인 강의 학습 session, 하트비트 적립, 출석 인정(ADR-0014). 적립은 서버 시각과 원장으로만 정한다(SC-06, SEC-11, CHG-05).
 * <ul>
 * <li>사용자당 활성 session은 하나다(learning_sessions.active_user_key 유일 제약). 새 session은 앞선 session을 superseded로 닫는다.</li>
 * <li>하트비트와 종료는 session 행을 잠근 뒤 시각을 읽고 권한을 다시 판정한다. 권한이 사라지면 session을 닫고 적립하지 않는다.
 * 이미 적립한 시간은 그대로 둔다.</li>
 * <li>출석인정 충족은 콘텐츠마다 한 번, 조건부 UPDATE로 기록하고 이후 어떤 사건으로도 되돌리지 않는다.</li>
 * <li>학습 인증을 요구하는 배포({@code astra.learning.require-auth-grant})에서는 학생의 시작에 유효한 학습 인증 grant가 있어야 하고
 * 적립 유효기한을 session에 저장한다. 하트비트마다 요청한 로그인 session의 grant를 다시 보고 그 경계에서 구간을 자른다. 경계를 지나면
 * {@code reauth_required}이고 다시 인증하면 소급 없이 이어 간다(SC-06-A, ADR-0029, 규칙 v2).</li>
 * </ul>
 * 404가 되거나 session이 닫히는 응답도 session 상태 변경은 commit한 뒤 오류로 알린다.
 * <p>
 * 지표: 받아들인 하트비트마다 앞 기준 시각과의 간격(astra.learning.heartbeat.gap)과 닫힌 session 수를 사유별로 센다
 * (astra.learning.sessions.closed, reason=button|unload|forced|superseded|lease_expired). 사용자와 과목은 tag에 없다.
 */
@Service
class LearningSessionService {

	private static final int START_ATTEMPTS = 3;

	private final JdbcClient jdbc;

	private final TransactionTemplate tx;

	private final LearningClock clock;

	private final CourseMemberships memberships;

	private final DeliveryCookies cookies;

	private final LearningSessionProperties properties;

	private final LearningAuthGrants grants;

	private final OnlineAttendance attendance;

	private final MeterRegistry meters;

	private final Timer heartbeatGap;

	LearningSessionService(JdbcClient jdbc, TransactionTemplate tx, LearningClock clock,
			CourseMemberships memberships, DeliveryCookies cookies, LearningSessionProperties properties,
			LearningAuthGrants grants, OnlineAttendance attendance, MeterRegistry meters) {
		this.jdbc = jdbc;
		this.tx = tx;
		this.clock = clock;
		this.memberships = memberships;
		this.cookies = cookies;
		this.properties = properties;
		this.grants = grants;
		this.attendance = attendance;
		this.meters = meters;
		this.heartbeatGap = Timer.builder("astra.learning.heartbeat.gap")
			.description("받아들인 하트비트와 앞 기준 시각(앞 하트비트 또는 시작) 사이의 서버 시각 간격")
			.register(meters);
	}

	/**
	 * 콘텐츠의 학습 session을 시작한다(EP-0049, EP-0053, EP-0054). 볼 수 없는 콘텐츠면 404, 학습 인증이 필요한데 없으면 403
	 * {@code learning_auth_required}다.
	 * @param loginSessionId 요청한 로그인 session. 학습 인증 grant는 이 session에 묶인다
	 */
	Granted<Started> start(long userId, long loginSessionId, long courseId, long itemId) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.tx.execute((status) -> doStart(userId, loginSessionId, courseId, itemId));
			}
			catch (DuplicateKeyException | PessimisticLockingFailureException ex) {
				// 같은 사용자의 시작이 동시에 들어오면 활성 session 유일 제약에서 한쪽이 진다. 다시 하면 이긴 쪽을 대체한다.
				if (attempt == START_ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	/** 하트비트(EP-0050). seq는 session 안에서 증가하는 순번이다. 위치는 이어보기 힌트로만 저장한다. */
	Granted<HeartbeatResult> heartbeat(long userId, long loginSessionId, long courseId, long sessionId, long seq,
			Integer positionSec, Boolean playing) {
		return retried(() -> this.tx
			.execute((status) -> doHeartbeat(userId, loginSessionId, courseId, sessionId, seq, positionSec, playing)))
			.orThrow();
	}

	/** 명시적 종료(EP-0057, EP-0058). 마지막 기준 시각 뒤 구간을 같은 규칙으로 적립한다. 이미 닫힌 session이면 그 결과를 그대로 돌려준다. */
	Ended end(long userId, long loginSessionId, long courseId, long sessionId, Integer positionSec, String reason) {
		return retried(
				() -> this.tx.execute((status) -> doEnd(userId, loginSessionId, courseId, sessionId, positionSec, reason)))
			.orThrow();
	}

	/**
	 * 교착이나 잠금 시간 초과로 진 transaction은 전부 되돌아가므로 처음부터 다시 실행한다. 적립은 주차 행을 잠그고 출석 차시를 잠금 읽기로
	 * 판정해, 차시·영상 활동 명령과 드물게 엇갈릴 수 있다(OnlineAttendance).
	 */
	private <T> T retried(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return work.get();
			}
			catch (PessimisticLockingFailureException ex) {
				if (attempt == START_ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	/**
	 * lease가 지난 열린 session을 닫는다. 후보를 잠그지 않고 읽은 뒤 session마다 조건부 UPDATE(열린 상태일 때만)를 따로 commit한다.
	 * worker가 둘이어도 session마다 한쪽만 닫고, 한 번에 행 하나만 잠가 서로 기다리다 교착하지 않는다.
	 * 종료 시각은 마지막 기준 시각이다. 그 뒤 구간은 적립하지 않는다.
	 *
	 * @return 이 호출이 닫은 session 수
	 */
	int expireLeases() {
		LocalDateTime now = this.clock.now();
		List<Long> candidates = this.jdbc.sql("""
				SELECT learning_session_id FROM learning_sessions
				 WHERE active_user_key IS NOT NULL
				   AND TIMESTAMPADD(SECOND, lease_sec, COALESCE(last_heartbeat_at, started_at)) < :now
				""").param("now", now).query(Long.class).list();
		int closed = 0;
		for (long sessionId : candidates) {
			closed += closed("lease_expired", this.jdbc.sql("""
					UPDATE learning_sessions
					   SET state = 'lease_expired', end_reason = 'lease_expired', ended_at = COALESCE(last_heartbeat_at, started_at)
					 WHERE learning_session_id = :session AND state IN ('active', 'reauth_required')
					   AND TIMESTAMPADD(SECOND, lease_sec, COALESCE(last_heartbeat_at, started_at)) < :now
					""").param("session", sessionId).param("now", now).update());
		}
		return closed;
	}

	private Granted<Started> doStart(long userId, long loginSessionId, long courseId, long itemId) {
		Item item = item(itemId, courseId).orElseThrow(ApiException::notFound);
		String assetKey = LectureVideoController.assetKey(item.contentPath()).orElseThrow(ApiException::notFound);
		LocalDateTime now = this.clock.now();
		Access access = access(userId, item, now);
		if (!access.visible()) {
			throw ApiException.notFound();
		}
		LocalDateTime authorizedUntil = null;
		if (access.learner() && this.properties.requireAuthGrant()) {
			// SC-06-A: 시작할 때 grant가 유효해야 한다. client가 인증을 건너뛰어도 여기서 막힌다(SEC-04).
			LocalDateTime grantUntil = this.grants.current(loginSessionId)
				.map(Grant::expiresAt)
				.filter(now::isBefore)
				.orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN, "learning_auth_required",
						"학습을 시작하려면 본인 인증이 필요합니다."));
			authorizedUntil = earliest(grantUntil, item.closeAt());
		}
		List<DeliveryCookie> issued = this.cookies.forVideo(assetKey);
		if (issued.isEmpty()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "delivery_unavailable", "영상 전달이 설정되지 않았습니다.");
		}
		// 열린 session 유일 제약의 index 행을 잠그고 대체한다. 그 session의 적립은 마지막 기준 시각까지다.
		closed("superseded", this.jdbc.sql("""
				UPDATE learning_sessions SET state = 'superseded', end_reason = 'superseded', ended_at = :now
				 WHERE active_user_key = :user AND state IN ('active', 'reauth_required')
				""").param("now", now).param("user", userId).update());
		int interval = (int) this.properties.heartbeatInterval().toSeconds();
		int lease = CreditRule.leaseSec(interval);
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO learning_sessions (user_id, course_id, activity_id, online_lecture_item_id, state, started_at,
				                               heartbeat_interval_sec, lease_sec, credit_authorized_until)
				VALUES (:user, :course, :activity, :item, 'active', :now, :interval, :lease, :authorizedUntil)
				""")
			.param("user", userId)
			.param("course", courseId)
			.param("activity", item.activityId())
			.param("item", itemId)
			.param("now", now)
			.param("interval", interval)
			.param("lease", lease)
			.param("authorizedUntil", authorizedUntil)
			.update(key, "learning_session_id");
		long sessionId = key.getKey().longValue();
		if (access.learner()) {
			recordView(userId, courseId, item.activityId(), now);
		}
		ItemProgress progress = progress(userId, item);
		Started started = new Started(sessionId, itemId, interval, lease, access.learner(),
				authorizedUntil != null ? authorizedUntil : item.closeAt(), progress.resumePositionSec(), progress);
		return new Granted<>(started, playback(item, issued), issued);
	}

	private Step<Granted<HeartbeatResult>> doHeartbeat(long userId, long loginSessionId, long courseId, long sessionId,
			long seq, Integer positionSec, Boolean playing) {
		Session session = lock(userId, courseId, sessionId);
		LocalDateTime now = this.clock.now();
		Optional<ApiException> closed = closeIfInvalid(session, now);
		if (closed.isPresent()) {
			return Step.failed(closed.get());
		}
		Item item = playable(item(session.itemId(), courseId));
		Access access = item == null ? Access.NONE : access(userId, item, now);
		if (!access.visible()) {
			close(session, now, "forced");
			return Step.failed(ApiException.notFound());
		}
		checkPosition(positionSec, item);
		List<DeliveryCookie> issued = LectureVideoController.assetKey(item.contentPath())
			.map(this.cookies::forVideo)
			.orElse(List.of());
		long lastSeq = this.jdbc.sql("""
				SELECT COALESCE(MAX(seq_no), 0) FROM learning_heartbeats WHERE learning_session_id = :session
				""").param("session", sessionId).query(Long.class).single();
		Beat beat;
		if (seq <= lastSeq) {
			// 재전송, 순서가 바뀐 요청. 원장과 기준 시각을 건드리지 않는다.
			beat = new Beat(Outcome.DUPLICATE, 0, session.baseline(), session.reauthRequired(),
					session.creditAuthorizedUntil());
		}
		else {
			this.heartbeatGap.record(Duration.between(session.baseline(), now));
			beat = session.bound() ? boundBeat(session, loginSessionId, item, access, positionSec, now)
					: unboundBeat(session, item, access, positionSec, now);
			this.jdbc.sql("""
					INSERT INTO learning_heartbeats (learning_session_id, seq_no, received_at, position_sec, playing_yn, credited_sec)
					VALUES (:session, :seq, :now, :position, :playing, :credited)
					""")
				.param("session", sessionId)
				.param("seq", seq)
				.param("now", now)
				.param("position", positionSec)
				.param("playing", playing)
				.param("credited", beat.credited())
				.update();
		}
		LocalDateTime creditUntil = session.bound() ? beat.authorizedUntil() : item.closeAt();
		boolean creditOpen = access.learner() && !beat.reauthRequired()
				&& (creditUntil == null || now.isBefore(creditUntil));
		LocalDateTime leaseEnd = beat.baseline().plusSeconds(session.leaseSec());
		HeartbeatResult result = new HeartbeatResult(sessionId, beat.outcome().code(), beat.credited(), creditOpen,
				beat.reauthRequired() ? "reauth_required" : "authorized", creditUntil, leaseEnd,
				(int) Math.max(0, Duration.between(now, leaseEnd).toSeconds()), progress(userId, item));
		return Step.done(new Granted<>(result, issued.isEmpty() ? null : playback(item, issued), issued));
	}

	/** 규칙 v1 하트비트. */
	private Beat unboundBeat(Session session, Item item, Access access, Integer positionSec, LocalDateTime now) {
		Credit credit = credit(session, item, access, now, false, null);
		apply(session, item, credit, positionSec, now);
		return new Beat(credit.outcome(), credit.seconds(), credit.baseline(), false, null);
	}

	/**
	 * 규칙 v2 하트비트(SC-06-A). 경계는 요청한 로그인 session의 grant로 매번 다시 정한다. 폐기하면 만료가 폐기 시각으로 당겨지고, grant가
	 * 없으면 기준 시각이 경계라 더 적립하지 않는다.
	 */
	private Beat boundBeat(Session session, long loginSessionId, Item item, Access access, Integer positionSec,
			LocalDateTime now) {
		LocalDateTime boundary = this.grants.current(loginSessionId)
			.map(Grant::expiresAt)
			.orElse(session.baseline());
		if (session.reauthRequired()) {
			// 적립하지 않고 기준 시각만 옮긴다. lease는 유지되고 인증이 없던 시간은 나중에도 적립하지 않는다.
			boolean resumed = access.learner() && now.isBefore(boundary);
			LocalDateTime authorizedUntil = resumed ? earliest(boundary, item.closeAt())
					: session.creditAuthorizedUntil();
			this.jdbc.sql("""
					UPDATE learning_sessions
					   SET state = :state, last_heartbeat_at = :now, credit_authorized_until = :until
					 WHERE learning_session_id = :session
					""")
				.param("state", resumed ? "active" : "reauth_required")
				.param("now", now)
				.param("until", authorizedUntil)
				.param("session", session.id())
				.update();
			if (positionSec != null) {
				recordProgress(session.userId(), item, 0, positionSec, now, session.rule());
			}
			return new Beat(resumed ? Outcome.RESUMED : Outcome.REAUTH_REQUIRED, 0, now, !resumed, authorizedUntil);
		}
		// 인증이 close_at보다 먼저 끝날 때만 재인증 대기가 된다. close_at이 먼저면 기간이 끝난 것이다.
		boolean authEndsFirst = item.closeAt() == null || boundary.isBefore(item.closeAt());
		boolean passed = authEndsFirst && !now.isBefore(boundary);
		Credit credit = credit(session, item, access, now, passed, boundary);
		apply(session, item, credit, positionSec, now);
		LocalDateTime authorizedUntil = earliest(boundary, item.closeAt());
		this.jdbc.sql("""
				UPDATE learning_sessions SET state = :state, credit_authorized_until = :until
				 WHERE learning_session_id = :session
				""")
			.param("state", passed ? "reauth_required" : "active")
			.param("until", authorizedUntil)
			.param("session", session.id())
			.update();
		Outcome outcome = passed && credit.seconds() == 0 ? Outcome.REAUTH_REQUIRED : credit.outcome();
		return new Beat(outcome, credit.seconds(), credit.baseline(), passed, authorizedUntil);
	}

	private Step<Ended> doEnd(long userId, long loginSessionId, long courseId, long sessionId, Integer positionSec,
			String reason) {
		Session session = lock(userId, courseId, sessionId);
		Item item = item(session.itemId(), courseId).orElse(null);
		if (!session.open()) {
			return Step.done(ended(session, session.state(), session.endReason(), 0, item));
		}
		LocalDateTime now = this.clock.now();
		if (expired(session, now)) {
			expire(session);
			return Step.done(ended(session, "lease_expired", "lease_expired", 0, item));
		}
		Access access = playable(Optional.ofNullable(item)) == null ? Access.NONE : access(userId, item, now);
		if (!access.visible()) {
			close(session, now, "forced");
			return Step.failed(ApiException.notFound());
		}
		checkPosition(positionSec, item);
		int credited = 0;
		if (session.reauthRequired()) {
			if (positionSec != null) {
				recordProgress(session.userId(), item, 0, positionSec, now, session.rule());
			}
		}
		else {
			LocalDateTime boundary = session.bound()
					? this.grants.current(loginSessionId).map(Grant::expiresAt).orElse(session.baseline()) : null;
			Credit credit = credit(session, item, access, now, true, boundary);
			apply(session, item, credit, positionSec, now);
			credited = credit.seconds();
		}
		close(session, now, reason);
		return Step.done(ended(session, "ended", reason, credited, item));
	}

	/**
	 * @param last 최소 간격을 보지 않는다. 종료와 학습 인증 경계를 지난 하트비트다
	 * @param authBoundary 학습 인증 경계(v2). 없으면 null
	 */
	private Credit credit(Session session, Item item, Access access, LocalDateTime now, boolean last,
			LocalDateTime authBoundary) {
		Window window = access.learner() ? new Window(item.openAt(), earliest(item.closeAt(), authBoundary)) : null;
		LocalDateTime frontier = window == null ? null : frontier(session.userId());
		return CreditRule.apply(session.baseline(), frontier, now, window, session.intervalSec(), last);
	}

	/** null은 제한 없음이다. */
	private static LocalDateTime earliest(LocalDateTime a, LocalDateTime b) {
		if (a == null) {
			return b;
		}
		return b != null && b.isBefore(a) ? b : a;
	}

	/**
	 * 원장, 기준 시각, 진도를 한 transaction에서 바꾼다. 같은 session 행 잠금 아래에서만 부른다. 적립하면 진도를 쓰기 전에 활동 주차를
	 * 공유 잠금한다. 차시·영상 활동 명령이 주차를 배타 잠금하고 출석부를 다시 판정하므로 충족과 그 판정이 서로 엇갈리지 않는다.
	 */
	private void apply(Session session, Item item, Credit credit, Integer positionSec, LocalDateTime now) {
		if (credit.seconds() > 0) {
			this.attendance.lockWeeks(false, List.of(item.weekId()));
			this.jdbc.sql("""
					INSERT INTO learning_credit_ledger (learning_session_id, from_at, to_at, credited_sec, in_period_yn, rule_version)
					VALUES (:session, :from, :to, :seconds, 1, :rule)
					""")
				.param("session", session.id())
				.param("from", credit.from())
				.param("to", credit.to())
				.param("seconds", credit.seconds())
				.param("rule", session.rule())
				.update();
		}
		if (credit.outcome() != Outcome.TOO_EARLY) {
			this.jdbc.sql("UPDATE learning_sessions SET last_heartbeat_at = :baseline WHERE learning_session_id = :session")
				.param("baseline", credit.baseline())
				.param("session", session.id())
				.update();
		}
		if (credit.seconds() > 0 || positionSec != null) {
			recordProgress(session.userId(), item, credit.seconds(), positionSec, now, session.rule());
		}
		if (credit.seconds() > 0) {
			decide(session.userId(), item, now, session.rule());
		}
	}

	/** @param rule 이 진도를 바꾼 적립 규칙 버전 */
	private void recordProgress(long userId, Item item, int seconds, Integer positionSec, LocalDateTime now,
			String rule) {
		Integer required = ItemProgress.required(item.requiredSec(), item.durationSec());
		this.jdbc.sql("""
				INSERT INTO online_lecture_item_progress (user_id, online_lecture_item_id, credited_in_sec, max_position_sec,
				                                          last_position_sec, last_position_at, decided_status, rule_version)
				VALUES (:user, :item, :seconds, :maxPosition, :position, :positionAt, :status, :rule) AS n
				ON DUPLICATE KEY UPDATE
				  credited_in_sec = online_lecture_item_progress.credited_in_sec + n.credited_in_sec,
				  max_position_sec = GREATEST(online_lecture_item_progress.max_position_sec, n.max_position_sec),
				  last_position_sec = COALESCE(n.last_position_sec, online_lecture_item_progress.last_position_sec),
				  last_position_at = COALESCE(n.last_position_at, online_lecture_item_progress.last_position_at),
				  decided_status = CASE WHEN online_lecture_item_progress.met_at IS NOT NULL THEN 'met'
				                        ELSE n.decided_status END,
				  rule_version = n.rule_version
				""")
			.param("user", userId)
			.param("item", item.itemId())
			.param("seconds", seconds)
			.param("maxPosition", positionSec == null ? 0 : positionSec)
			.param("position", positionSec)
			.param("positionAt", positionSec == null ? null : now)
			.param("status", required == null ? "unknown" : "under_required")
			.param("rule", rule)
			.update();
	}

	/**
	 * 기간 안 적립이 필요 시간에 닿으면 충족 시각을 한 번 기록한다. 활동의 모든 콘텐츠가 충족되면 활동 완료를 한 번 쓰고 출석부 반영을
	 * {@link OnlineAttendance}에 맡긴다. 담당자가 이미 정한 출석 상태는 바꾸지 않는다.
	 */
	private void decide(long userId, Item item, LocalDateTime now, String rule) {
		Integer required = ItemProgress.required(item.requiredSec(), item.durationSec());
		if (required == null) {
			return;
		}
		int met = this.jdbc.sql("""
				UPDATE online_lecture_item_progress SET met_at = :now, decided_status = 'met', rule_version = :rule
				 WHERE user_id = :user AND online_lecture_item_id = :item AND met_at IS NULL AND credited_in_sec >= :required
				""")
			.param("now", now)
			.param("rule", rule)
			.param("user", userId)
			.param("item", item.itemId())
			.param("required", required)
			.update();
		if (met == 0) {
			return;
		}
		boolean complete = this.jdbc.sql("""
				SELECT NOT EXISTS (
				  SELECT 1 FROM online_lecture_items i
				    LEFT JOIN online_lecture_item_progress p
				           ON p.online_lecture_item_id = i.online_lecture_item_id AND p.user_id = :user
				   WHERE i.activity_id = :activity AND i.deleted_at IS NULL AND p.met_at IS NULL)
				""").param("user", userId).param("activity", item.activityId()).query(Boolean.class).single();
		if (!complete) {
			return;
		}
		this.jdbc.sql("""
				INSERT INTO activity_progress (user_id, activity_id, completed_at) VALUES (:user, :activity, :now) AS n
				ON DUPLICATE KEY UPDATE completed_at = COALESCE(activity_progress.completed_at, n.completed_at)
				""").param("user", userId).param("activity", item.activityId()).param("now", now).update();
		this.attendance.credit(userId, item.activityId(), now, rule);
	}

	/** 학습 시작은 명시적 POST라 열람 기록과 이어보기 포인터를 여기서 남긴다(SC-04). */
	private void recordView(long userId, long courseId, long activityId, LocalDateTime now) {
		this.jdbc.sql("""
				INSERT INTO activity_progress (user_id, activity_id, first_viewed_at, last_viewed_at, view_count)
				VALUES (:user, :activity, :now, :now, 1) AS n
				ON DUPLICATE KEY UPDATE first_viewed_at = COALESCE(activity_progress.first_viewed_at, n.first_viewed_at),
				                        last_viewed_at = n.last_viewed_at, view_count = activity_progress.view_count + 1
				""").param("user", userId).param("activity", activityId).param("now", now).update();
		this.jdbc.sql("""
				INSERT INTO course_continue_pointers (user_id, course_id, activity_id, menu_code, recorded_at)
				VALUES (:user, :course, :activity, 'lecture_weeks', :now) AS n
				ON DUPLICATE KEY UPDATE activity_id = n.activity_id, menu_code = n.menu_code, recorded_at = n.recorded_at
				""").param("user", userId).param("course", courseId).param("activity", activityId).param("now", now).update();
	}

	/** 이 사용자의 모든 session이 적립한 구간의 끝. 새 구간은 이 뒤에서만 시작한다(SEC-05). */
	private LocalDateTime frontier(long userId) {
		return this.jdbc.sql("""
				SELECT MAX(l.to_at) FROM learning_credit_ledger l
				  JOIN learning_sessions s ON s.learning_session_id = l.learning_session_id
				 WHERE s.user_id = :user
				""").param("user", userId).query(LocalDateTime.class).optional().orElse(null);
	}

	/** 자기 session이 아니거나 경로의 과목과 다르면 잠그지 않고 404다. */
	private Session lock(long userId, long courseId, long sessionId) {
		return this.jdbc.sql("""
				SELECT learning_session_id, user_id, online_lecture_item_id, state, end_reason, started_at, last_heartbeat_at,
				       heartbeat_interval_sec, lease_sec, credit_authorized_until
				  FROM learning_sessions
				 WHERE learning_session_id = :session AND user_id = :user AND course_id = :course
				   FOR UPDATE
				""")
			.param("session", sessionId)
			.param("user", userId)
			.param("course", courseId)
			.query((rs, n) -> new Session(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5),
					rs.getObject(6, LocalDateTime.class), rs.getObject(7, LocalDateTime.class), rs.getInt(8), rs.getInt(9),
					rs.getObject(10, LocalDateTime.class)))
			.optional()
			.orElseThrow(ApiException::notFound);
	}

	/**
	 * 닫힌 session이면 그 사유의 오류. lease가 지났으면 지금 닫고 같은 오류를 낸다. 코드는 저장한 종료 사유마다 하나다.
	 * <ul>
	 * <li>{@code session_superseded}: 같은 사용자가 다른 곳에서 학습을 시작했다. 어느 창이나 기기인지는 알리지 않는다.</li>
	 * <li>{@code session_expired}: lease 안에 하트비트가 오지 않아 닫혔다.</li>
	 * <li>{@code session_ended}: 이 session에 종료 요청(button, unload)이 이미 왔다.</li>
	 * <li>{@code session_withdrawn}: 콘텐츠를 볼 수 없게 되어 서버가 닫았다(forced).</li>
	 * </ul>
	 */
	private Optional<ApiException> closeIfInvalid(Session session, LocalDateTime now) {
		if (session.open() && !expired(session, now)) {
			return Optional.empty();
		}
		String reason = session.endReason();
		if (session.open()) {
			expire(session);
			reason = "lease_expired";
		}
		return Optional.of(switch (reason == null ? "" : reason) {
			case "superseded" -> new ApiException(HttpStatus.CONFLICT, "session_superseded",
					"다른 창이나 기기에서 학습을 시작해 이 학습이 끝났습니다.");
			case "lease_expired" -> new ApiException(HttpStatus.CONFLICT, "session_expired",
					"학습 신호가 끊겨 학습이 끝났습니다. 다시 시작해 주세요.");
			case "forced" -> new ApiException(HttpStatus.CONFLICT, "session_withdrawn",
					"볼 수 없게 된 콘텐츠라 학습이 끝났습니다.");
			default -> new ApiException(HttpStatus.CONFLICT, "session_ended", "이미 끝낸 학습입니다. 다시 시작해 주세요.");
		});
	}

	private static boolean expired(Session session, LocalDateTime now) {
		return session.baseline().plusSeconds(session.leaseSec()).isBefore(now);
	}

	private void expire(Session session) {
		closed("lease_expired", this.jdbc.sql("""
				UPDATE learning_sessions
				   SET state = 'lease_expired', end_reason = 'lease_expired', ended_at = COALESCE(last_heartbeat_at, started_at)
				 WHERE learning_session_id = :session AND state IN ('active', 'reauth_required')
				""").param("session", session.id()).update());
	}

	private void close(Session session, LocalDateTime now, String reason) {
		closed(reason, this.jdbc.sql("""
				UPDATE learning_sessions SET state = 'ended', end_reason = :reason, ended_at = :now
				 WHERE learning_session_id = :session AND state IN ('active', 'reauth_required')
				""").param("reason", reason).param("now", now).param("session", session.id()).update());
	}

	/** @return count. 사유는 코드가 정한 값(button, unload, forced, superseded, lease_expired)뿐이다 */
	private int closed(String reason, int count) {
		if (count > 0) {
			Counter.builder("astra.learning.sessions.closed")
				.description("닫힌 학습 session 수")
				.tag("reason", reason)
				.register(this.meters)
				.increment(count);
		}
		return count;
	}

	private Ended ended(Session session, String state, String reason, int credited, Item item) {
		return new Ended(session.id(), state, reason, credited, item == null ? null : progress(session.userId(), item));
	}

	/** 학생은 공개됐고 시작한 활동만 보고 적립 대상이다. 담당자는 언제나 보지만 적립하지 않는다. 시각은 적립과 같은 시계다. */
	private Access access(long userId, Item item, LocalDateTime now) {
		Optional<CourseRole> role = this.memberships.roleOf(userId, item.courseId());
		if (role.isEmpty()) {
			return Access.NONE;
		}
		if (role.get() == CourseRole.INSTRUCTOR) {
			return new Access(true, false);
		}
		boolean open = LearningAccess.open(new Activity(item.activityId(), item.courseId(), item.published(), item.openAt()),
				now);
		return open ? new Access(true, true) : Access.NONE;
	}

	private static void checkPosition(Integer positionSec, Item item) {
		int max = item.durationSec() != null ? item.durationSec() : 86_400;
		if (positionSec != null && (positionSec < 0 || positionSec > max)) {
			throw ApiException.invalid("positionSec는 0 이상 콘텐츠 길이 이하여야 합니다.");
		}
	}

	private ItemProgress progress(long userId, Item item) {
		return this.jdbc.sql("""
				SELECT credited_in_sec, met_at, last_position_sec FROM online_lecture_item_progress
				 WHERE user_id = :user AND online_lecture_item_id = :item
				""")
			.param("user", userId)
			.param("item", item.itemId())
			.query((rs, n) -> ItemProgress.of(item.itemId(), item.requiredSec(), item.durationSec(), rs.getInt(1),
					rs.getObject(2, LocalDateTime.class), rs.getObject(3, Integer.class)))
			.optional()
			.orElseGet(() -> ItemProgress.of(item.itemId(), item.requiredSec(), item.durationSec(), 0, null, null));
	}

	private Playback playback(Item item, List<DeliveryCookie> issued) {
		return issued.isEmpty() ? null : new Playback("/" + item.contentPath(), this.cookies.videoTtl().toSeconds());
	}

	private Optional<Item> item(long itemId, long courseId) {
		return this.jdbc.sql("""
				SELECT i.online_lecture_item_id, i.activity_id, a.course_id, a.published_yn, a.open_at, a.close_at,
				       a.course_week_id, i.content_path, i.duration_sec, i.required_sec
				  FROM online_lecture_items i JOIN learning_activities a ON a.activity_id = i.activity_id
				 WHERE i.online_lecture_item_id = :item AND a.course_id = :course AND a.deleted_at IS NULL
				   AND i.deleted_at IS NULL
				""")
			.param("item", itemId)
			.param("course", courseId)
			.query((rs, n) -> new Item(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getBoolean(4),
					rs.getObject(5, LocalDateTime.class), rs.getObject(6, LocalDateTime.class),
					rs.getLong(7), rs.getString(8), rs.getObject(9, Integer.class),
					rs.getObject(10, Integer.class)))
			.optional();
	}

	/**
	 * 재생할 수 있는 콘텐츠만 돌려준다. 영상을 거두었으면(content_path 비움, ADR-0016 10번) 볼 수 없게 된 것과 같다. 하트비트와 종료는
	 * session을 forced로 닫고 404로 알린다.
	 */
	private static Item playable(Optional<Item> item) {
		return item.filter((i) -> LectureVideoController.assetKey(i.contentPath()).isPresent()).orElse(null);
	}

	private record Item(long itemId, long activityId, long courseId, boolean published, LocalDateTime openAt,
			LocalDateTime closeAt, long weekId, String contentPath, Integer durationSec,
			Integer requiredSec) {
	}

	/**
	 * @param creditAuthorizedUntil 적립 유효기한(SC-06-A). 학습 인증을 요구하지 않을 때 시작한 session은 null이고 규칙 v1을 쓴다
	 */
	private record Session(long id, long userId, long itemId, String state, String endReason, LocalDateTime startedAt,
			LocalDateTime lastHeartbeatAt, int intervalSec, int leaseSec, LocalDateTime creditAuthorizedUntil) {

		/** 열려 있다. 하트비트를 받고 lease가 돈다. */
		boolean open() {
			return "active".equals(this.state) || reauthRequired();
		}

		boolean reauthRequired() {
			return "reauth_required".equals(this.state);
		}

		/** 학습 인증에 묶인 session이다(규칙 v2). */
		boolean bound() {
			return this.creditAuthorizedUntil != null;
		}

		String rule() {
			return bound() ? CreditRule.AUTH_BOUND_VERSION : CreditRule.VERSION;
		}

		/** 적립 기준 시각. 첫 하트비트 전에는 시작 시각이다. */
		LocalDateTime baseline() {
			return this.lastHeartbeatAt != null ? this.lastHeartbeatAt : this.startedAt;
		}

	}

	/**
	 * 하트비트 하나의 결과.
	 * @param baseline 다음 기준 시각
	 * @param reauthRequired 이 하트비트 뒤 session이 재인증 대기다
	 * @param authorizedUntil 적립 유효기한. 학습 인증에 묶이지 않은 session이면 null
	 */
	private record Beat(Outcome outcome, int credited, LocalDateTime baseline, boolean reauthRequired,
			LocalDateTime authorizedUntil) {
	}

	/**
	 * @param visible 지금 이 콘텐츠를 볼 수 있다
	 * @param learner 적립 대상(학생)이다
	 */
	private record Access(boolean visible, boolean learner) {

		static final Access NONE = new Access(false, false);

	}

	/** commit한 뒤 알릴 결과나 오류. */
	private record Step<T>(T value, ApiException failure) {

		static <T> Step<T> done(T value) {
			return new Step<>(value, null);
		}

		static <T> Step<T> failed(ApiException failure) {
			return new Step<>(null, failure);
		}

		T orThrow() {
			if (this.failure != null) {
				throw this.failure;
			}
			return this.value;
		}

	}

	/**
	 * 응답 본문과 함께 내보낼 영상 cookie. 권한이 있는 동안에만 채운다.
	 *
	 * @param playback 이번에 cookie를 발급했으면 manifest 경로와 유효 시간, 아니면 null
	 */
	record Granted<T>(T body, Playback playback, List<DeliveryCookie> cookies) {
	}

	/**
	 * @param heartbeatIntervalSec player가 하트비트를 보낼 주기. 서버가 정한다
	 * @param leaseSec 마지막 적립 기준 시각 뒤 이 시간 안에 하트비트가 없으면 session이 닫힌다
	 * @param creditable 이 session이 출석 인정 시간을 적립하는지. 담당자 미리보기는 false
	 * @param creditUntil 적립이 끝나는 시각. 활동의 close_at, 학습 인증을 요구하면 min(grant 만료, close_at, 로그인 session 만료). 없으면 null
	 * @param resumePositionSec 이어보기 위치 힌트
	 */
	record Started(long sessionId, long itemId, int heartbeatIntervalSec, int leaseSec, boolean creditable,
			LocalDateTime creditUntil, Integer resumePositionSec, ItemProgress progress) {
	}

	/**
	 * @param outcome {@link Outcome}의 소문자 이름
	 * @param creditedSec 이 하트비트가 적립한 초
	 * @param creditOpen 다음 하트비트가 적립할 수 있는지. 적립 유효기한이 지났거나, 재인증 대기이거나, 학습자가 아니면 false
	 * @param creditState authorized | reauth_required. reauth_required면 다시 인증할 때까지 적립하지 않는다(SC-06-A)
	 * @param creditUntil 적립이 끝나는 시각. {@link Started#creditUntil}과 같은 규칙이다
	 * @param leaseExpiresAt 이 시각까지 다음 하트비트가 없으면 session이 닫힌다
	 */
	record HeartbeatResult(long sessionId, String outcome, int creditedSec, boolean creditOpen, String creditState,
			LocalDateTime creditUntil, LocalDateTime leaseExpiresAt, int leaseRemainingSec, ItemProgress progress) {
	}

	/**
	 * @param state ended | superseded | lease_expired
	 * @param endReason 저장한 종료 사유. button | unload(사용자 종료), forced(볼 수 없게 되어 서버가 닫음), superseded(다른 곳에서
	 * 시작), lease_expired(하트비트 끊김) 중 하나다. 이미 닫힌 session에 다시 종료를 보내면 처음 사유를 돌려준다
	 * @param creditedSec 이번 종료 요청이 적립한 초
	 */
	record Ended(long sessionId, String state, String endReason, int creditedSec, ItemProgress progress) {
	}

}
