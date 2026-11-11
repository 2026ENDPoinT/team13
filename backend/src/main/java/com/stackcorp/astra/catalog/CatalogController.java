package com.stackcorp.astra.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import com.stackcorp.astra.catalog.CatalogQueries.CourseRow;
import com.stackcorp.astra.catalog.CatalogQueries.Department;
import com.stackcorp.astra.catalog.CatalogQueries.OfferingFilter;
import com.stackcorp.astra.catalog.CatalogQueries.OfferingRow;
import com.stackcorp.astra.catalog.CatalogQueries.RegularFilter;
import com.stackcorp.astra.catalog.CatalogQueries.ScheduleView;
import com.stackcorp.astra.catalog.CatalogQueries.Slice;
import com.stackcorp.astra.catalog.CatalogQueries.TermView;
import com.stackcorp.astra.catalog.EnrollmentRecords.Enrollment;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 정규과목 전체·상세와 비정규과목 전체·상세 조회(EP-0211~EP-0213, EP-0217, EP-0200, EP-0201, EP-0204의 대체 계약).
 * 로그인한 사용자 누구에게나 공개 카탈로그 항목만 돌려준다. 수강생 명단, 학습활동, 강의계획서 본문은 없다. 요청한 사람의 관계는
 * viewer에 본인 것만 담는다. 조회는 신청을 만들지 않는다(SC-04, CHG-03). 목록은 page 번호와 size 상한(40)이 있고 정렬은 고정이다.
 * 필터 값은 허용 목록으로 검사한다(SC-09).
 */
@RestController
class CatalogController {

	private static final int MAX_PAGE_SIZE = 40;

	private static final int MAX_PAGE = 10_000;

	private static final int MAX_QUERY = 100;

	private static final int MAX_VALUES = 30;

	private static final Set<String> ORG_SECTS = Set.of("A", "G", "F");

	private static final Set<String> AUDIENCES = Set.of("1", "2", "3", "4", "5", "6");

	private static final Set<String> DIVISIONS = Set.of("B", "C", "K");

	private static final Set<String> LECTURE_TYPES = Set.of("이클래스", "이러닝수업", "블렌디드", "플립");

	private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]{1,20}");

	private static final BigDecimal MAX_CREDIT = new BigDecimal("99.9");

	private final CatalogQueries queries;

	private final EnrollmentRecords enrollments;

	private final CourseMemberships memberships;

	CatalogController(CatalogQueries queries, EnrollmentRecords enrollments, CourseMemberships memberships) {
		this.queries = queries;
		this.enrollments = enrollments;
		this.memberships = memberships;
	}

	/** 필터 선택지: 정규·특별 학기 전체와, 고른 학기(없으면 현재 학기)에 과목이 있는 학과. */
	@GetMapping("/api/catalog/filters")
	ResponseEntity<Filters> filters(@RequestParam(required = false) List<Long> term) {
		List<Long> terms = terms(term);
		return ok(new Filters(this.queries.terms(), this.queries.departments(terms)));
	}

	/**
	 * 정규과목 목록. term을 주지 않으면 현재 학기다. 학과와 학점은 여러 개를 주면 그중 하나에 맞는 과목이다. q는 과목명이나
	 * 학수번호-분반에 들어 있는 글자를 찾는다. 정렬은 학기, 학수번호, 분반, 과목 ID다.
	 */
	@GetMapping("/api/catalog/courses")
	ResponseEntity<Page<RegularCourseItem>> courses(@RequestParam(required = false) List<Long> term,
			@RequestParam(required = false) String orgSect, @RequestParam(required = false) List<String> department,
			@RequestParam(required = false) List<BigDecimal> credit,
			@RequestParam(defaultValue = "false") boolean auditable, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "40") int size) {
		checkPage(page, size);
		if (orgSect != null && !ORG_SECTS.contains(orgSect)) {
			throw ApiException.invalid("orgSect는 A, G, F 중 하나여야 합니다.");
		}
		List<BigDecimal> credits = credit == null ? List.of() : credit;
		if (credits.size() > MAX_VALUES || credits.stream()
			.anyMatch((c) -> c == null || c.signum() < 0 || c.compareTo(MAX_CREDIT) > 0 || c.stripTrailingZeros().scale() > 1)) {
			throw ApiException.invalid("credit 값이 올바르지 않습니다.");
		}
		RegularFilter filter = new RegularFilter(terms(term), orgSect, codes(department, "department"), credits,
				auditable, query(q));
		Slice<CourseRow> slice = this.queries.regularCourses(filter, (page - 1) * size, size);
		List<Long> ids = CatalogQueries.ids(slice.rows());
		Map<Long, List<String>> instructors = this.queries.instructorNames(ids);
		Map<Long, List<ScheduleView>> schedules = this.queries.schedules(ids);
		return ok(new Page<>(slice.rows().stream().map((row) -> regularItem(row, instructors, schedules)).toList(),
				slice.total(), page, size, null));
	}

	/** 정규과목 상세(공개 항목)와 요청한 사람의 역할·청강 신청 상태. */
	@GetMapping("/api/catalog/courses/{courseId}")
	ResponseEntity<RegularCourseDetail> course(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		CourseRow row = this.queries.regularCourse(courseId).orElseThrow(ApiException::notFound);
		List<Long> ids = List.of(courseId);
		RegularCourseItem item = regularItem(row, this.queries.instructorNames(ids), this.queries.schedules(ids));
		Optional<CourseRole> role = this.memberships.roleOf(actor.userId(), courseId);
		Optional<Enrollment> audit = this.enrollments.latest(courseId, actor.userId(), EnrollmentService.AUDIT);
		LocalDateTime now = this.queries.now();
		AuditState state = audit.map((e) -> new AuditState(e.enrollmentId(), e.status(), e.version(),
				this.enrollments.auditReason(e.enrollmentId()).orElse(null),
				OfferingRules.cancellable(e.kind(), e.status(), null, null, now)))
			.orElse(null);
		List<String> reasons = new ArrayList<>();
		if (!row.auditAllowed() || !OfferingRules.OPEN.equals(row.status())) {
			reasons.add("audit_not_allowed");
		}
		if (role.isPresent()) {
			reasons.add("already_member");
		}
		if (audit.filter(Enrollment::active).isPresent()) {
			reasons.add(OfferingRules.ALREADY_APPLIED);
		}
		return ok(new RegularCourseDetail(item,
				new RegularViewer(role.map(Enum::name).orElse(null), state, reasons.isEmpty(), List.copyOf(reasons))));
	}

	/**
	 * 비정규과목 목록. open=true면 지금 접수 중인 과정만, audience는 신청대상 코드 하나, division은 비정규 분류(B|C|K),
	 * category는 과목분류 코드, lectureType은 수업형태다. 정렬은 접수 시작 일시 역순이다. 접수 상태(state)와 serverTime은 같은 DB
	 * 시각으로 정한다.
	 */
	@GetMapping("/api/catalog/offerings")
	ResponseEntity<Page<OfferingItem>> offerings(@RequestParam(defaultValue = "false") boolean open,
			@RequestParam(required = false) String audience, @RequestParam(required = false) String division,
			@RequestParam(required = false) List<String> category,
			@RequestParam(required = false) List<String> lectureType, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "40") int size) {
		checkPage(page, size);
		if (audience != null && !AUDIENCES.contains(audience)) {
			throw ApiException.invalid("audience는 1~6 중 하나여야 합니다.");
		}
		if (division != null && !DIVISIONS.contains(division)) {
			throw ApiException.invalid("division은 B, C, K 중 하나여야 합니다.");
		}
		List<String> lectureTypes = lectureType == null ? List.of() : lectureType;
		if (lectureTypes.size() > LECTURE_TYPES.size() || !LECTURE_TYPES.containsAll(lectureTypes)) {
			throw ApiException.invalid("lectureType 값이 올바르지 않습니다.");
		}
		OfferingFilter filter = new OfferingFilter(open, audience, division, codes(category, "category"),
				lectureTypes, query(q));
		LocalDateTime now = this.queries.now();
		Slice<OfferingRow> slice = this.queries.offerings(filter, now, (page - 1) * size, size);
		List<Long> ids = CatalogQueries.ids(slice.rows());
		Map<Long, List<String>> instructors = this.queries.instructorNames(ids);
		Map<Long, List<String>> audiences = this.queries.audiences(ids);
		return ok(new Page<>(
				slice.rows().stream().map((row) -> offeringItem(row, now, instructors, audiences)).toList(),
				slice.total(), page, size, now));
	}

	/**
	 * 비정규과목 상세와 신청 미리보기. eligible과 reasons는 표시용이다. 신청 명령이 같은 규칙으로 다시 판정한다.
	 */
	@GetMapping("/api/catalog/offerings/{courseId}")
	ResponseEntity<OfferingDetail> offering(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		OfferingRow row = this.queries.offering(courseId).orElseThrow(ApiException::notFound);
		LocalDateTime now = this.queries.now();
		List<Long> ids = List.of(courseId);
		Map<Long, List<String>> audiences = this.queries.audiences(ids);
		OfferingItem item = offeringItem(row, now, this.queries.instructorNames(ids), audiences);
		Optional<Enrollment> mine = this.enrollments.latest(courseId, actor.userId(), EnrollmentService.NONREGULAR);
		boolean instructor = this.memberships.roleOf(actor.userId(), courseId)
			.filter((r) -> r == CourseRole.INSTRUCTOR)
			.isPresent();
		List<String> reasons = OfferingRules.refusals(row, now, actor.role(), item.audiences(), instructor,
				mine.filter(Enrollment::active).isPresent());
		MyApplication application = mine.map((e) -> new MyApplication(e.enrollmentId(), e.status(), e.version(),
				OfferingRules.cancellable(e.kind(), e.status(), row.cancelAllowed(), row.cancelCloseAt(), now)))
			.orElse(null);
		return ok(new OfferingDetail(item, new OfferingViewer(application, reasons.isEmpty(), reasons), now));
	}

	private List<Long> terms(List<Long> term) {
		if (term == null || term.isEmpty()) {
			return this.queries.currentTermIds();
		}
		if (term.size() > 10 || term.stream().anyMatch((t) -> t == null || t < 1)) {
			throw ApiException.invalid("term 값이 올바르지 않습니다.");
		}
		return term.stream().distinct().toList();
	}

	private static List<String> codes(List<String> values, String name) {
		if (values == null) {
			return List.of();
		}
		if (values.size() > MAX_VALUES || values.stream().anyMatch((v) -> v == null || !CODE.matcher(v).matches())) {
			throw ApiException.invalid(name + " 값이 올바르지 않습니다.");
		}
		return values.stream().distinct().toList();
	}

	/** 앞뒤 공백을 지운 검색어. 비어 있으면 null이다. */
	private static String query(String q) {
		if (q == null || q.isBlank()) {
			return null;
		}
		String text = q.strip();
		if (text.length() > MAX_QUERY || text.codePoints().anyMatch(Character::isISOControl)) {
			throw ApiException.invalid("검색어는 " + MAX_QUERY + "자 이하여야 합니다.");
		}
		return text;
	}

	private static void checkPage(int page, int size) {
		if (page < 1 || page > MAX_PAGE || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
	}

	private static RegularCourseItem regularItem(CourseRow row, Map<Long, List<String>> instructors,
			Map<Long, List<ScheduleView>> schedules) {
		return new RegularCourseItem(row.courseId(), row.key(), row.name(), row.code(), row.section(), row.orgSect(),
				row.term(), row.departmentCode(), row.departmentName(), row.credit(), row.completionType(),
				row.gradeYear(), row.lectureType(), row.auditAllowed(), row.merged(),
				instructors.getOrDefault(row.courseId(), List.of()), schedules.getOrDefault(row.courseId(), List.of()));
	}

	private static OfferingItem offeringItem(OfferingRow row, LocalDateTime now, Map<Long, List<String>> instructors,
			Map<Long, List<String>> audiences) {
		return new OfferingItem(row.courseId(), row.key(), row.name(), row.code(), row.section(), row.roundYear(),
				row.roundNo(), row.roundLabel(), row.division(), row.categoryCode(), row.lectureType(),
				OfferingRules.autoApproval(row) ? "AUTO" : "MANUAL", instructors.getOrDefault(row.courseId(), List.of()),
				audiences.getOrDefault(row.courseId(), List.of()), row.learningStartDate(), row.learningEndDate(),
				row.applicationOpenAt(), row.applicationCloseAt(), row.cancelCloseAt(), row.cancelAllowed(),
				OfferingRules.state(row, now), row.capacity(), row.held());
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	record Filters(List<TermView> terms, List<Department> departments) {
	}

	/**
	 * @param serverTime 비정규 목록에서 접수 상태를 정한 DB 시각. 정규 목록은 null
	 */
	record Page<T>(List<T> items, long total, int page, int size, LocalDateTime serverTime) {
	}

	/**
	 * @param key 과목 키(KJKEY). 없으면 null
	 * @param orgSect 대학구분 A 대학|G 대학원|F 미래융합대학
	 * @param merged 합반 과목인지
	 */
	record RegularCourseItem(long courseId, String key, String name, String code, String section, String orgSect,
			TermView term, String departmentCode, String departmentName, BigDecimal credit, String completionType,
			Integer gradeYear, String lectureType, boolean auditAllowed, boolean merged, List<String> instructors,
			List<ScheduleView> schedules) {
	}

	record RegularCourseDetail(RegularCourseItem course, RegularViewer viewer) {
	}

	/**
	 * @param role 이 과목에서 요청한 사람의 역할(INSTRUCTOR|STUDENT). 구성원이 아니면 null
	 * @param audit 본인의 가장 최근 청강 신청. 없으면 null
	 * @param canRequestAudit 지금 청강을 신청할 수 있는지. 아니면 reasons에 이유가 있다
	 * @param reasons audit_not_allowed|already_member|already_applied
	 */
	record RegularViewer(String role, AuditState audit, boolean canRequestAudit, List<String> reasons) {
	}

	record AuditState(long enrollmentId, String status, int version, String reason, boolean cancellable) {
	}

	/**
	 * @param division 비정규 분류 B 비교과|C 기초학력|K K-digital
	 * @param audiences 신청대상 코드(1 교수, 2 교직원, 3 학생, 4 졸업생, 5 조교, 6 대학원생)
	 * @param state 접수 상태 UPCOMING|OPEN|CLOSED
	 * @param capacity 정원. null이면 제한 없음
	 * @param enrolled 수강이 확정된 인원
	 */
	record OfferingItem(long courseId, String key, String name, String code, String section, int roundYear,
			int roundNo, String roundLabel, String division, String categoryCode, String lectureType,
			String approvalMode, List<String> instructors, List<String> audiences, LocalDate learningStartDate,
			LocalDate learningEndDate, LocalDateTime applicationOpenAt, LocalDateTime applicationCloseAt,
			LocalDateTime cancelCloseAt, boolean cancelAllowed, String state, Integer capacity, int enrolled) {
	}

	record OfferingDetail(OfferingItem offering, OfferingViewer viewer, LocalDateTime serverTime) {
	}

	/**
	 * @param application 본인의 가장 최근 신청. 없으면 null
	 * @param eligible 지금 신청할 수 있는지(표시용)
	 * @param reasons not_open_yet|application_closed|not_target|own_course|already_applied|seat_full
	 */
	record OfferingViewer(MyApplication application, boolean eligible, List<String> reasons) {
	}

	/**
	 * @param status PENDING|WAITLISTED|ENROLLED|REJECTED|CANCELLED
	 */
	record MyApplication(long enrollmentId, String status, int version, boolean cancellable) {
	}

}
