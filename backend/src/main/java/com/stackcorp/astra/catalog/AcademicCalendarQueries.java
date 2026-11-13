package com.stackcorp.astra.catalog;

import java.time.LocalDate;
import java.util.List;

import com.stackcorp.astra.catalog.api.AcademicCalendar;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * 공개한 학사일정만 돌려준다(is_public = 1). 기간 겹침은 {@code start_date <= to AND end_date >= from}이다.
 */
@Service
class AcademicCalendarQueries implements AcademicCalendar {

	private final JdbcClient jdbc;

	AcademicCalendarQueries(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<AcademicEvent> overlapping(LocalDate from, LocalDate to) {
		return this.jdbc.sql("""
				SELECT academic_event_id, event_title, event_type, start_date, end_date
				  FROM academic_calendar_events
				 WHERE is_public = 1 AND start_date <= :to AND end_date >= :from
				 ORDER BY start_date, academic_event_id
				 LIMIT :limit
				""")
			.param("from", from)
			.param("to", to)
			.param("limit", MAX_EVENTS)
			.query((rs, n) -> new AcademicEvent(rs.getLong("academic_event_id"), rs.getString("event_title"),
					rs.getString("event_type"), rs.getObject("start_date", LocalDate.class),
					rs.getObject("end_date", LocalDate.class)))
			.list();
	}

}
