package com.stackcorp.astra.identity.sso;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.identity.api.ExternalLoginException;
import com.stackcorp.astra.identity.api.SchoolLoginSucceeded;
import com.stackcorp.astra.identity.api.SchoolPasswordVerifier.SchoolAccount;
import com.stackcorp.astra.identity.breakglass.BreakGlassProperties;
import com.stackcorp.astra.identity.breakglass.LoginEvents;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;

/**
 * 학교 e-Class가 학번·비밀번호를 확인한 학생을 내부 사용자로 찾거나 만들고 session을 만든다. 학교 IdP 로그인({@link SsoLogin})과
 * 달리 처음 들어온 학번은 학생으로 만든다. 학교 로그인이 그 학번의 학생이라는 근거이기 때문이다.
 * <ul>
 * <li>있는 사용자는 학교 화면에서 읽은 이름·학과·학년으로 갱신한다. 읽지 못한 값은 그대로 둔다.</li>
 * <li>break-glass 계정과 ACTIVE가 아닌 계정은 들어오지 못한다.</li>
 * <li>처음 들어온 학번인데 이름을 읽지 못하면 사용자를 만들지 않는다. 이름 없는 사용자를 지어내지 않는다.</li>
 * <li>로그인하면 commit하고 DB 연결을 돌려준 뒤 {@link SchoolLoginSucceeded}로 학교 수강과목과 강의실 손잡이를 알린다.
 * {@code catalog}가 과목과 수강을 맞추고, {@code learning}이 강의실 학습활동 목록을 가져온다.</li>
 * </ul>
 */
@Service
class SchoolPasswordLogin {

	private final JdbcClient jdbc;

	private final SessionStore sessions;

	private final LoginEvents events;

	private final BreakGlassProperties breakGlass;

	private final ApplicationEventPublisher publisher;

	private final TransactionTemplate transaction;

	SchoolPasswordLogin(JdbcClient jdbc, SessionStore sessions, LoginEvents events, BreakGlassProperties breakGlass,
			ApplicationEventPublisher publisher, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.sessions = sessions;
		this.events = events;
		this.breakGlass = breakGlass;
		this.publisher = publisher;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	/**
	 * 로그인 transaction은 이 method 안에서 끝난다. 거절도 login_events에 남긴 채로 commit한 뒤 예외를 던진다. 로그인하면 commit하고
	 * DB 연결을 돌려준 다음 {@link SchoolLoginSucceeded}를 발행한다. 받는 쪽(과목 동기화, 학교 강의실 가져오기를 잠깐 기다림)이 이
	 * thread에서 도는 동안 로그인이 DB 연결을 쥐고 있지 않게 하기 위해서다. 쥐고 있으면 몰린 로그인이 연결 pool을 다 쓰고, 과목 동기화가
	 * 새 transaction의 연결을 얻지 못한 채 모두 멈춘다.
	 * @throws ExternalLoginException 로그인할 수 없는 사용자일 때
	 */
	IssuedSession signIn(SchoolAccount account, String device, String ip, String userAgent) {
		SignedIn signedIn = this.transaction.execute((status) -> signInTransaction(account, device, ip, userAgent));
		if (signedIn.refusal() != null) {
			throw new ExternalLoginException(signedIn.refusal());
		}
		this.publisher.publishEvent(new SchoolLoginSucceeded(signedIn.userId(), account.courses(), account.rooms()));
		return signedIn.session();
	}

	private SignedIn signInTransaction(SchoolAccount account, String device, String ip, String userAgent) {
		String ref = account.studentId();
		if (ref.equals(this.breakGlass.externalRef())) {
			return SignedIn.refused("break_glass_account");
		}
		Account existing = find(ref);
		if (existing == null) {
			if (account.name() == null) {
				return SignedIn.refused("profile_missing");
			}
			// 같은 학번이 동시에 처음 로그인하면 한쪽은 아무것도 넣지 않고 아래에서 그 행을 읽는다.
			this.jdbc.sql("""
					INSERT INTO users (external_ref, user_name, user_role, department_name, grade, last_synced_at)
					VALUES (:ref, :name, 'STUDENT', :department, :grade, CURRENT_TIMESTAMP(3))
					ON DUPLICATE KEY UPDATE user_id = user_id
					""")
				.param("ref", ref)
				.param("name", account.name())
				.param("department", account.departmentName())
				.param("grade", account.grade())
				.update();
			existing = find(ref);
		}
		else if ("ACTIVE".equals(existing.status())) {
			this.jdbc.sql("""
					UPDATE users
					   SET user_name = COALESCE(:name, user_name),
					       department_name = COALESCE(:department, department_name),
					       grade = COALESCE(:grade, grade),
					       last_synced_at = CURRENT_TIMESTAMP(3)
					 WHERE user_id = :id
					""")
				.param("name", account.name())
				.param("department", account.departmentName())
				.param("grade", account.grade())
				.param("id", existing.userId())
				.update();
		}
		if (existing == null) {
			return SignedIn.refused("unknown_user");
		}
		if (!"ACTIVE".equals(existing.status())) {
			this.events.record(existing.userId(), null, ip, false, "ECLASS_INACTIVE");
			return SignedIn.refused("inactive_account");
		}
		IssuedSession session = this.sessions.create(existing.userId(), device, ip, userAgent);
		this.events.record(existing.userId(), session.sessionId(), ip, true, null);
		return new SignedIn(existing.userId(), session, null);
	}

	/** 학교가 학번·비밀번호를 받지 않았을 때. 있는 사용자만 기록한다. */
	@Transactional
	void rejected(String studentId, String ip) {
		Account existing = find(studentId);
		if (existing != null) {
			this.events.record(existing.userId(), null, ip, false, "ECLASS_REJECTED");
		}
	}

	private Account find(String ref) {
		return this.jdbc.sql("SELECT user_id, account_status FROM users WHERE external_ref = :ref")
			.param("ref", ref)
			.query((rs, n) -> new Account(rs.getLong("user_id"), rs.getString("account_status")))
			.optional()
			.orElse(null);
	}

	private record Account(long userId, String status) {
	}

	private record SignedIn(long userId, IssuedSession session, String refusal) {

		static SignedIn refused(String refusal) {
			return new SignedIn(0, null, refusal);
		}

	}

}
