package com.stackcorp.astra.learning;

import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Component;

/**
 * 과목 주차. 활동을 만들 때 그 주차가 없으면 만든다. 같은 주차를 동시에 만들면 유일 제약으로 하나만 남긴다(SC-05).
 */
@Component
class CourseWeeks {

	private final JdbcClient jdbc;

	CourseWeeks(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return 주차 ID */
	long weekId(long courseId, int weekNo) {
		Optional<Long> existing = find(courseId, weekNo, "");
		if (existing.isPresent()) {
			return existing.get();
		}
		try {
			GeneratedKeyHolder key = new GeneratedKeyHolder();
			this.jdbc.sql("INSERT INTO course_weeks (course_id, week_no) VALUES (:course, :no)")
				.param("course", courseId)
				.param("no", weekNo)
				.update(key, "course_week_id");
			return key.getKey().longValue();
		}
		catch (DuplicateKeyException ex) {
			// 다른 transaction이 방금 만든 행은 snapshot 밖이라 잠금 읽기로 찾는다.
			return find(courseId, weekNo, " FOR SHARE").orElseThrow(() -> ex);
		}
	}

	private Optional<Long> find(long courseId, int weekNo, String lock) {
		return this.jdbc.sql("SELECT course_week_id FROM course_weeks WHERE course_id = :course AND week_no = :no" + lock)
			.param("course", courseId)
			.param("no", weekNo)
			.query(Long.class)
			.optional();
	}

}
