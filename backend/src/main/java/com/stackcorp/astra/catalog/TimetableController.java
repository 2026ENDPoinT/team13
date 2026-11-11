package com.stackcorp.astra.catalog;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수업시간표(화면 14). 담당하거나 수강이 확정된 과목 가운데 현재 학기 과목의 수업 시간 슬롯(course_schedules)만 돌려준다.
 * 주는 일요일에 시작해 토요일에 끝나고, 주 경계와 오늘은 서버가 DB 시계로 정한다(CAT-15). 학기 기간이 있으면 그 밖의 날은 넣지 않는다.
 * 휴강·보강은 스키마에 없어 반영하지 않는다.
 */
@RestController
class TimetableController {

	/** 오늘에서 이만큼 넘게 떨어진 주는 묻지 않는다(EP-0171 범위 제한). */
	static final int MAX_DISTANCE_DAYS = 400;

	private static final String SLOT = """
			SELECT s.course_id, s.day_of_week, s.period_start, s.period_end, s.start_time, s.end_time, s.room_name
			  FROM course_schedules s
			""";

	private static final String ORDER = " ORDER BY s.day_of_week, s.start_time, s.period_start, s.course_schedule_id";

	private final JdbcClient jdbc;

	private final CourseMemberships memberships;

	private final UserNames userNames;

	TimetableController(JdbcClient jdbc, CourseMemberships memberships, UserNames userNames) {
		this.jdbc = jdbc;
		this.memberships = memberships;
		this.userNames = userNames;
	}

	/**
	 * 한 주의 시간표(EP-0172, EP-0171). 날짜를 비우면 오늘이 든 주다. 오늘 수업(EP-0180)은 이 응답에서 {@code date == today}인
	 * 슬롯이다.
	 * @param date 이 날짜가 든 주(yyyy-MM-dd)
	 */
	@GetMapping("/api/me/timetable")
	ResponseEntity<WeekTimetable> week(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
		LocalDateTime now = this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
		LocalDate today = now.toLocalDate();
		LocalDate day = date == null ? today : date;
		if (Math.abs(ChronoUnit.DAYS.between(today, day)) > MAX_DISTANCE_DAYS) {
			throw ApiException.invalid("date는 오늘에서 " + MAX_DISTANCE_DAYS + "일 안이어야 합니다.");
		}
		LocalDate weekStart = day.minusDays(day.getDayOfWeek().getValue() % 7);
		List<CourseSlotRow> rows = this.jdbc.sql("""
				SELECT s.course_id, s.day_of_week, s.period_start, s.period_end, s.start_time, s.end_time, s.room_name,
				       c.course_name, c.course_code, c.section_no, t.start_date, t.end_date,
				       (EXISTS (SELECT 1 FROM course_instructors i WHERE i.course_id = c.course_id AND i.user_id = :user)
				     OR EXISTS (SELECT 1 FROM course_enrollments e WHERE e.course_id = c.course_id AND e.user_id = :user
				                   AND e.enrollment_status = 'ENROLLED' AND e.enrollment_role IN ('PROFESSOR', 'TA'))) AS instructor
				  FROM course_schedules s JOIN courses c ON c.course_id = s.course_id JOIN terms t ON t.term_id = c.term_id
				 WHERE t.is_current = 1 AND c.course_status <> 'CANCELLED'
				   AND (c.course_id IN (SELECT course_id FROM course_instructors WHERE user_id = :user)
				     OR c.course_id IN (SELECT course_id FROM course_enrollments
				                         WHERE user_id = :user AND enrollment_status = 'ENROLLED'
				                           AND enrollment_role IN ('STUDENT', 'AUDITOR', 'PROFESSOR', 'TA')))
				""" + ORDER)
			.param("user", actor.userId())
			.query(TimetableController::courseSlotRow)
			.list();
		Map<Long, List<String>> instructors = instructors(rows.stream().map((r) -> r.slot().courseId()).toList());
		List<WeekSlot> slots = new ArrayList<>();
		for (CourseSlotRow row : rows) {
			LocalDate on = weekStart.plusDays(row.slot().dayOfWeek());
			if ((row.termStart() != null && on.isBefore(row.termStart()))
					|| (row.termEnd() != null && on.isAfter(row.termEnd()))) {
				continue;
			}
			Slot slot = row.slot();
			slots.add(new WeekSlot(slot.courseId(), row.name(), row.code(), row.section(), row.role().name(), on,
					slot.dayOfWeek(), slot.periodStart(), slot.periodEnd(), slot.startTime(), slot.endTime(), slot.room(),
					instructors.getOrDefault(slot.courseId(), List.of())));
		}
		return ok(new WeekTimetable(now, today, weekStart, weekStart.plusDays(6), slots));
	}

	/** 한 과목의 수업 시간(EP-0170, 모바일 상세). 과목 구성원만 본다. 역할은 서버가 관계로 정한다. */
	@GetMapping("/api/courses/{courseId}/timetable")
	ResponseEntity<CourseTimetable> course(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		CourseRole role = this.memberships.roleOf(actor.userId(), courseId).orElseThrow(ApiException::notFound);
		CourseTimetable view = this.jdbc
			.sql("SELECT course_name, course_code, section_no FROM courses WHERE course_id = :course AND course_status <> 'CANCELLED'")
			.param("course", courseId)
			.query((rs, n) -> new CourseTimetable(courseId, rs.getString("course_name"), rs.getString("course_code"),
					rs.getString("section_no"), role.name(), List.of(), List.of()))
			.optional()
			.orElseThrow(ApiException::notFound);
		List<Slot> slots = this.jdbc.sql(SLOT + " WHERE s.course_id = :course" + ORDER)
			.param("course", courseId)
			.query(TimetableController::slot)
			.list();
		return ok(new CourseTimetable(view.courseId(), view.name(), view.code(), view.section(), view.role(),
				instructors(List.of(courseId)).getOrDefault(courseId, List.of()), slots));
	}

	/** 과목별 담당자 이름. 대표 담당자, 표시 순서대로다. */
	private Map<Long, List<String>> instructors(Collection<Long> courseIds) {
		if (courseIds.isEmpty()) {
			return Map.of();
		}
		List<long[]> pairs = this.jdbc.sql("""
				SELECT course_id, user_id FROM course_instructors WHERE course_id IN (:courses)
				 ORDER BY course_id, is_primary DESC, sort_order, course_instructor_id
				""")
			.param("courses", courseIds.stream().distinct().toList())
			.query((rs, n) -> new long[] { rs.getLong("course_id"), rs.getLong("user_id") })
			.list();
		Map<Long, String> names = this.userNames.namesOf(pairs.stream().map((p) -> p[1]).distinct().toList());
		Map<Long, List<String>> byCourse = new LinkedHashMap<>();
		for (long[] pair : pairs) {
			String name = names.get(pair[1]);
			List<String> list = byCourse.computeIfAbsent(pair[0], (k) -> new ArrayList<>());
			if (name != null && !list.contains(name)) {
				list.add(name);
			}
		}
		return byCourse;
	}

	private static Slot slot(ResultSet rs, int n) throws SQLException {
		return new Slot(rs.getLong("course_id"), rs.getInt("day_of_week"), rs.getObject("period_start", Integer.class),
				rs.getObject("period_end", Integer.class), rs.getObject("start_time", LocalTime.class),
				rs.getObject("end_time", LocalTime.class), rs.getString("room_name"));
	}

	private static CourseSlotRow courseSlotRow(ResultSet rs, int n) throws SQLException {
		return new CourseSlotRow(slot(rs, n), rs.getString("course_name"), rs.getString("course_code"),
				rs.getString("section_no"), rs.getObject("start_date", LocalDate.class),
				rs.getObject("end_date", LocalDate.class),
				rs.getBoolean("instructor") ? CourseRole.INSTRUCTOR : CourseRole.STUDENT);
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	private record CourseSlotRow(Slot slot, String name, String code, String section, LocalDate termStart,
			LocalDate termEnd, CourseRole role) {
	}

	/**
	 * @param dayOfWeek 0 일요일 ~ 6 토요일
	 * @param room 강의실. 등록하지 않았으면 null(화면 표기 "-")
	 */
	record Slot(long courseId, int dayOfWeek, Integer periodStart, Integer periodEnd, LocalTime startTime,
			LocalTime endTime, String room) {
	}

	/**
	 * @param role 이 과목에서 요청한 사람의 역할. INSTRUCTOR|STUDENT
	 * @param date 이 주에서 그 수업이 있는 날
	 */
	record WeekSlot(long courseId, String courseName, String courseCode, String section, String role, LocalDate date,
			int dayOfWeek, Integer periodStart, Integer periodEnd, LocalTime startTime, LocalTime endTime, String room,
			List<String> instructors) {
	}

	/**
	 * @param today DB 시계의 오늘
	 * @param weekStart 일요일
	 * @param weekEnd 토요일
	 */
	record WeekTimetable(LocalDateTime serverTime, LocalDate today, LocalDate weekStart, LocalDate weekEnd,
			List<WeekSlot> slots) {
	}

	record CourseTimetable(long courseId, String name, String code, String section, String role,
			List<String> instructors, List<Slot> slots) {
	}

}
