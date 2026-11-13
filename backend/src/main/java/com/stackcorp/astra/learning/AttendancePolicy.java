package com.stackcorp.astra.learning;

import com.stackcorp.astra.shared.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 과목별 출석 정책(attendance_settings). 학교가 아직 정하지 않은 값이라 과목마다 담당자가 정하고, 정하지 않은 과목은 기본값을 쓴다
 * (ADR-0030, architecture.md 12절).
 * <ul>
 * <li>지각 기준 {@value #DEFAULT_LATE_AFTER_MINUTES}분: 스마트출석 회차를 연 뒤 이 시간이 지나 제출하면 지각이다.</li>
 * <li>회차 진행 시간 {@value #DEFAULT_ROUND_MINUTES}분: 회차를 열 때 시간을 정하지 않으면 이 시간 뒤에 마감한다.</li>
 * <li>지각 환산 {@value #DEFAULT_LATES_PER_ABSENCE}: 지각 몇 번을 결석 한 번으로 칠지. 0이면 환산하지 않는다.</li>
 * </ul>
 * 회차는 연 순간의 값을 회차 행(late_at, ends_at)에 고정하므로, 설정을 바꿔도 진행 중인 회차의 판정은 바뀌지 않는다.
 */
@Component
class AttendancePolicy {

	static final int DEFAULT_LATE_AFTER_MINUTES = 5;

	static final int DEFAULT_ROUND_MINUTES = 10;

	static final int DEFAULT_LATES_PER_ABSENCE = 0;

	static final int MAX_ROUND_MINUTES = 240;

	static final int MAX_LATES_PER_ABSENCE = 10;

	private final JdbcClient jdbc;

	AttendancePolicy(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Settings of(long courseId) {
		return this.jdbc.sql("""
				SELECT late_after_minutes, round_minutes, lates_per_absence, row_version FROM attendance_settings
				 WHERE course_id = :course
				""")
			.param("course", courseId)
			.query((rs, n) -> new Settings(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)))
			.optional()
			.orElse(Settings.DEFAULTS);
	}

	/**
	 * 정책을 바꾼다. 처음 바꿀 때는 version 0을 보낸다. 행은 기본값과 version 0으로 만든 뒤 잠가서 비교하므로, 동시에 처음 저장해도
	 * 하나만 이기고 나머지는 409다.
	 */
	@Transactional
	Settings update(long userId, long courseId, int expectedVersion, Values values) {
		this.jdbc.sql("""
				INSERT INTO attendance_settings (course_id, late_after_minutes, round_minutes, lates_per_absence, row_version)
				VALUES (:course, :late, :round, :lates, 0)
				ON DUPLICATE KEY UPDATE course_id = course_id
				""")
			.param("course", courseId)
			.param("late", DEFAULT_LATE_AFTER_MINUTES)
			.param("round", DEFAULT_ROUND_MINUTES)
			.param("lates", DEFAULT_LATES_PER_ABSENCE)
			.update();
		int current = this.jdbc.sql("SELECT row_version FROM attendance_settings WHERE course_id = :course FOR UPDATE")
			.param("course", courseId)
			.query(Integer.class)
			.single();
		if (current != expectedVersion) {
			throw Versions.conflict();
		}
		this.jdbc.sql("""
				UPDATE attendance_settings
				   SET late_after_minutes = :late, round_minutes = :round, lates_per_absence = :lates, updated_by = :user,
				       row_version = row_version + 1
				 WHERE course_id = :course
				""")
			.param("late", values.lateAfterMinutes())
			.param("round", values.roundMinutes())
			.param("lates", values.latesPerAbsence())
			.param("user", userId)
			.param("course", courseId)
			.update();
		return new Settings(values.lateAfterMinutes(), values.roundMinutes(), values.latesPerAbsence(), current + 1);
	}

	/** 요청 본문을 검사한다. 지각 기준은 1분 이상이고 회차 진행 시간을 넘지 않는다. 둘이 같으면 지각이 없다. */
	static Values validated(Integer lateAfterMinutes, Integer roundMinutes, Integer latesPerAbsence) {
		if (roundMinutes == null || roundMinutes < 1 || roundMinutes > MAX_ROUND_MINUTES) {
			throw ApiException.invalid("roundMinutes는 1 이상 " + MAX_ROUND_MINUTES + " 이하여야 합니다.");
		}
		if (lateAfterMinutes == null || lateAfterMinutes < 1 || lateAfterMinutes > roundMinutes) {
			throw ApiException.invalid("lateAfterMinutes는 1 이상 roundMinutes 이하여야 합니다.");
		}
		if (latesPerAbsence == null || latesPerAbsence < 0 || latesPerAbsence > MAX_LATES_PER_ABSENCE) {
			throw ApiException.invalid("latesPerAbsence는 0 이상 " + MAX_LATES_PER_ABSENCE + " 이하여야 합니다.");
		}
		return new Values(lateAfterMinutes, roundMinutes, latesPerAbsence);
	}

	record Values(int lateAfterMinutes, int roundMinutes, int latesPerAbsence) {
	}

	/**
	 * @param version If-Match로 보낼 값. 정하지 않은 과목은 0
	 */
	record Settings(int lateAfterMinutes, int roundMinutes, int latesPerAbsence, int version) {

		static final Settings DEFAULTS = new Settings(DEFAULT_LATE_AFTER_MINUTES, DEFAULT_ROUND_MINUTES,
				DEFAULT_LATES_PER_ABSENCE, 0);

	}

}
