package com.stackcorp.astra.catalog;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import com.stackcorp.astra.catalog.CatalogQueries.ScheduleView;
import com.stackcorp.astra.catalog.CatalogQueries.TermView;
import com.stackcorp.astra.catalog.EnrollmentRecords.Enrollment;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.UnreadCourseNotices;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 내 과목 조회(EP-0214, EP-0215, EP-0216, EP-0202, EP-0203의 대체 계약). 담당하거나 수강이 확정된 과목만 돌려준다. 정규과목
 * 수강과목 화면은 type=REGULAR와 termId로, 비정규과목 수강과목 화면은 type=NONREGULAR와 division으로 고른다. 신청 중이거나 취소한
 * 과목은 나오지 않는다. 과목의 구성원이 아니면 그 과목이 있는지 드러내지 않도록 404로 응답한다(SC-02).
 * <p>
 * 담당 과목에는 수강생 수(`learners`)가 있다. 그 담당자가 보는 수강생 명단(GET /api/courses/{courseId}/students)의 검색어 없는
 * `total`과 같은 사람 수다. 학생에게는 반의 인원을 보여 주는 다른 응답이 없으므로 수강 과목에서는 null이다. 목록의 역할은 과목 행과
 * 같은 문장에서, 담당 과목의 수강생 수는 한 번에 읽어 쿼리 수가 과목 수와 관계없다.
 * <p>
 * 과목마다 안 읽은 공지 수(`unreadNotices`)가 있다. 강의실 왼쪽 메뉴의 공지 배지에 쓰고, 메인 화면 과목의 같은 필드와 같은 집계다
 * ({@link UnreadCourseNotices}). 역할과 학기에 관계없이 모든 행에 있고, 모든 과목을 한 번에 센다.
 */
@RestController
class CourseController {

	private static final String COLUMNS = """
			SELECT c.course_id, c.legacy_kjkey, c.course_name, c.course_code, c.section_no, c.course_type, c.credit,
			       t.term_id, t.term_year, t.term_code, t.term_name, t.is_current,
			       o.round_year, o.round_no, o.round_label, o.nonregular_category,
			       COALESCE(o.lecture_type, c.lecture_type) AS lecture_type, o.learning_start_date, o.learning_end_date,
			       o.cancel_allowed, o.cancel_close_at, c.cover_template, c.instructor_label, c.learning_period_label
			""";

	private static final String FROM = """
			  FROM courses c LEFT JOIN terms t ON t.term_id = c.term_id
			  LEFT JOIN non_regular_offerings o ON o.course_id = c.course_id
			""";

	/**
	 * 내 과목 목록. WHERE가 {@link MembershipRule#memberOf}로 거르므로 담당자가 아닌 행은 STUDENT다. 이것은
	 * {@link CourseMembershipQueries#roleOf}와 같은 판정이다({@link MemberCourseQueries}와 같다).
	 */
	private static final String MINE = COLUMNS + ", " + MembershipRule.instructorOf("c.course_id", ":user")
			+ " AS instructor" + FROM;

	private static final int MAX_QUERY = 100;

	private final JdbcClient jdbc;

	private final CourseMemberships memberships;

	private final CatalogQueries queries;

	private final EnrollmentRecords enrollments;

	private final CourseRosterQueries rosters;

	private final UnreadCourseNotices unreadNotices;

	CourseController(JdbcClient jdbc, CourseMemberships memberships, CatalogQueries queries,
			EnrollmentRecords enrollments, CourseRosterQueries rosters, UnreadCourseNotices unreadNotices) {
		this.jdbc = jdbc;
		this.memberships = memberships;
		this.queries = queries;
		this.enrollments = enrollments;
		this.rosters = rosters;
		this.unreadNotices = unreadNotices;
	}

	/**
	 * @param type REGULAR|NONREGULAR. 없으면 둘 다
	 * @param termId 정규 과목의 학기
	 * @param division 비정규 분류 B|C|K
	 * @param q 과목명이나 학수번호-분반에 들어 있는 글자
	 */
	@GetMapping("/api/me/courses")
	ResponseEntity<List<CourseView>> mine(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) String type, @RequestParam(required = false) Long termId,
			@RequestParam(required = false) String division, @RequestParam(required = false) String q) {
		StringBuilder where = new StringBuilder(" WHERE c.course_id IN (" + MembershipRule.candidateCourses(":user")
				+ ") AND " + MembershipRule.memberOf("c.course_id", ":user"));
		Map<String, Object> params = new HashMap<>();
		params.put("user", actor.userId());
		if (type != null) {
			if (!type.equals("REGULAR") && !type.equals("NONREGULAR")) {
				throw ApiException.invalid("type은 REGULAR 또는 NONREGULAR여야 합니다.");
			}
			where.append(" AND c.course_type = :type");
			params.put("type", type.equals("REGULAR") ? CatalogQueries.REGULAR : CatalogQueries.NON_REGULAR);
		}
		if (termId != null) {
			where.append(" AND c.term_id = :term");
			params.put("term", termId);
		}
		if (division != null) {
			if (!division.equals("B") && !division.equals("C") && !division.equals("K")) {
				throw ApiException.invalid("division은 B, C, K 중 하나여야 합니다.");
			}
			where.append(" AND o.nonregular_category = :division");
			params.put("division", division);
		}
		if (q != null && !q.isBlank()) {
			String text = q.strip();
			if (text.length() > MAX_QUERY || text.codePoints().anyMatch(Character::isISOControl)) {
				throw ApiException.invalid("검색어는 " + MAX_QUERY + "자 이하여야 합니다.");
			}
			where.append(" AND (c.course_name LIKE CONCAT('%', :q, '%')")
				.append(" OR CONCAT_WS('-', c.course_code, c.section_no) LIKE CONCAT('%', :q, '%'))");
			params.put("q", CatalogQueries.likeLiteral(text));
		}
		List<CourseRow> rows = new ArrayList<>();
		List<CourseRole> roles = new ArrayList<>();
		this.jdbc.sql(MINE + where + """
				 ORDER BY t.is_current DESC, t.sort_order, o.round_year DESC, o.round_no DESC, c.course_name, c.course_id
				""").params(params).query((rs) -> {
			rows.add(row(rs, rows.size()));
			roles.add(rs.getBoolean("instructor") ? CourseRole.INSTRUCTOR : CourseRole.STUDENT);
		});
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views(actor, rows, roles));
	}

	@GetMapping("/api/courses/{courseId}")
	ResponseEntity<CourseView> one(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		Optional<CourseRole> role = this.memberships.roleOf(actor.userId(), courseId);
		CourseRow row = this.jdbc.sql(COLUMNS + FROM + " WHERE c.course_id = :course AND c.course_status <> 'CANCELLED'")
			.param("course", courseId)
			.query(CourseController::row)
			.optional()
			.filter((r) -> role.isPresent())
			.orElseThrow(ApiException::notFound);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(views(actor, List.of(row), List.of(role.get())).get(0));
	}

	private List<CourseView> views(Actor actor, List<CourseRow> rows, List<CourseRole> roles) {
		List<Long> ids = rows.stream().map(CourseRow::courseId).toList();
		Map<Long, List<String>> instructors = this.queries.instructorNames(ids);
		Map<Long, List<ScheduleView>> schedules = this.queries.schedules(ids);
		Map<Long, Enrollment> enrolled = this.enrollments.enrolledOf(actor.userId(), ids);
		LocalDateTime now = ids.isEmpty() ? null : this.queries.now();
		Map<Long, Integer> unread = this.unreadNotices.of(actor.userId(), ids, now);
		Map<Long, Integer> learners = this.rosters.counts(IntStream.range(0, rows.size())
			.filter((i) -> roles.get(i) == CourseRole.INSTRUCTOR)
			.mapToObj((i) -> rows.get(i).courseId())
			.toList());
		List<CourseView> views = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			CourseRow row = rows.get(i);
			CourseRole role = roles.get(i);
			Enrollment e = role == CourseRole.STUDENT ? enrolled.get(row.courseId()) : null;
			MyEnrollment mine = e == null ? null
					: new MyEnrollment(e.enrollmentId(), e.kind(), e.status(), e.version(), OfferingRules
						.cancellable(e.kind(), e.status(), row.cancelAllowed(), row.cancelCloseAt(), now));
			// 담당자 행이 없는 과목(학교 화면에서 맞춘 과목)은 학교 화면의 담당자 글자를 쓴다.
			List<String> names = instructors.getOrDefault(row.courseId(), List.of());
			if (names.isEmpty() && row.instructorLabel() != null) {
				names = List.of(row.instructorLabel());
			}
			views.add(new CourseView(row.courseId(), row.key(), row.name(), row.code(), row.section(), row.term(),
					role.name(), names, row.type(), row.credit(), schedules.getOrDefault(row.courseId(), List.of()),
					row.offering(), mine, row.coverTemplate(), row.learningPeriod(), row.lectureType(),
					role == CourseRole.INSTRUCTOR ? learners.getOrDefault(row.courseId(), 0) : null,
					unread.getOrDefault(row.courseId(), 0)));
		}
		return views;
	}

	private static CourseRow row(ResultSet rs, int n) throws SQLException {
		TermView term = rs.getObject("term_id", Long.class) == null ? null : CatalogQueries.term(rs, n);
		Integer roundYear = rs.getObject("round_year", Integer.class);
		OfferingSummary offering = roundYear == null ? null
				: new OfferingSummary(roundYear, rs.getInt("round_no"), rs.getString("round_label"),
						rs.getString("nonregular_category"), rs.getString("lecture_type"),
						rs.getObject("learning_start_date", LocalDate.class),
						rs.getObject("learning_end_date", LocalDate.class));
		return new CourseRow(rs.getLong("course_id"), rs.getString("legacy_kjkey"), rs.getString("course_name"),
				rs.getString("course_code"), rs.getString("section_no"), term,
				CatalogQueries.NON_REGULAR.equals(rs.getString("course_type")) ? "NONREGULAR" : "REGULAR",
				rs.getBigDecimal("credit"), offering, rs.getObject("cancel_allowed", Boolean.class),
				rs.getObject("cancel_close_at", LocalDateTime.class), rs.getString("cover_template"),
				rs.getString("instructor_label"), rs.getString("learning_period_label"), rs.getString("lecture_type"));
	}

	private record CourseRow(long courseId, String key, String name, String code, String section, TermView term,
			String type, BigDecimal credit, OfferingSummary offering, Boolean cancelAllowed,
			LocalDateTime cancelCloseAt, String coverTemplate, String instructorLabel, String learningPeriod,
			String lectureType) {
	}

	/**
	 * 비정규 과정의 차수와 학습기간.
	 * @param division 비정규 분류 B|C|K
	 */
	record OfferingSummary(int roundYear, int roundNo, String roundLabel, String division, String lectureType,
			LocalDate learningStartDate, LocalDate learningEndDate) {
	}

	/**
	 * 학생으로 수강이 확정된 근거.
	 * @param kind REGISTRAR(학사 원장)|NONREGULAR|AUDIT
	 * @param cancellable 학생이 스스로 취소할 수 있는지. 학사 원장은 언제나 false
	 */
	record MyEnrollment(long enrollmentId, String kind, String status, int version, boolean cancellable) {
	}

	/**
	 * @param key 과목 키(KJKEY). 없으면 null
	 * @param role 이 과목에서 요청한 사용자의 역할. INSTRUCTOR|STUDENT
	 * @param type REGULAR|NONREGULAR
	 * @param offering 비정규 과정이면 차수와 학습기간. 정규 과목이면 null
	 * @param enrollment 학생이면 수강 근거. 담당자면 null
	 * @param coverTemplate 기본 표지 템플릿 키(예: dynamic_13). 없으면 null
	 * @param learningPeriod 학교 화면에서 맞춘 비정규 과목의 학습기간 표시(예: 2026.05.13~2026.12.21). 없으면 null
	 * @param lectureType 수업형태(예: 이러닝수업). 없으면 null
	 * @param learners 담당자면 수강생 수: 그 과목의 수강생 명단(GET /api/courses/{courseId}/students)에 나오는 사람 수로, 검색어 없는
	 * 명단의 `total`과 같다. 학생이면 null
	 * @param unreadNotices 게시된 과목 공지 중 요청한 사용자가 읽음으로 표시하지 않은 수(자기가 쓴 공지 제외). 메인 화면 과목의
	 * `unreadNotices`와 같다
	 */
	record CourseView(long courseId, String key, String name, String code, String section, TermView term, String role,
			List<String> instructors, String type, BigDecimal credit, List<ScheduleView> schedules,
			OfferingSummary offering, MyEnrollment enrollment, String coverTemplate, String learningPeriod,
			String lectureType, Integer learners, int unreadNotices) {
	}

}
