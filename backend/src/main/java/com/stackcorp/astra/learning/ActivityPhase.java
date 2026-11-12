package com.stackcorp.astra.learning;

import java.time.LocalDateTime;

/**
 * 활동의 지금 구간. 과제·퀴즈·설문 화면의 phase와 같은 이름과 경계를 쓴다(SC-06의 {@code openAt <= now < closeAt}). 시각은 DB 시계다.
 * <ul>
 * <li>not_open: 시작 일시 전</li>
 * <li>open: 시작했고 마감 전. 마감 일시가 없는 활동(강의 자료)은 시작한 뒤 계속 open이다</li>
 * <li>late: 마감이 지났고 지각 마감 전. 지각 제출을 받는 과제에만 있다</li>
 * <li>closed: 마감(지각을 받으면 지각 마감)이 지났다</li>
 * </ul>
 * 공개 여부는 따로 내보낸다. 담당자에게 보이는 비공개 활동도 기간으로 구간을 정한다.
 */
final class ActivityPhase {

	private ActivityPhase() {
	}

	/** @param lateUntil 지각 마감. 지각을 받지 않으면 null */
	static String of(LocalDateTime openAt, LocalDateTime closeAt, LocalDateTime lateUntil, LocalDateTime now) {
		if (openAt != null && now.isBefore(openAt)) {
			return "not_open";
		}
		if (closeAt == null || now.isBefore(closeAt)) {
			return "open";
		}
		if (lateUntil != null && now.isBefore(lateUntil)) {
			return "late";
		}
		return "closed";
	}

}
