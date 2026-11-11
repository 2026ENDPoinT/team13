package com.stackcorp.astra.identity;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import com.stackcorp.astra.identity.api.UserNames;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class UserNameQueries implements UserNames {

	private final JdbcClient jdbc;

	UserNameQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<Long, String> namesOf(Collection<Long> userIds) {
		Map<Long, String> names = new HashMap<>();
		if (userIds.isEmpty()) {
			return names;
		}
		this.jdbc.sql("SELECT user_id, user_name FROM users WHERE user_id IN (:ids)")
			.param("ids", userIds)
			.query((rs) -> {
				names.put(rs.getLong("user_id"), rs.getString("user_name"));
			});
		return names;
	}

}
