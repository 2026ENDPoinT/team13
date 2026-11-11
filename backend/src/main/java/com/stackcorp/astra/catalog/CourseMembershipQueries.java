package com.stackcorp.astra.catalog;

import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.catalog.api.CourseMemberships;

/**
 * 판정은 {@link MembershipRule}이다. 폐강한 과목에서는 누구도 역할이 없다.
 */
@Service
class CourseMembershipQueries implements CourseMemberships {

	private static final String ROLE = "SELECT CASE WHEN " + MembershipRule.instructorOf(":course", ":user")
			+ " THEN 1 WHEN " + MembershipRule.studentOf(":course", ":user") + " THEN 2 ELSE 0 END";

	private final JdbcClient jdbc;

	CourseMembershipQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Optional<CourseRole> roleOf(long userId, long courseId) {
		int role = this.jdbc.sql(ROLE).param("course", courseId).param("user", userId).query(Integer.class).single();
		return switch (role) {
			case 1 -> Optional.of(CourseRole.INSTRUCTOR);
			case 2 -> Optional.of(CourseRole.STUDENT);
			default -> Optional.empty();
		};
	}

}
