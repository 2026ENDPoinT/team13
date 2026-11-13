package com.stackcorp.astra.identity.sso;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
 * <li>로그인하면 {@link SchoolLoginSucceeded}로 학교 수강과목을 알린다. {@code catalog}가 commit 뒤에 과목과 수강을 맞춘다.</li>
 * </ul>
 */
@Service
class SchoolPasswordLogin {

	private final JdbcClient jdbc;

	private final SessionStore sessions;

	private final LoginEvents events;

	private final BreakGlassProperties breakGlass;

	private final ApplicationEventPublisher publisher;

	SchoolPasswordLogin(JdbcClient jdbc, SessionStore sessions, LoginEvents events, BreakGlassProperties breakGlass,
			ApplicationEventPublisher publisher) {
		this.jdbc = jdbc;
		this.sessions = sessions;
		this.events = events;
		this.breakGlass = breakGlass;
		this.publisher = publisher;
	}

	/**
	 * 실패를 login_events에 남긴 채로 예외를 던지므로 그 예외로는 rollback하지 않는다.
	 * @throws ExternalLoginException 로그인할 수 없는 사용자일 때
	 */
	@Transactional(noRollbackFor = ExternalLoginException.class)
	IssuedSession signIn(SchoolAccount account, String device, String ip, String userAgent) {
		String ref = account.studentId();
		if (ref.equals(this.breakGlass.externalRef())) {
			throw new ExternalLoginException("break_glass_account");
		}
		Account existing = find(ref);
		if (existing == null) {
			if (account.name() == null) {
				throw new ExternalLoginException("profile_missing");
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
			throw new ExternalLoginException("unknown_user");
		}
		if (!"ACTIVE".equals(existing.status())) {
			this.events.record(existing.userId(), null, ip, false, "ECLASS_INACTIVE");
			throw new ExternalLoginException("inactive_account");
		}
		IssuedSession session = this.sessions.create(existing.userId(), device, ip, userAgent);
		this.events.record(existing.userId(), session.sessionId(), ip, true, null);
		this.publisher.publishEvent(new SchoolLoginSucceeded(existing.userId(), account.courses()));
		return session;
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

}
