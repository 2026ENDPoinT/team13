package com.stackcorp.astra.identity.stepup;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.LearningAuthGrants;
import com.stackcorp.astra.identity.api.SecondFactorChannel;
import com.stackcorp.astra.identity.api.SecondFactorChannel.Pending;
import com.stackcorp.astra.identity.api.SecondFactorChannel.Start;
import com.stackcorp.astra.identity.session.SessionProperties;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

/**
 * 학습 인증 challenge와 grant(SC-06-A, ADR-0029). challenge는 로그인 session 하나에 묶이고 그 session에서만 확인할 수 있다.
 * <ul>
 * <li>상태: PENDING → CONFIRMED | LOCKED | EXPIRED | CANCELLED. 확인은 challenge 행을 잠근 뒤 PENDING이고 만료 전이며 시도가 한도
 * 안일 때만 한 번 CONFIRMED가 된다. 같은 transaction에서 grant를 쓴다(IDENTITY-05).</li>
 * <li>확인에 실패할 때마다 시도 수를 올리고, 한도에 닿으면 LOCKED다. 잠긴 challenge는 맞는 근거가 와도 통과하지 않는다.</li>
 * <li>사용자의 최근 실패 합이 한도에 닿으면 새 challenge를 만들지 못한다. 새 challenge는 같은 session의 PENDING challenge를
 * 취소한다.</li>
 * <li>grant는 로그인 session과 목적 LEARNING_AUTH의 session_step_ups 행이다. 폐기는 만료를 지금으로 당긴다.</li>
 * </ul>
 * 시각은 모두 DB 시계다. challenge ID(auth_seq)는 추측할 수 없는 난수이고 다른 사람의 것이면 404다.
 */
@Service
class LearningAuth implements LearningAuthGrants {

	private static final SecureRandom RANDOM = new SecureRandom();

	private final JdbcClient jdbc;

	private final TransactionTemplate tx;

	private final TransactionTemplate readCommitted;

	private final StepUps stepUps;

	private final List<SecondFactorChannel> channels;

	private final StepUpProperties properties;

	private final SessionProperties sessions;

	private final AuditLog audit;

	LearningAuth(JdbcClient jdbc, PlatformTransactionManager transactions, StepUps stepUps,
			List<SecondFactorChannel> channels, StepUpProperties properties, SessionProperties sessions,
			AuditLog audit) {
		this.jdbc = jdbc;
		this.tx = new TransactionTemplate(transactions);
		this.readCommitted = new TransactionTemplate(transactions);
		this.readCommitted.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.stepUps = stepUps;
		this.channels = List.copyOf(channels);
		this.properties = properties;
		this.sessions = sessions;
		this.audit = audit;
	}

	@Override
	public Optional<Grant> current(long sessionId) {
		return this.stepUps.learningGrant(sessionId, this.sessions.absoluteTimeout()).map(Grant::new);
	}

	List<String> availableChannels() {
		return this.channels.stream().filter(SecondFactorChannel::available).map(SecondFactorChannel::name).toList();
	}

	Status status(Actor actor) {
		LocalDateTime now = now();
		Optional<LocalDateTime> until = current(actor.sessionId()).map(Grant::expiresAt).filter(now::isBefore);
		return new Status(until.isPresent(), until.orElse(null), availableChannels(), now);
	}

	/**
	 * @throws ApiException 쓸 수 없는 수단이면 400, 최근 실패가 한도에 닿았으면 429 {@code learning_auth_locked}
	 */
	Created create(Actor actor, String channelName) {
		SecondFactorChannel channel = this.channels.stream()
			.filter((c) -> c.name().equals(channelName) && c.available())
			.findFirst()
			.orElseThrow(() -> ApiException.invalid("쓸 수 없는 인증 수단입니다."));
		if (recentFailures(actor.userId()) >= this.properties.lockoutAttempts()) {
			this.audit.record(actor.userId(), actor.sessionId(), "learning_auth.challenge_create", null, null, null,
					"locked");
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "learning_auth_locked",
					"인증에 여러 번 실패했습니다. 잠시 뒤 다시 시도해 주세요.");
		}
		Start start = channel.start(actor.userId(), actor.sessionId());
		String challengeId = randomId();
		return this.readCommitted.execute((status) -> {
			this.jdbc.sql("""
					UPDATE secondary_auth_challenges SET challenge_status = 'CANCELLED'
					 WHERE session_id = :session AND challenge_status = 'PENDING'
					""").param("session", actor.sessionId()).update();
			this.jdbc.sql("""
					INSERT INTO secondary_auth_challenges (auth_seq, user_id, session_id, auth_div, auth_way, destination_type,
					                                       destination_label, code_hash, max_attempts, expires_at)
					VALUES (:seq, :user, :session, 1, :way, 'USER', :label, :hash, :attempts,
					        TIMESTAMPADD(SECOND, :seconds, CURRENT_TIMESTAMP(3)))
					""")
				.param("seq", challengeId)
				.param("user", actor.userId())
				.param("session", actor.sessionId())
				.param("way", channel.authWay())
				.param("label", start.destinationLabel())
				.param("hash", start.codeHash())
				.param("attempts", this.properties.challengeAttempts())
				.param("seconds", this.properties.challenge().toSeconds())
				.update();
			Challenge created = find(challengeId, actor, false).orElseThrow();
			this.audit.success(actor.userId(), actor.sessionId(), "learning_auth.challenge_create",
					"secondary_auth_challenge", created.id(), null);
			return new Created(challengeId, channel.name(), "pending", created.expiresAt(), start.destinationLabel(),
					start.redirectPath());
		});
	}

	/**
	 * 확인한다. 실패와 잠금도 시도 수를 commit한 뒤 오류로 알린다.
	 * @param code 코드가 있는 수단에서 사용자가 입력한 값. 없으면 null
	 */
	Verified verify(Actor actor, String challengeId, String code) {
		Outcome outcome = this.tx.execute((status) -> doVerify(actor, challengeId, code));
		return switch (outcome) {
			case CONFIRMED -> new Verified("confirmed", current(actor.sessionId()).map(Grant::expiresAt).orElse(null));
			case FAILED -> throw new ApiException(HttpStatus.BAD_REQUEST, "verification_failed",
					"인증을 확인하지 못했습니다.");
			case LOCKED -> throw new ApiException(HttpStatus.CONFLICT, "challenge_locked",
					"확인에 여러 번 실패해 이 인증은 더 쓸 수 없습니다. 다시 시작해 주세요.");
			case USED -> throw new ApiException(HttpStatus.CONFLICT, "challenge_used", "이미 확인한 인증입니다.");
			case EXPIRED -> throw new ApiException(HttpStatus.CONFLICT, "challenge_expired",
					"인증 시간이 지났습니다. 다시 시작해 주세요.");
			case CANCELLED -> throw new ApiException(HttpStatus.CONFLICT, "challenge_cancelled",
					"취소된 인증입니다. 다시 시작해 주세요.");
		};
	}

	/** grant를 지금 끝내고 이 session의 진행 중인 challenge를 취소한다. 몇 번 불러도 결과가 같다. */
	void revoke(Actor actor) {
		this.tx.executeWithoutResult((status) -> {
			int revoked = this.stepUps.revokeLearningGrant(actor.sessionId());
			this.jdbc.sql("""
					UPDATE secondary_auth_challenges SET challenge_status = 'CANCELLED'
					 WHERE session_id = :session AND challenge_status = 'PENDING'
					""").param("session", actor.sessionId()).update();
			this.audit.record(actor.userId(), actor.sessionId(), "learning_auth.revoke", "user_session",
					actor.sessionId(), null, revoked > 0 ? AuditLog.OK : "none");
		});
	}

	private Outcome doVerify(Actor actor, String challengeId, String code) {
		Challenge challenge = find(challengeId, actor, true).orElseThrow(ApiException::notFound);
		Outcome outcome = switch (challenge.status()) {
			case "CONFIRMED" -> Outcome.USED;
			case "LOCKED" -> Outcome.LOCKED;
			case "EXPIRED" -> Outcome.EXPIRED;
			case "CANCELLED" -> Outcome.CANCELLED;
			default -> pending(challenge, actor, code);
		};
		this.audit.record(actor.userId(), actor.sessionId(), "learning_auth.challenge_verify",
				"secondary_auth_challenge", challenge.id(), null,
				outcome == Outcome.CONFIRMED ? AuditLog.OK : outcome.name().toLowerCase());
		return outcome;
	}

	private Outcome pending(Challenge challenge, Actor actor, String code) {
		if (!challenge.now().isBefore(challenge.expiresAt())) {
			setStatus(challenge, "EXPIRED");
			return Outcome.EXPIRED;
		}
		SecondFactorChannel channel = this.channels.stream()
			.filter((c) -> c.authWay() == challenge.authWay() && c.available())
			.findFirst()
			.orElse(null);
		boolean verified = channel != null && channel.verify(
				new Pending(actor.userId(), actor.sessionId(), challenge.createdAt(), challenge.codeHash()), code);
		if (verified) {
			this.jdbc.sql("""
					UPDATE secondary_auth_challenges SET challenge_status = 'CONFIRMED', confirmed_at = CURRENT_TIMESTAMP(3)
					 WHERE challenge_id = :id AND challenge_status = 'PENDING'
					""").param("id", challenge.id()).update();
			this.stepUps.grant(actor.sessionId(), StepUps.LEARNING_AUTH, StepUps.METHOD_SECONDARY_AUTH,
					this.properties.learningGrant());
			return Outcome.CONFIRMED;
		}
		// 상태를 먼저 정한다. MySQL은 SET을 왼쪽부터 적용하므로 attempt_count는 아직 이전 값이다.
		this.jdbc.sql("""
				UPDATE secondary_auth_challenges
				   SET challenge_status = CASE WHEN attempt_count + 1 >= max_attempts THEN 'LOCKED' ELSE challenge_status END,
				       attempt_count = attempt_count + 1
				 WHERE challenge_id = :id AND challenge_status = 'PENDING'
				""").param("id", challenge.id()).update();
		return challenge.attempts() + 1 >= challenge.maxAttempts() ? Outcome.LOCKED : Outcome.FAILED;
	}

	private void setStatus(Challenge challenge, String status) {
		this.jdbc.sql("""
				UPDATE secondary_auth_challenges SET challenge_status = :status
				 WHERE challenge_id = :id AND challenge_status = 'PENDING'
				""").param("status", status).param("id", challenge.id()).update();
	}

	private int recentFailures(long userId) {
		return this.jdbc.sql("""
				SELECT COALESCE(SUM(attempt_count), 0) FROM secondary_auth_challenges
				 WHERE user_id = :user AND challenge_status <> 'CONFIRMED'
				   AND created_at > DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL :seconds SECOND)
				""")
			.param("user", userId)
			.param("seconds", this.properties.lockoutWindow().toSeconds())
			.query(Integer.class)
			.single();
	}

	private Optional<Challenge> find(String challengeId, Actor actor, boolean lock) {
		if (challengeId == null || !challengeId.matches("[A-Za-z0-9_-]{43}")) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				SELECT challenge_id, auth_way, code_hash, attempt_count, max_attempts, challenge_status, expires_at, created_at,
				       CURRENT_TIMESTAMP(3)
				  FROM secondary_auth_challenges
				 WHERE auth_seq = :seq AND user_id = :user AND session_id = :session
				""" + (lock ? " FOR UPDATE" : ""))
			.param("seq", challengeId)
			.param("user", actor.userId())
			.param("session", actor.sessionId())
			.query((rs, n) -> new Challenge(rs.getLong(1), rs.getInt(2), rs.getString(3), rs.getInt(4), rs.getInt(5),
					rs.getString(6), rs.getObject(7, LocalDateTime.class), rs.getObject(8, LocalDateTime.class),
					rs.getObject(9, LocalDateTime.class)))
			.optional();
	}

	private LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	private static String randomId() {
		byte[] value = new byte[32];
		RANDOM.nextBytes(value);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
	}

	private enum Outcome {

		CONFIRMED, FAILED, LOCKED, USED, EXPIRED, CANCELLED

	}

	private record Challenge(long id, int authWay, String codeHash, int attempts, int maxAttempts, String status,
			LocalDateTime expiresAt, LocalDateTime createdAt, LocalDateTime now) {
	}

	/**
	 * @param granted 지금 유효한 grant가 있다
	 * @param expiresAt grant가 끝나는 시각. 없으면 null
	 * @param channels 이 실행에서 쓸 수 있는 2차 인증 수단
	 */
	record Status(boolean granted, LocalDateTime expiresAt, List<String> channels, LocalDateTime serverTime) {
	}

	/**
	 * @param challengeId 확인할 때 쓰는 ID
	 * @param destinationLabel 마스킹한 발송 대상. 없는 수단이면 null
	 * @param redirectPath 사용자를 보낼 경로({@code idp}는 {@code /api/auth/reauth}). 없으면 null
	 */
	record Created(String challengeId, String channel, String status, LocalDateTime expiresAt,
			String destinationLabel, String redirectPath) {
	}

	/**
	 * @param grantExpiresAt 새 grant가 끝나는 시각
	 */
	record Verified(String status, LocalDateTime grantExpiresAt) {
	}

}
