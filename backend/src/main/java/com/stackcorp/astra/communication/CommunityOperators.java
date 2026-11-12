package com.stackcorp.astra.communication;

import com.stackcorp.astra.identity.api.Actor;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 알림마당 운영자(ADR-0035). 자료실 자료를 올리고 고치고 지우며, 모든 1:1 문의를 보고 답한다. 기관 역할(users.user_role)이나 과목
 * 관계가 아니라 community_operators의 해제하지 않은 행으로만 정한다. 이 행을 만드는 API는 없고 운영 절차로만 넣는다. 요청마다 이 표를
 * 읽으며 session에 담아 두지 않는다. break-glass 계정은 행이 있어도 운영자가 아니다(ADR-0004 6번). 기본은 거부다.
 */
@Repository
class CommunityOperators {

	private final JdbcClient jdbc;

	CommunityOperators(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	boolean is(Actor actor) {
		if (actor.breakGlass()) {
			return false;
		}
		return this.jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM community_operators WHERE user_id = :user AND revoked_at IS NULL)
				""").param("user", actor.userId()).query(Boolean.class).single();
	}

}
