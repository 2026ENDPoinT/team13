package com.stackcorp.astra.identity;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.identity.api.UserDirectory;

@Service
class UserDirectoryQueries implements UserDirectory {

	private final JdbcClient jdbc;

	UserDirectoryQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<Long, UserCard> find(Collection<Long> userIds) {
		Map<Long, UserCard> cards = new LinkedHashMap<>();
		if (userIds.isEmpty()) {
			return cards;
		}
		this.jdbc.sql("SELECT user_id, user_name, external_ref FROM users WHERE user_id IN (:ids)")
			.param("ids", userIds)
			.query((rs, n) -> new UserCard(rs.getLong(1), rs.getString(2), rs.getString(3)))
			.list()
			.forEach((card) -> cards.put(card.userId(), card));
		return cards;
	}

}
