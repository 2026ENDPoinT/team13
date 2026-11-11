package com.stackcorp.astra.catalog.api;

import java.time.LocalDate;
import java.util.List;

/**
 * 학사일정(academic_calendar_events). 기관 전체 일정이라 공개한 일정은 로그인한 사용자 누구나 본다. 쿼리 한 번이다.
 */
public interface AcademicCalendar {

	/** 한 번에 돌려주는 일정 수 상한. */
	int MAX_EVENTS = 200;

	/**
	 * 기간이 [from, to]와 겹치는 공개 일정. 시작일, ID 순서다. {@link #MAX_EVENTS}개까지다.
	 */
	List<AcademicEvent> overlapping(LocalDate from, LocalDate to);

	/**
	 * @param type ACADEMIC|REGISTRATION|EXAM|HOLIDAY
	 * @param endDate 마지막 날(당일 일정이면 시작일과 같다)
	 */
	record AcademicEvent(long eventId, String title, String type, LocalDate startDate, LocalDate endDate) {
	}

}
