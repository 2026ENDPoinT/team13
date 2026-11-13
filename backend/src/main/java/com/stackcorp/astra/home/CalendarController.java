package com.stackcorp.astra.home;

import java.time.LocalDate;

import com.stackcorp.astra.home.CalendarService.CalendarView;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 메인 캘린더(EP-0178, main_calendar_data1·2의 대체 계약)와 하루 일정 팝업(EP-0177, 화면 16). 요청한 사람의 과목만 다룬다.
 * 사용자는 session principal의 userId로만 정한다(SC-02). 이 모듈은 identity에 의존하지 않으므로 principal 타입 대신 그 값만 받는다.
 */
@RestController
class CalendarController {

	private final CalendarService calendar;

	CalendarController(CalendarService calendar) {
		this.calendar = calendar;
	}

	/**
	 * @param view month(기본) 또는 week
	 * @param date 기준 날짜(yyyy-MM-dd). 없으면 오늘
	 */
	@GetMapping("/api/me/calendar")
	ResponseEntity<CalendarView> range(@AuthenticationPrincipal(expression = "userId()") Long userId,
			@RequestParam(defaultValue = "month") String view,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return ok(this.calendar.range(userId, view, date));
	}

	/** 그날에 걸친 학사일정과 그날 마감인 활동. */
	@GetMapping("/api/me/calendar/days/{date}")
	ResponseEntity<CalendarView> day(@AuthenticationPrincipal(expression = "userId()") Long userId,
			@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		return ok(this.calendar.day(userId, date));
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

}
