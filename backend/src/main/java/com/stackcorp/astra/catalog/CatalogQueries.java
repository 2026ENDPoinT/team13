package com.stackcorp.astra.catalog;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import com.stackcorp.astra.identity.api.UserNames;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 공개 카탈로그 조회. 과목과 비정규 과정의 공개 항목(이름, 코드, 학기, 담당자 이름, 수업 시간, 접수 기간, 정원과 신청 인원)만
 * 읽는다. 수강생 명단과 학습활동은 읽지 않는다. 폐강 과목은 어디에도 나오지 않는다.
 */
@Repository
class CatalogQueries {

	/** 정규 과목은 학기가 있는 과목이다. 학기가 없는 정규 행은 카탈로그에 나오지 않는다. */
	static final String REGULAR = "정규";

	static final String NON_REGULAR = "비정규";

	private static final String COURSE_COLUMNS = """
			SELECT c.course_id, c.legacy_kjkey, c.course_name, c.course_code, c.section_no, c.org_sect, c.department_code,
			       c.department_name, c.credit, c.completion_type, c.grade_year, c.lecture_type, c.audit_allowed,
			       c.is_merged_class, c.course_status, t.term_id, t.term_year, t.term_code, t.term_name, t.is_current
			  FROM courses c JOIN terms t ON t.term_id = c.term_id
			""";

	private static final String REGULAR_SCOPE = " WHERE c.course_type = '" + REGULAR
			+ "' AND c.course_status <> 'CANCELLED'";

	/** 잠금 읽기에서도 쓴다. 정원 카운터가 없으면 제한 없음, 신청 인원 0이다. */
	static final String OFFERING_COLUMNS = """
			SELECT o.offering_id, o.course_id, c.legacy_kjkey, c.course_name, c.course_code, c.section_no,
			       o.nonregular_category, o.category_code, COALESCE(o.lecture_type, c.lecture_type) AS lecture_type,
			       o.approval_mode, o.round_year, o.round_no, o.round_label, o.application_open_at, o.application_close_at,
			       o.cancel_close_at, o.learning_start_date, o.learning_end_date, o.cancel_allowed, o.offering_status,
			       c.course_status, s.seat_capacity, COALESCE(s.held_count, 0) AS held_count
			  FROM non_regular_offerings o JOIN courses c ON c.course_id = o.course_id
			  LEFT JOIN course_seat_counters s ON s.course_id = o.course_id
			""";

	static final String OFFERING_SCOPE = " WHERE c.course_type = '" + NON_REGULAR
			+ "' AND c.course_status <> 'CANCELLED'";

	// CONCAT_WS는 화면 표기(학수번호-분반)로 찾을 수 있게 한다. q는 likeLiteral로 escape한 값이다.
	private static final String REGULAR_MATCH = " AND (c.course_name LIKE CONCAT('%', :q, '%')"
			+ " OR CONCAT_WS('-', c.course_code, c.section_no) LIKE CONCAT('%', :q, '%'))";

	private static final String OFFERING_MATCH = " AND (c.course_name LIKE CONCAT('%', :q, '%')"
			+ " OR CONCAT_WS('-', c.course_code, c.section_no) LIKE CONCAT('%', :q, '%')"
			+ " OR o.legacy_course_no LIKE CONCAT('%', :q, '%'))";

	private final JdbcClient jdbc;

	private final UserNames userNames;

	CatalogQueries(JdbcClient jdbc, UserNames userNames) {
		this.jdbc = jdbc;
		this.userNames = userNames;
	}

	/** 판정 기준 시각. 클라이언트 시계는 쓰지 않는다(SC-06). */
	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

	/** 정규·특별 학기. 최근 학기가 먼저 나온다. */
	List<TermView> terms() {
		return this.jdbc.sql("""
				SELECT term_id, term_year, term_code, term_name, is_current FROM terms
				 WHERE term_type <> 'NONREGULAR'
				 ORDER BY sort_order, term_year DESC, term_code DESC, term_id
				""").query(CatalogQueries::term).list();
	}

	List<Long> currentTermIds() {
		return this.jdbc.sql("SELECT term_id FROM terms WHERE is_current = 1 AND term_type <> 'NONREGULAR'")
			.query(Long.class)
			.list();
	}

	List<Department> departments(Collection<Long> termIds) {
		if (termIds.isEmpty()) {
			return List.of();
		}
		return this.jdbc.sql("""
				SELECT c.department_code, MAX(c.department_name) AS department_name FROM courses c
				""" + REGULAR_SCOPE + """
				   AND c.term_id IN (:terms) AND c.department_code IS NOT NULL
				 GROUP BY c.department_code
				 ORDER BY department_name, c.department_code
				""")
			.param("terms", termIds)
			.query((rs, n) -> new Department(rs.getString(1), rs.getString(2)))
			.list();
	}

	Slice<CourseRow> regularCourses(RegularFilter filter, int offset, int size) {
		if (filter.termIds().isEmpty()) {
			return new Slice<>(List.of(), 0);
		}
		StringBuilder where = new StringBuilder(REGULAR_SCOPE).append(" AND c.term_id IN (:terms)");
		Map<String, Object> params = new HashMap<>();
		params.put("terms", filter.termIds());
		if (filter.orgSect() != null) {
			where.append(" AND c.org_sect = :orgSect");
			params.put("orgSect", filter.orgSect());
		}
		if (!filter.departments().isEmpty()) {
			where.append(" AND c.department_code IN (:departments)");
			params.put("departments", filter.departments());
		}
		if (!filter.credits().isEmpty()) {
			where.append(" AND c.credit IN (:credits)");
			params.put("credits", filter.credits());
		}
		if (filter.auditableOnly()) {
			where.append(" AND c.audit_allowed = 1");
		}
		if (filter.query() != null) {
			where.append(REGULAR_MATCH);
			params.put("q", likeLiteral(filter.query()));
		}
		long total = this.jdbc.sql("SELECT COUNT(*) FROM courses c JOIN terms t ON t.term_id = c.term_id" + where)
			.params(params)
			.query(Long.class)
			.single();
		List<CourseRow> rows = this.jdbc.sql(COURSE_COLUMNS + where + """
				 ORDER BY t.sort_order, t.term_id, c.course_code, c.section_no, c.course_id
				 LIMIT :size OFFSET :offset
				""").params(params).param("size", size).param("offset", offset).query(CatalogQueries::course).list();
		return new Slice<>(rows, total);
	}

	Optional<CourseRow> regularCourse(long courseId) {
		return this.jdbc.sql(COURSE_COLUMNS + REGULAR_SCOPE + " AND c.course_id = :course")
			.param("course", courseId)
			.query(CatalogQueries::course)
			.optional();
	}

	/**
	 * @param now 접수 중만 고를 때 쓰는 DB 시각
	 */
	Slice<OfferingRow> offerings(OfferingFilter filter, LocalDateTime now, int offset, int size) {
		StringBuilder where = new StringBuilder(OFFERING_SCOPE);
		Map<String, Object> params = new HashMap<>();
		if (filter.acceptingOnly()) {
			where.append(" AND o.offering_status = 'OPEN' AND c.course_status = 'OPEN'")
				.append(" AND o.application_open_at <= :now AND :now < o.application_close_at");
			params.put("now", now);
		}
		if (filter.audience() != null) {
			where.append(" AND EXISTS (SELECT 1 FROM non_regular_targets g")
				.append(" WHERE g.offering_id = o.offering_id AND g.audience_code = :audience)");
			params.put("audience", filter.audience());
		}
		if (filter.division() != null) {
			where.append(" AND o.nonregular_category = :division");
			params.put("division", filter.division());
		}
		if (!filter.categories().isEmpty()) {
			where.append(" AND o.category_code IN (:categories)");
			params.put("categories", filter.categories());
		}
		if (!filter.lectureTypes().isEmpty()) {
			where.append(" AND COALESCE(o.lecture_type, c.lecture_type) IN (:lectureTypes)");
			params.put("lectureTypes", filter.lectureTypes());
		}
		if (filter.query() != null) {
			where.append(OFFERING_MATCH);
			params.put("q", likeLiteral(filter.query()));
		}
		long total = this.jdbc.sql("""
				SELECT COUNT(*) FROM non_regular_offerings o JOIN courses c ON c.course_id = o.course_id
				""" + where).params(params).query(Long.class).single();
		List<OfferingRow> rows = this.jdbc.sql(OFFERING_COLUMNS + where + """
				 ORDER BY o.application_open_at DESC, o.course_id DESC
				 LIMIT :size OFFSET :offset
				""").params(params).param("size", size).param("offset", offset).query(CatalogQueries::offering).list();
		return new Slice<>(rows, total);
	}

	Optional<OfferingRow> offering(long courseId) {
		return this.jdbc.sql(OFFERING_COLUMNS + OFFERING_SCOPE + " AND o.course_id = :course")
			.param("course", courseId)
			.query(CatalogQueries::offering)
			.optional();
	}

	/** 과목별 담당자 표시 이름. 대표 담당자가 먼저다. 담당자 이름은 카드에 공개하는 항목이다. */
	Map<Long, List<String>> instructorNames(Collection<Long> courseIds) {
		if (courseIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<Long>> ids = new LinkedHashMap<>();
		this.jdbc.sql("""
				SELECT course_id, user_id FROM course_instructors WHERE course_id IN (:courses)
				 ORDER BY course_id, is_primary DESC, sort_order, course_instructor_id
				""").param("courses", courseIds).query((rs) -> {
			List<Long> users = ids.computeIfAbsent(rs.getLong(1), (k) -> new ArrayList<>());
			long user = rs.getLong(2);
			// 한 사람이 교수와 담당자로 함께 등록돼도 이름은 한 번만 쓴다.
			if (!users.contains(user)) {
				users.add(user);
			}
		});
		Map<Long, String> names = this.userNames
			.namesOf(ids.values().stream().flatMap(List::stream).collect(Collectors.toSet()));
		Map<Long, List<String>> result = new HashMap<>();
		ids.forEach((course, users) -> result.put(course,
				users.stream().map(names::get).filter(Objects::nonNull).toList()));
		return result;
	}

	Map<Long, List<ScheduleView>> schedules(Collection<Long> courseIds) {
		if (courseIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<ScheduleView>> result = new HashMap<>();
		this.jdbc.sql("""
				SELECT course_id, day_of_week, period_start, period_end, start_time, end_time, room_name
				  FROM course_schedules WHERE course_id IN (:courses)
				 ORDER BY course_id, day_of_week, start_time, period_start, course_schedule_id
				""")
			.param("courses", courseIds)
			.query((rs) -> {
				result.computeIfAbsent(rs.getLong(1), (k) -> new ArrayList<>())
					.add(new ScheduleView(rs.getInt(2), rs.getObject(3, Integer.class), rs.getObject(4, Integer.class),
							rs.getObject(5, LocalTime.class), rs.getObject(6, LocalTime.class), rs.getString(7)));
			});
		return result;
	}

	/** 과목별 신청대상 코드(1 교수, 2 교직원, 3 학생, 4 졸업생, 5 조교, 6 대학원생). */
	Map<Long, List<String>> audiences(Collection<Long> courseIds) {
		if (courseIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<String>> result = new HashMap<>();
		this.jdbc.sql("""
				SELECT o.course_id, g.audience_code FROM non_regular_targets g
				  JOIN non_regular_offerings o ON o.offering_id = g.offering_id
				 WHERE o.course_id IN (:courses)
				 ORDER BY o.course_id, g.audience_code
				""")
			.param("courses", courseIds)
			.query((rs) -> {
				result.computeIfAbsent(rs.getLong(1), (k) -> new ArrayList<>()).add(rs.getString(2));
			});
		return result;
	}

	/** LIKE 안에서 사용자 입력의 %, _, \를 글자 그대로 비교하게 escape한다. MySQL의 기본 escape 문자는 \다. */
	static String likeLiteral(String text) {
		return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

	static List<Long> ids(Collection<? extends HasCourse> rows) {
		return List.copyOf(rows.stream().map(HasCourse::courseId).collect(Collectors.toCollection(LinkedHashSet::new)));
	}

	static TermView term(ResultSet rs, int n) throws SQLException {
		return new TermView(rs.getLong("term_id"), rs.getInt("term_year"), rs.getString("term_code"),
				rs.getString("term_name"), rs.getBoolean("is_current"));
	}

	private static CourseRow course(ResultSet rs, int n) throws SQLException {
		return new CourseRow(rs.getLong("course_id"), rs.getString("legacy_kjkey"), rs.getString("course_name"),
				rs.getString("course_code"), rs.getString("section_no"), rs.getString("org_sect"), term(rs, n),
				rs.getString("department_code"), rs.getString("department_name"), rs.getBigDecimal("credit"),
				rs.getString("completion_type"), rs.getObject("grade_year", Integer.class), rs.getString("lecture_type"),
				rs.getBoolean("audit_allowed"), rs.getBoolean("is_merged_class"), rs.getString("course_status"));
	}

	static OfferingRow offering(ResultSet rs, int n) throws SQLException {
		return new OfferingRow(rs.getLong("offering_id"), rs.getLong("course_id"), rs.getString("legacy_kjkey"),
				rs.getString("course_name"), rs.getString("course_code"), rs.getString("section_no"),
				rs.getString("nonregular_category"), rs.getString("category_code"), rs.getString("lecture_type"),
				rs.getString("approval_mode"), rs.getInt("round_year"), rs.getInt("round_no"), rs.getString("round_label"),
				rs.getObject("application_open_at", LocalDateTime.class),
				rs.getObject("application_close_at", LocalDateTime.class),
				rs.getObject("cancel_close_at", LocalDateTime.class),
				rs.getObject("learning_start_date", LocalDate.class), rs.getObject("learning_end_date", LocalDate.class),
				rs.getBoolean("cancel_allowed"), rs.getString("offering_status"), rs.getString("course_status"),
				rs.getObject("seat_capacity", Integer.class), rs.getInt("held_count"));
	}

	interface HasCourse {

		long courseId();

	}

	record Slice<T>(List<T> rows, long total) {
	}

	record TermView(long termId, int year, String code, String name, boolean current) {
	}

	record Department(String code, String name) {
	}

	/**
	 * @param dayOfWeek 0 일요일 ~ 6 토요일
	 * @param room 강의실. 없으면 null
	 */
	record ScheduleView(int dayOfWeek, Integer periodStart, Integer periodEnd, LocalTime startTime, LocalTime endTime,
			String room) {
	}

	/**
	 * @param termIds 비어 있으면 결과도 비어 있다
	 * @param query escape 전의 검색어. 없으면 null
	 */
	record RegularFilter(List<Long> termIds, String orgSect, List<String> departments, List<BigDecimal> credits,
			boolean auditableOnly, String query) {
	}

	/**
	 * @param acceptingOnly 지금 접수 중인 과정만
	 * @param division 비정규 분류 B|C|K. 없으면 null
	 */
	record OfferingFilter(boolean acceptingOnly, String audience, String division, List<String> categories,
			List<String> lectureTypes, String query) {
	}

	record CourseRow(long courseId, String key, String name, String code, String section, String orgSect,
			TermView term, String departmentCode, String departmentName, BigDecimal credit, String completionType,
			Integer gradeYear, String lectureType, boolean auditAllowed, boolean merged, String status)
			implements HasCourse {
	}

	/**
	 * @param capacity 정원. null이면 제한 없음
	 * @param held 좌석을 점유한(수강이 확정된) 인원
	 */
	record OfferingRow(long offeringId, long courseId, String key, String name, String code, String section,
			String division, String categoryCode, String lectureType, String approvalMode, int roundYear, int roundNo,
			String roundLabel, LocalDateTime applicationOpenAt, LocalDateTime applicationCloseAt,
			LocalDateTime cancelCloseAt, LocalDate learningStartDate, LocalDate learningEndDate, boolean cancelAllowed,
			String offeringStatus, String courseStatus, Integer capacity, int held) implements HasCourse {
	}

}
