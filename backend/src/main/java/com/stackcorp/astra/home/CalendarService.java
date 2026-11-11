package com.stackcorp.astra.home;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.stackcorp.astra.catalog.api.AcademicCalendar;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.MemberCourses.MemberCourse;
import com.stackcorp.astra.learning.api.CalendarActivities;
import com.stackcorp.astra.learning.api.CalendarActivities.CalendarActivity;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 메인 캘린더(EP-0178)와 하루 일정(EP-0177, 화면 16). 학사일정(catalog)과 요청한 사람의 과목 활동 마감(learning)을 함께 돌려준다.
 * 활동은 마감일 칸에 놓는다. 담당 과목은 모든 활동, 수강 과목은 학생에게 열린 활동만이다. 기간과 주 경계(일요일 시작)는 서버가 DB
 * 시계로 정한다(CAT-15). 과목 수와 관계없이 쿼리 4개(시계, 과목, 학사일정, 활동)이고 읽기 전용 transaction 하나에서 읽는다.
 */
@Service
class CalendarService {

	/** 한 번에 돌려주는 활동 수 상한. 넘으면 truncated가 true다. */
	static final int MAX_ACTIVITIES = 500;

	/** 오늘에서 이만큼 넘게 떨어진 날은 묻지 않는다. */
	static final int MAX_DISTANCE_DAYS = 400;

	private final JdbcClient jdbc;

	private final MemberCourses courses;

	private final AcademicCalendar academic;

	private final CalendarActivities activities;

	CalendarService(JdbcClient jdbc, MemberCourses courses, AcademicCalendar academic, CalendarActivities activities) {
		this.jdbc = jdbc;
		this.courses = courses;
		this.academic = academic;
		this.activities = activities;
	}

	/**
	 * @param view month면 그 날짜가 든 달을 채우는 주들(앞뒤 달의 날 포함), week면 그 날짜가 든 주
	 * @param date 기준 날짜. 없으면 오늘
	 */
	@Transactional(readOnly = true)
	CalendarView range(long userId, String view, LocalDate date) {
		LocalDateTime now = now();
		LocalDate day = checked(now, date);
		LocalDate from;
		LocalDate to;
		switch (view) {
			case "month" -> {
				from = sunday(day.withDayOfMonth(1));
				to = sunday(day.withDayOfMonth(day.lengthOfMonth())).plusDays(6);
			}
			case "week" -> {
				from = sunday(day);
				to = from.plusDays(6);
			}
			default -> throw ApiException.invalid("view는 month 또는 week여야 합니다.");
		}
		return collect(userId, now, from, to);
	}

	@Transactional(readOnly = true)
	CalendarView day(long userId, LocalDate date) {
		LocalDateTime now = now();
		LocalDate day = checked(now, date);
		return collect(userId, now, day, day);
	}

	private CalendarView collect(long userId, LocalDateTime now, LocalDate from, LocalDate to) {
		List<MemberCourse> mine = this.courses.of(userId);
		Map<Long, MemberCourse> byId = mine.stream()
			.collect(Collectors.toMap(MemberCourse::courseId, Function.identity()));
		List<Long> taught = ids(mine, CourseRole.INSTRUCTOR);
		List<Long> enrolled = ids(mine, CourseRole.STUDENT);
		List<CalendarEvent> events = this.academic.overlapping(from, to)
			.stream()
			.map((e) -> new CalendarEvent(e.eventId(), e.title(), e.type(), e.startDate(), e.endDate()))
			.toList();
		List<CalendarActivity> rows = this.activities.closingBetween(taught, enrolled, now, from.atStartOfDay(),
				to.plusDays(1).atStartOfDay(), MAX_ACTIVITIES + 1);
		List<CalendarItem> items = rows.stream().limit(MAX_ACTIVITIES).map((a) -> {
			MemberCourse course = byId.get(a.courseId());
			return new CalendarItem(a.activityId(), a.courseId(), course.name(), course.code(), course.section(),
					course.role().name(), a.type(), a.title(), a.openAt(), a.closeAt(), a.published());
		}).toList();
		return new CalendarView(now, now.toLocalDate(), from, to, events, items, rows.size() > MAX_ACTIVITIES);
	}

	private static LocalDate checked(LocalDateTime now, LocalDate date) {
		LocalDate day = date == null ? now.toLocalDate() : date;
		if (Math.abs(ChronoUnit.DAYS.between(now.toLocalDate(), day)) > MAX_DISTANCE_DAYS) {
			throw ApiException.invalid("date는 오늘에서 " + MAX_DISTANCE_DAYS + "일 안이어야 합니다.");
		}
		return day;
	}

	private static LocalDate sunday(LocalDate day) {
		return day.minusDays(day.getDayOfWeek().getValue() % 7);
	}

	private static List<Long> ids(List<MemberCourse> mine, CourseRole role) {
		return mine.stream().filter((c) -> c.role() == role).map(MemberCourse::courseId).toList();
	}

	private LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	/**
	 * @param serverTime 판정에 쓴 DB 시각. D-day와 시작·마감 표시는 이 값으로 계산한다
	 * @param from 첫날(포함)
	 * @param to 마지막 날(포함)
	 * @param truncated 활동이 상한을 넘어 일부만 담았는지
	 */
	record CalendarView(LocalDateTime serverTime, LocalDate today, LocalDate from, LocalDate to,
			List<CalendarEvent> events, List<CalendarItem> activities, boolean truncated) {
	}

	/**
	 * @param type ACADEMIC|REGISTRATION|EXAM|HOLIDAY
	 */
	record CalendarEvent(long eventId, String title, String type, LocalDate startDate, LocalDate endDate) {
	}

	/**
	 * @param role 이 과목에서 요청한 사람의 역할. INSTRUCTOR|STUDENT
	 * @param type 활동 유형(learning_activities.activity_type)
	 * @param published 공개 여부. 담당자에게만 false가 있다
	 */
	record CalendarItem(long activityId, long courseId, String courseName, String courseCode, String section,
			String role, String type, String title, LocalDateTime openAt, LocalDateTime closeAt, boolean published) {
	}

}
