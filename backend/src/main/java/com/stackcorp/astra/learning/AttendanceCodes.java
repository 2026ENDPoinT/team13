package com.stackcorp.astra.learning;

import java.util.Arrays;
import java.util.Optional;

/**
 * 출석부 값의 API 코드와 DB 값. DB는 원본 표기(출석, 지각 …)를 그대로 두고 API는 영문 코드만 주고받는다.
 */
final class AttendanceCodes {

	private AttendanceCodes() {
	}

	/** 출석 상태. 미처리(unmarked)는 판정 전이고 담당자가 고르는 값이 아니다. */
	enum Status {

		PRESENT("present", "출석"), LATE("late", "지각"), ABSENT("absent", "결석"), EXCUSED("excused", "공결"),
		UNMARKED("unmarked", "미처리");

		final String code;

		final String db;

		Status(String code, String db) {
			this.code = code;
			this.db = db;
		}

		/** 담당자가 직접 정할 수 있는 상태. */
		static Optional<Status> decidable(String code) {
			return Arrays.stream(values()).filter((s) -> s != UNMARKED && s.code.equals(code)).findFirst();
		}

		static Status ofDb(String value) {
			return Arrays.stream(values()).filter((s) -> s.db.equals(value)).findFirst().orElse(UNMARKED);
		}

	}

	/** 차시의 출석 방식(attendance_sessions.attendance_method). */
	enum Method {

		SMART("smart", "스마트"), ONLINE("online", "온라인"), MANUAL("manual", "일반");

		final String code;

		final String db;

		Method(String code, String db) {
			this.code = code;
			this.db = db;
		}

		static Optional<Method> of(String code) {
			return Arrays.stream(values()).filter((m) -> m.code.equals(code)).findFirst();
		}

		static String code(String db) {
			return Arrays.stream(values()).filter((m) -> m.db.equals(db)).map((m) -> m.code).findFirst().orElse(null);
		}

	}

	/** 과목 그룹 종류(course_groups.group_type). */
	enum GroupType {

		ACTIVITY("activity", "활동"), SECTION("section", "출석분반");

		final String code;

		final String db;

		GroupType(String code, String db) {
			this.code = code;
			this.db = db;
		}

		static Optional<GroupType> of(String code) {
			return Arrays.stream(values()).filter((t) -> t.code.equals(code)).findFirst();
		}

		static String code(String db) {
			return Arrays.stream(values()).filter((t) -> t.db.equals(db)).map((t) -> t.code).findFirst().orElse(null);
		}

	}

}
