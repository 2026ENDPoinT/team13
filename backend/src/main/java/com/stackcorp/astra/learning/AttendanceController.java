package com.stackcorp.astra.learning;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserDirectory;
import com.stackcorp.astra.identity.api.UserDirectory.UserCard;
import com.stackcorp.astra.learning.AttendanceCodes.Method;
import com.stackcorp.astra.learning.AttendanceCodes.Status;
import com.stackcorp.astra.learning.AttendancePolicy.Settings;
import com.stackcorp.astra.learning.AttendanceQueries.SessionRow;
import com.stackcorp.astra.learning.AttendanceService.Cell;
import com.stackcorp.astra.learning.AttendanceService.SessionInput;
import com.stackcorp.astra.learning.api.AttendanceSummaries.AttendanceSummary;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 강의실 출석부(EP-0011, EP-0012의 대체 계약)와 출석 차시 관리. 과목은 경로로 명시하고(SC-03), 구성원이 아니거나 그 과목의 차시가
 * 아니면 404다.
 * <ul>
 * <li>학생은 자기 기록과 요약만 본다. 다른 학생의 기록은 어느 경로로도 보이지 않는다.</li>
 * <li>담당자는 차시를 만들고 고치고 지우며, 수강생 × 차시 출석부를 쪽 단위로 보고, 상태를 사유와 함께 정한다.</li>
 * <li>상태 판정의 우선순위: 담당자 지정 &gt; 자동 판정(온라인 학습, 스마트출석). 자동 판정은 빈 칸만 채운다(ADR-0014, ADR-0030).</li>
 * </ul>
 * 사유와 같은 입력은 일반 텍스트로만 받는다. 학교가 정하지 않은 정책 값(지각 기준, 회차 시간, 지각 환산)은 과목 설정이다.
 */
@RestController
class AttendanceController {

	private static final int MAX_WEEK = 60;

	private static final int MAX_SESSION_NO = 20;

	private static final int MAX_REASON = 200;

	private static final int MAX_PAGE_SIZE = 100;

	private static final int DEFAULT_PAGE_SIZE = 50;

	private final LearningAccess access;

	private final LearningClock clock;

	private final AttendanceQueries queries;

	private final AttendanceService service;

	private final AttendancePolicy policy;

	private final CourseRosters rosters;

	private final UserDirectory users;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final SchoolScreens school;

	AttendanceController(LearningAccess access, LearningClock clock, AttendanceQueries queries,
			AttendanceService service, AttendancePolicy policy, CourseRosters rosters, UserDirectory users,
			RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit, SchoolScreens school) {
		this.access = access;
		this.clock = clock;
		this.queries = queries;
		this.service = service;
		this.policy = policy;
		this.rosters = rosters;
		this.users = users;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
		this.school = school;
	}

	/**
	 * 출석부(EP-0011). 학생에게는 차시마다 자기 상태와 요약을, 담당자에게는 차시마다 상태별 수를 주차별로 돌려준다.
	 * {@code week}를 주면 그 주차만 보인다. 요약은 언제나 과목 전체다. 학교에서 읽는 과목(ADR-0043)의 학생에게는 학생 본인의 학교
	 * 출석을 그 학생의 학교 session으로 읽어 같은 모양으로 준다({@code origin: "school"}).
	 */
	@GetMapping("/api/courses/{courseId}/attendance")
	ResponseEntity<?> attendance(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(required = false) Integer week) {
		CourseRole role = this.access.roleIn(actor.userId(), courseId).orElseThrow(ApiException::notFound);
		if (week != null && (week < 1 || week > MAX_WEEK)) {
			throw ApiException.invalid("week는 1 이상 " + MAX_WEEK + " 이하여야 합니다.");
		}
		Optional<SchoolScreens.AttendanceView> school = this.school.attendance(actor, courseId, week, this.clock.now());
		if (school.isPresent()) {
			return ok(school.get());
		}
		boolean instructor = role == CourseRole.INSTRUCTOR;
		Settings settings = this.policy.of(courseId);
		Map<Long, Cell> mine = instructor ? Map.of() : this.queries.recordsOf(courseId, actor.userId());
		Map<Long, Map<String, Integer>> counts = instructor ? this.queries.countsBySession(courseId) : Map.of();
		Map<Integer, List<SessionView>> weeks = new LinkedHashMap<>();
		for (SessionRow row : this.queries.sessions(courseId, week)) {
			RecordView record = instructor ? null : RecordView.of(mine.get(row.sessionId()));
			Map<String, Integer> sessionCounts = instructor ? counts.getOrDefault(row.sessionId(), Map.of()) : null;
			weeks.computeIfAbsent(row.weekNo(), (n) -> new ArrayList<>())
				.add(SessionView.of(row, record, sessionCounts));
		}
		AttendanceSummary summary = instructor ? null : summary(courseId, this.queries.sessions(courseId, null), mine,
				settings);
		List<WeekView> body = weeks.entrySet().stream().map((e) -> new WeekView(e.getKey(), e.getValue())).toList();
		return ok(new AttendanceView(role.name(), this.clock.now(), SettingsView.of(settings), summary, body));
	}

	/**
	 * 담당자용 수강생 × 차시 출석부. 학생 행만 쪽으로 나눈다(page는 1부터, size는 {@value #MAX_PAGE_SIZE}까지). 정렬은
	 * {@code studentNo}(학번) 또는 {@code name}이고 같으면 사용자 ID 순서다(SC-09). 칸마다 version을 주며, 기록이 없는 칸은 0이다.
	 */
	@GetMapping("/api/courses/{courseId}/attendance/roster")
	ResponseEntity<RosterView> roster(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
			@RequestParam(defaultValue = "studentNo") String sort) {
		requireInstructor(actor, courseId);
		if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		Comparator<UserCard> order = switch (sort) {
			case "studentNo" -> Comparator.comparing(UserCard::externalRef, Comparator.nullsLast(Comparator.naturalOrder()));
			case "name" -> Comparator.comparing(UserCard::name, Comparator.nullsLast(Comparator.naturalOrder()));
			default -> throw ApiException.invalid("sort는 studentNo 또는 name이어야 합니다.");
		};
		List<Long> students = this.rosters.students(courseId);
		Map<Long, UserCard> cards = this.users.find(students);
		List<UserCard> sorted = students.stream()
			.map((id) -> cards.getOrDefault(id, new UserCard(id, null, null)))
			.sorted(order.thenComparingLong(UserCard::userId))
			.toList();
		int from = (int) Math.min((long) (page - 1) * size, sorted.size());
		List<UserCard> slice = sorted.subList(from, Math.min(from + size, sorted.size()));
		List<SessionRow> sessions = this.queries.sessions(courseId, null);
		Map<Long, Map<Long, Cell>> records = this.queries
			.recordsOf(courseId, slice.stream().map(UserCard::userId).toList());
		Settings settings = this.policy.of(courseId);
		List<StudentRow> rows = slice.stream().map((card) -> {
			Map<Long, Cell> mine = records.getOrDefault(card.userId(), Map.of());
			List<CellView> cells = sessions.stream()
				.map((s) -> CellView.of(s.sessionId(), mine.get(s.sessionId())))
				.toList();
			return new StudentRow(card.userId(), card.name(), card.externalRef(), cells,
					summary(courseId, sessions, mine, settings));
		}).toList();
		List<SessionView> columns = sessions.stream().map((s) -> SessionView.of(s, null, null)).toList();
		return ok(new RosterView(page, size, sorted.size(), sort, columns, rows));
	}

	@GetMapping("/api/courses/{courseId}/attendance/settings")
	ResponseEntity<SettingsView> settings(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		this.access.roleIn(actor.userId(), courseId).orElseThrow(ApiException::notFound);
		Settings settings = this.policy.of(courseId);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(Versions.etag(settings.version()))
			.body(SettingsView.of(settings));
	}

	/** 과목 출석 정책. If-Match에 읽은 version(설정 전이면 0)을 보낸다. 진행 중인 회차의 판정은 바뀌지 않는다. */
	@PutMapping("/api/courses/{courseId}/attendance/settings")
	ResponseEntity<SettingsView> updateSettings(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody SettingsBody body) {
		requireInstructor(actor, courseId);
		int version = Versions.expected(ifMatch);
		AttendancePolicy.Values values = AttendancePolicy.validated(body.lateAfterMinutes(), body.roundMinutes(),
				body.latesPerAbsence());
		this.rateLimiter.check("attendance-write", actor.userId(), 120, Duration.ofMinutes(10));
		Settings settings = this.policy.update(actor.userId(), courseId, version, values);
		this.audit.success(actor.userId(), actor.sessionId(), "attendance_settings.update", "course", courseId, courseId);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(Versions.etag(settings.version()))
			.body(SettingsView.of(settings));
	}

	/**
	 * 차시 등록(EP-0012 대체 계약의 관리 부분). 같은 주차에 같은 차시 번호는 하나다(보강 차시는 따로 센다). 보강 차시는 휴강한 차시를
	 * {@code makeupOfSessionId}로 가리킨다. {@code Idempotency-Key}를 보내면 재전송이 차시를 더 만들지 않는다.
	 */
	@PostMapping("/api/courses/{courseId}/attendance-sessions")
	ResponseEntity<SessionView> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody CreateSession body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		requireInstructor(actor, courseId);
		SessionInput input = validated(body.weekNo(), body.sessionNo(), body.classDate(), body.startTime(),
				body.endTime(), body.method());
		if (body.makeupOfSessionId() != null && body.makeupOfSessionId() < 1) {
			throw ApiException.invalid("makeupOfSessionId가 올바르지 않습니다.");
		}
		this.rateLimiter.check("attendance-write", actor.userId(), 120, Duration.ofMinutes(10));
		// 출석 반영 다시 판정이 하트비트·일괄 지정과 교착하면 멱등 원장·차시·감사 기록이 함께 되돌아가므로 처음부터 다시 실행한다.
		SessionView created = DeadlockRetry.run(() -> this.idempotency.execute(actor.userId(),
				"attendance_session.create", idempotencyKey,
				List.of(courseId, input, body.makeupOfSessionId() == null ? 0L : body.makeupOfSessionId()),
				SessionView.class, () -> {
					long id = this.service.create(actor.userId(), courseId, input, body.makeupOfSessionId());
					this.audit.success(actor.userId(), actor.sessionId(), "attendance_session.create",
							"attendance_session", id, courseId);
					return session(courseId, id);
				}));
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.eTag(Versions.etag(created.version()))
			.body(created);
	}

	/** 차시 수정. 본문은 수정한 뒤의 전체 상태다. {@code cancelled: true}면 휴강이다. If-Match 필수. */
	@PutMapping("/api/courses/{courseId}/attendance-sessions/{sessionId}")
	ResponseEntity<SessionView> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long sessionId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch,
			@RequestBody UpdateSession body) {
		requireInstructor(actor, courseId);
		int version = Versions.expected(ifMatch);
		SessionInput input = validated(body.weekNo(), body.sessionNo(), body.classDate(), body.startTime(),
				body.endTime(), body.method());
		if (body.cancelled() == null) {
			throw ApiException.invalid("cancelled 값이 필요합니다.");
		}
		this.rateLimiter.check("attendance-write", actor.userId(), 120, Duration.ofMinutes(10));
		DeadlockRetry.run(() -> this.service.update(actor, courseId, sessionId, version, input, body.cancelled()));
		SessionView updated = session(courseId, sessionId);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(Versions.etag(updated.version()))
			.body(updated);
	}

	/** 차시 삭제. 출석 기록, 스마트출석 회차, 보강 차시, 연결된 학습활동이 있으면 409다. If-Match 필수. */
	@DeleteMapping("/api/courses/{courseId}/attendance-sessions/{sessionId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long sessionId, @RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		requireInstructor(actor, courseId);
		int version = Versions.expected(ifMatch);
		this.rateLimiter.check("attendance-write", actor.userId(), 120, Duration.ofMinutes(10));
		DeadlockRetry.run(() -> this.service.delete(actor, courseId, sessionId, version));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/**
	 * 한 학생의 출석 상태를 정하거나 고친다. 상태는 present|late|absent|excused, 사유는 필수 일반 텍스트
	 * {@value #MAX_REASON}자다. If-Match에 출석부에서 읽은 칸의 version(기록이 없으면 0)을 보낸다. 대상은 이 과목의 수강생만 된다.
	 */
	@PutMapping("/api/courses/{courseId}/attendance-sessions/{sessionId}/records/{studentId}")
	ResponseEntity<CellView> set(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long sessionId, @PathVariable long studentId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch, @RequestBody RecordBody body) {
		requireInstructor(actor, courseId);
		int version = Versions.expected(ifMatch);
		Status status = status(body.status());
		String reason = LearningText.required(body.reason(), "사유", MAX_REASON);
		this.rateLimiter.check("attendance-record", actor.userId(), 600, Duration.ofMinutes(10));
		Cell cell = this.service.set(actor, courseId, sessionId, studentId, version, status, reason);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.eTag(Versions.etag(cell.version()))
			.body(CellView.of(sessionId, cell));
	}

	/** 판정 전(기록 없음, 미처리)인 수강생 모두를 한 상태로 채운다. 이미 판정된 학생은 바꾸지 않는다. */
	@PostMapping("/api/courses/{courseId}/attendance-sessions/{sessionId}/records/fill")
	ResponseEntity<FillResult> fill(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long sessionId, @RequestBody RecordBody body) {
		requireInstructor(actor, courseId);
		Status status = status(body.status());
		String reason = LearningText.required(body.reason(), "사유", MAX_REASON);
		this.rateLimiter.check("attendance-fill", actor.userId(), 30, Duration.ofMinutes(10));
		return ok(new FillResult(this.service.fill(actor, courseId, sessionId, status, reason)));
	}

	private SessionView session(long courseId, long sessionId) {
		return this.queries.sessions(courseId, null)
			.stream()
			.filter((s) -> s.sessionId() == sessionId)
			.findFirst()
			.map((s) -> SessionView.of(s, null, null))
			.orElseThrow(ApiException::notFound);
	}

	private void requireInstructor(Actor actor, long courseId) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
	}

	private static AttendanceSummary summary(long courseId, List<SessionRow> sessions, Map<Long, Cell> records,
			Settings settings) {
		int counted = 0;
		int[] byStatus = new int[Status.values().length];
		for (SessionRow session : sessions) {
			if (session.cancelled()) {
				continue;
			}
			counted++;
			Cell cell = records.get(session.sessionId());
			if (cell != null) {
				byStatus[Status.ofDb(cell.status()).ordinal()]++;
			}
		}
		return AttendanceQueries.summary(courseId, counted, byStatus[Status.PRESENT.ordinal()],
				byStatus[Status.LATE.ordinal()], byStatus[Status.ABSENT.ordinal()], byStatus[Status.EXCUSED.ordinal()],
				settings.latesPerAbsence());
	}

	private static Status status(String code) {
		return Status.decidable(code)
			.orElseThrow(() -> ApiException.invalid("status는 present, late, absent, excused 중 하나여야 합니다."));
	}

	private static SessionInput validated(Integer weekNo, Integer sessionNo, LocalDate classDate, LocalTime startTime,
			LocalTime endTime, String method) {
		if (weekNo == null || weekNo < 1 || weekNo > MAX_WEEK) {
			throw ApiException.invalid("weekNo는 1 이상 " + MAX_WEEK + " 이하여야 합니다.");
		}
		if (sessionNo == null || sessionNo < 1 || sessionNo > MAX_SESSION_NO) {
			throw ApiException.invalid("sessionNo는 1 이상 " + MAX_SESSION_NO + " 이하여야 합니다.");
		}
		if (startTime != null && endTime != null && !startTime.isBefore(endTime)) {
			throw ApiException.invalid("startTime은 endTime보다 앞서야 합니다.");
		}
		Method parsed = Method.of(method)
			.orElseThrow(() -> ApiException.invalid("method는 smart, online, manual 중 하나여야 합니다."));
		return new SessionInput(weekNo, sessionNo, classDate, startTime, endTime, parsed);
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/** 요청 본문. 만든 사람과 과목은 session과 경로에서 정한다(SC-02). */
	record CreateSession(Integer weekNo, Integer sessionNo, LocalDate classDate, LocalTime startTime, LocalTime endTime,
			String method, Long makeupOfSessionId) {
	}

	/** 수정 요청 본문. 모든 값을 보낸다. 빠진 선택 값(수업일, 시각)은 비운다는 뜻이다. 보강 관계는 바꾸지 않는다. */
	record UpdateSession(Integer weekNo, Integer sessionNo, LocalDate classDate, LocalTime startTime, LocalTime endTime,
			String method, Boolean cancelled) {
	}

	record RecordBody(String status, String reason) {
	}

	record SettingsBody(Integer lateAfterMinutes, Integer roundMinutes, Integer latesPerAbsence) {
	}

	record FillResult(int updated) {
	}

	/**
	 * @param role 요청한 사람의 역할. INSTRUCTOR|STUDENT
	 * @param summary 학생 본인의 요약. 담당자에게는 null
	 */
	record AttendanceView(String role, LocalDateTime serverTime, SettingsView settings, AttendanceSummary summary,
			List<WeekView> weeks) {
	}

	record WeekView(int weekNo, List<SessionView> sessions) {
	}

	/**
	 * @param method smart|online|manual
	 * @param record 학생 본인의 기록. 담당자에게는 null
	 * @param counts 상태 코드별 기록 수. 담당자에게만 있다
	 * @param version 차시 수정·삭제 때 If-Match로 보내는 값
	 */
	record SessionView(long sessionId, int weekNo, int sessionNo, LocalDate classDate, LocalTime startTime,
			LocalTime endTime, String method, boolean cancelled, boolean makeup, Long makeupOfSessionId,
			RecordView record, Map<String, Integer> counts, int version) {

		static SessionView of(SessionRow row, RecordView record, Map<String, Integer> counts) {
			return new SessionView(row.sessionId(), row.weekNo(), row.sessionNo(), row.classDate(), row.startTime(),
					row.endTime(), Method.code(row.method()), row.cancelled(), row.makeup(), row.makeupOfSessionId(),
					record, counts, row.version());
		}

	}

	/**
	 * @param status present|late|absent|excused|unmarked
	 * @param source smart|online|manual. 판정 전이면 null
	 */
	record RecordView(String status, String source, LocalDateTime decidedAt) {

		static RecordView of(Cell cell) {
			if (cell == null) {
				return new RecordView(Status.UNMARKED.code, null, null);
			}
			return new RecordView(Status.ofDb(cell.status()).code, cell.source(), cell.decidedAt());
		}

	}

	/**
	 * @param version 상태를 고칠 때 If-Match로 보내는 값. 기록이 없으면 0
	 */
	record CellView(long sessionId, String status, String source, LocalDateTime decidedAt, int version) {

		static CellView of(long sessionId, Cell cell) {
			RecordView record = RecordView.of(cell);
			return new CellView(sessionId, record.status(), record.source(), record.decidedAt(),
					cell == null ? 0 : cell.version());
		}

	}

	/**
	 * @param studentNo 학사 식별자(학번). 담당자에게만 보인다
	 */
	record StudentRow(long userId, String name, String studentNo, List<CellView> cells, AttendanceSummary summary) {
	}

	/**
	 * @param total 수강생 수
	 * @param sessions 열 순서. 학생 행의 cells가 같은 순서다
	 */
	record RosterView(int page, int size, int total, String sort, List<SessionView> sessions,
			List<StudentRow> students) {
	}

	/**
	 * @param lateAfterMinutes 스마트출석을 연 뒤 이 시간(분)이 지나면 지각
	 * @param roundMinutes 스마트출석 회차의 기본 진행 시간(분)
	 * @param latesPerAbsence 지각 몇 번을 결석 한 번으로 칠지. 0이면 환산하지 않는다
	 * @param version If-Match로 보내는 값. 정하지 않은 과목은 0
	 */
	record SettingsView(int lateAfterMinutes, int roundMinutes, int latesPerAbsence, int version) {

		static SettingsView of(Settings settings) {
			return new SettingsView(settings.lateAfterMinutes(), settings.roundMinutes(), settings.latesPerAbsence(),
					settings.version());
		}

	}

}
