package com.stackcorp.astra.identity;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.identity.api.UserSearch;

@Service
class UserSearchQueries implements UserSearch {

	private final JdbcClient jdbc;

	UserSearchQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Matches search(Collection<Long> userIds, String nameQuery, int offset, int limit) {
		if (userIds.isEmpty()) {
			return new Matches(List.of(), 0);
		}
		String where = " FROM users WHERE user_id IN (:ids) AND account_status = 'ACTIVE'"
				+ (nameQuery == null ? "" : " AND user_name LIKE CONCAT('%', :q, '%')");
		String pattern = nameQuery == null ? null : escapeLike(nameQuery);
		var count = this.jdbc.sql("SELECT COUNT(*)" + where).param("ids", userIds);
		var page = this.jdbc.sql("SELECT user_id, user_name" + where + " ORDER BY user_name, user_id LIMIT :limit OFFSET :offset")
			.param("ids", userIds)
			.param("limit", limit)
			.param("offset", offset);
		if (pattern != null) {
			count = count.param("q", pattern);
			page = page.param("q", pattern);
		}
		long total = count.query(Long.class).single();
		List<Match> items = page.query((rs, n) -> new Match(rs.getLong(1), rs.getString(2))).list();
		return new Matches(items, total);
	}

	/** LIKE 안에서 사용자 입력의 %, _, \를 글자 그대로 비교하게 escape한다. */
	private static String escapeLike(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
