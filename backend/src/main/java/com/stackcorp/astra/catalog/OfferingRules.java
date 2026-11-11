package com.stackcorp.astra.catalog;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.stackcorp.astra.catalog.CatalogQueries.OfferingRow;
import com.stackcorp.astra.identity.api.UserRole;

/**
 * 비정규 과정 신청 규칙(ADR-0026). 상세 화면의 미리보기와 신청 명령이 같은 판정을 쓴다. 미리보기 결과는 표시용이고, 신청 명령은
 * 과정 행을 잠근 뒤 다시 판정한다(SC-02). 시각은 언제나 DB 시각이다(SC-06).
 */
final class OfferingRules {

	static final String UPCOMING = "UPCOMING";

	static final String OPEN = "OPEN";

	static final String CLOSED = "CLOSED";

	static final String NOT_OPEN_YET = "not_open_yet";

	static final String APPLICATION_CLOSED = "application_closed";

	static final String NOT_TARGET = "not_target";

	static final String OWN_COURSE = "own_course";

	static final String ALREADY_APPLIED = "already_applied";

	static final String SEAT_FULL = "seat_full";

	private OfferingRules() {
	}

	/**
	 * 접수 상태. 과정과 과목이 OPEN이고 {@code openAt <= now < closeAt}이면 OPEN이다.
	 */
	static String state(OfferingRow offering, LocalDateTime now) {
		if (!OPEN.equals(offering.offeringStatus()) || !OPEN.equals(offering.courseStatus())) {
			return CLOSED;
		}
		if (now.isBefore(offering.applicationOpenAt())) {
			return UPCOMING;
		}
		return now.isBefore(offering.applicationCloseAt()) ? OPEN : CLOSED;
	}

	/**
	 * 사용자 구분을 신청대상 코드로 바꾼다. 졸업생(4)과 대학원생(6)은 identity가 구분하지 않아 어떤 사용자도 그 코드로 판정되지
	 * 않는다.
	 */
	static String audienceOf(UserRole role) {
		return switch (role) {
			case PROFESSOR -> "1";
			case STAFF -> "2";
			case STUDENT -> "3";
			case TA -> "5";
		};
	}

	/**
	 * 신청할 수 없는 이유. 비어 있으면 신청할 수 있다. 신청대상이 하나도 없는 과정은 누구도 신청하지 못한다(기본 거부).
	 * @param active 같은 과정에 활성(PENDING, WAITLISTED, ENROLLED) 신청이 있는지
	 */
	static List<String> refusals(OfferingRow offering, LocalDateTime now, UserRole role, List<String> audiences,
			boolean instructor, boolean active) {
		List<String> reasons = new ArrayList<>();
		String state = state(offering, now);
		if (UPCOMING.equals(state)) {
			reasons.add(NOT_OPEN_YET);
		}
		else if (CLOSED.equals(state)) {
			reasons.add(APPLICATION_CLOSED);
		}
		if (!audiences.contains(audienceOf(role))) {
			reasons.add(NOT_TARGET);
		}
		if (instructor) {
			reasons.add(OWN_COURSE);
		}
		if (active) {
			reasons.add(ALREADY_APPLIED);
		}
		if (autoApproval(offering) && full(offering)) {
			reasons.add(SEAT_FULL);
		}
		return reasons;
	}

	/** 신청 즉시 수강이 확정되는 과정. 그 밖(MANUAL)은 담당자가 승인한다. */
	static boolean autoApproval(OfferingRow offering) {
		return !"MANUAL".equals(offering.approvalMode());
	}

	static boolean full(OfferingRow offering) {
		return offering.capacity() != null && offering.held() >= offering.capacity();
	}

	/**
	 * 학생이 스스로 취소할 수 있는지. 승인 전 신청은 언제나 취소한다. 확정된 수강은 과정이 취소를 허용하고 취소 마감 전이어야 한다.
	 * 청강은 상태와 관계없이 취소한다. 학사 원장(REGISTRAR)은 여기서 바꾸지 않는다.
	 */
	static boolean cancellable(String kind, String status, Boolean cancelAllowed, LocalDateTime cancelCloseAt,
			LocalDateTime now) {
		return switch (kind) {
			case "NONREGULAR" -> switch (status) {
				case "PENDING", "WAITLISTED" -> true;
				case "ENROLLED" -> Boolean.TRUE.equals(cancelAllowed) && (cancelCloseAt == null || now.isBefore(cancelCloseAt));
				default -> false;
			};
			case "AUDIT" -> "REQUESTED".equals(status) || "ENROLLED".equals(status);
			default -> false;
		};
	}

}
