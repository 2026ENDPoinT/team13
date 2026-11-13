package com.stackcorp.astra.identity;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.identity.api.UserAudiences;
import com.stackcorp.astra.identity.breakglass.BreakGlassProperties;

/**
 * 대상 판정은 알림마당 설문과 같다. 학생은 user_role이 STUDENT, 교직원은 그 밖의 역할이다. break-glass 계정은 SQL에서 뺀다.
 */
@Service
class UserAudienceQueries implements UserAudiences {

	private final JdbcClient jdbc;

	private final BreakGlassProperties breakGlass;

	UserAudienceQueries(JdbcClient jdbc, BreakGlassProperties breakGlass) {
		this.jdbc = jdbc;
		this.breakGlass = breakGlass;
	}

	@Override
	public List<Long> members(UserAudience audience, long afterUserId, int limit) {
		String role = switch (audience) {
			case ALL -> "";
			case STUDENT -> " AND user_role = 'STUDENT'";
			case STAFF -> " AND user_role <> 'STUDENT'";
		};
		return this.jdbc.sql("SELECT user_id FROM users WHERE user_id > :after AND account_status = 'ACTIVE'"
				+ " AND external_ref <> :breakGlass" + role + " ORDER BY user_id LIMIT :limit")
			.param("after", afterUserId)
			.param("breakGlass", this.breakGlass.externalRef() == null ? "" : this.breakGlass.externalRef())
			.param("limit", limit)
			.query(Long.class)
			.list();
	}

}
