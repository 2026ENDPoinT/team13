package com.stackcorp.astra.identity;

import java.util.OptionalInt;

import com.stackcorp.astra.identity.api.UserGrades;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

@Service
class UserGradeQueries implements UserGrades {

	private final JdbcClient jdbc;

	UserGradeQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public OptionalInt gradeOf(long userId) {
		return this.jdbc.sql("SELECT grade FROM users WHERE user_id = :id AND grade IS NOT NULL")
			.param("id", userId)
			.query(Integer.class)
			.optional()
			.map(OptionalInt::of)
			.orElseGet(OptionalInt::empty);
	}

}
