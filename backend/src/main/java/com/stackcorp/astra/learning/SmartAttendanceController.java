package com.stackcorp.astra.learning;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.AttendanceController.RecordView;
import com.stackcorp.astra.learning.AttendancePolicy.Settings;
import com.stackcorp.astra.learning.SmartAttendanceService.Counts;
import com.stackcorp.astra.learning.SmartAttendanceService.OpenInput;
import com.stackcorp.astra.learning.SmartAttendanceService.Outcome;
import com.stackcorp.astra.learning.SmartAttendanceService.QrToken;
import com.stackcorp.astra.learning.SmartAttendanceService.Round;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * 스마트출석 PIN/QR(EP-0013, EP-0014, EP-0015와 smarta 소켓의 대체 계약, SEC-06, SEC-12, ADR-0030).
 * <ul>
 * <li>회차를 열고 닫고 취소하는 것은 그 과목 담당자만 한다. 학생 화면은 회차 상태를 바꾸지 못한다(LEARN-10).</li>
 * <li>PIN과 QR token은 서버가 만들고 담당자 화면에만 보인다. QR은 token 문자열만 주고 그림은 화면이 그린다.</li>
 * <li>학생은 PIN이나 QR token을 제출하고, 서버가 그 자리에서 검증해 기록한다. 제출한 사람은 session principal이다. 요청에 사용자,
 * 회차 상태, 시각을 넣을 자리가 없다.</li>
 * <li>실시간 신호는 WebSocket broker 없이 조회로 한다(architecture.md 8절). 화면은 응답의 pollAfterSec마다 다시 묻는다. 출석 완료 표시는
 * {@code GET …/me}의 state가 confirmed이고 서버 기록이 있을 때만 한다(SEC-06).</li>
 * </ul>
 */
@RestController
class SmartAttendanceController {

	private static final Pattern PIN = Pattern.compile("[0-9]{4,8}");

	private static final Pattern QR_TOKEN = Pattern.compile("[0-9A-Za-z._-]{1,40}");

	private final LearningAccess access;

	private final LearningClock clock;

	private final SmartAttendanceService service;

	private final AttendancePolicy policy;

	private final CourseRosters rosters;

	private final SmartAttendanceProperties properties;

	private final RateLimiter rateLimiter;

	SmartAttendanceController(LearningAccess access, LearningClock clock, SmartAttendanceService service,
			AttendancePolicy policy, CourseRosters rosters, SmartAttendanceProperties properties,
			RateLimiter rateLimiter) {
		this.access = access;
		this.clock = clock;
		this.service = service;
		this.policy = policy;
		this.rosters = rosters;
		this.properties = properties;
		this.rateLimiter = rateLimiter;
	}

	/**
	 * 회차를 연다. {@code method}는 pin|qr이다. 진행 시간과 지각 기준을 비우면 과목 출석 정책을 쓴다. {@code groupId}를 주면 그 그룹의
	 * 수강생만 대상이다. 차시마다 진행 중 회차는 하나다(409 round_open). 휴강한 차시에는 열지 않는다.
	 */
	@PostMapping("/api/courses/{courseId}/attendance-sessions/{sessionId}/smart-rounds")
	ResponseEntity<RoundView> open(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long sessionId, @RequestBody OpenRound body) {
		requireInstructor(actor, courseId);
		OpenInput input = validated(body, this.policy.of(courseId));
		this.rateLimiter.check("smart-attendance-open", actor.userId(), 30, Duration.ofMinutes(10));
		Round round = this.service.open(actor, courseId, sessionId, input);
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(view(round, this.clock.now()));
	}

	/** 지금 진행 중인 과목의 회차. 담당자에게는 회차 화면(rounds), 학생에게는 회차마다 자기 상태(mine)다. */
	@GetMapping("/api/courses/{courseId}/smart-rounds/active")
	ResponseEntity<ActiveView> active(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		CourseRole role = this.access.roleIn(actor.userId(), courseId).orElseThrow(ApiException::notFound);
		LocalDateTime now = this.clock.now();
		List<Round> rounds = this.service.active(courseId, now);
		if (role == CourseRole.INSTRUCTOR) {
			return ok(new ActiveView(role.name(), now, pollAfter(), rounds.stream().map((r) -> view(r, now)).toList(),
					List.of()));
		}
		boolean student = this.rosters.students(courseId).contains(actor.userId());
		return ok(new ActiveView(role.name(), now, pollAfter(), List.of(),
				rounds.stream().map((r) -> mine(r, actor.userId(), student, now)).toList()));
	}

	/** 담당자의 회차 화면. 진행 중이면 지금 PIN 또는 QR token과 다음 교체 시각, 실시간 수를 준다. */
	@GetMapping("/api/courses/{courseId}/smart-rounds/{roundId}")
	ResponseEntity<RoundView> round(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long roundId) {
		requireInstructor(actor, courseId);
		Round round = this.service.find(courseId, roundId).orElseThrow(ApiException::notFound);
		return ok(view(round, this.clock.now()));
	}

	/**
	 * 학생 본인의 회차 상태. confirmed(이 회차에서 출석이 확정됨, attendance에 서버 기록), pending(제출 전), locked(틀린 횟수 한도),
	 * expired(시간 지남), closed(담당자가 마감), cancelled(담당자가 취소), not_target(대상 아님) 중 하나다.
	 */
	@GetMapping("/api/courses/{courseId}/smart-rounds/{roundId}/me")
	ResponseEntity<StudentRoundView> me(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long roundId) {
		requireStudent(actor, courseId);
		Round round = this.service.find(courseId, roundId).orElseThrow(ApiException::notFound);
		boolean student = this.rosters.students(courseId).contains(actor.userId());
		return ok(mine(round, actor.userId(), student, this.clock.now()));
	}

	@PostMapping("/api/courses/{courseId}/smart-rounds/{roundId}/close")
	ResponseEntity<RoundView> close(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long roundId) {
		requireInstructor(actor, courseId);
		this.rateLimiter.check("smart-attendance-open", actor.userId(), 30, Duration.ofMinutes(10));
		return ok(view(this.service.close(actor, courseId, roundId), this.clock.now()));
	}

	/** 회차 취소. 이 회차로 확정된 출석 기록은 지워지고 학생 화면은 cancelled가 된다. */
	@PostMapping("/api/courses/{courseId}/smart-rounds/{roundId}/cancel")
	ResponseEntity<RoundView> cancel(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long roundId) {
		requireInstructor(actor, courseId);
		this.rateLimiter.check("smart-attendance-open", actor.userId(), 30, Duration.ofMinutes(10));
		return ok(view(this.service.cancel(actor, courseId, roundId), this.clock.now()));
	}

	/**
	 * 출석 제출. PIN 회차는 {@code pin}, QR 회차는 {@code qrToken} 하나만 보낸다. 확정되면 200과 confirmed 상태이고, 같은 회차에
	 * 다시 보내도 같은 결과다. 틀리면 422(bad_pin|bad_qr), 틀린 횟수 한도를 넘으면 429(attempts_locked), 마감·만료·취소 뒤나 대상이 아니면
	 * 409다. 실패한 시도도 기록에 남는다. 같은 회차에 다시 보내도 출석은 하나라 Idempotency-Key를 받지 않는다.
	 */
	@PostMapping("/api/courses/{courseId}/smart-rounds/{roundId}/check")
	ResponseEntity<StudentRoundView> check(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long roundId, @RequestBody CheckBody body) {
		requireStudent(actor, courseId);
		Round round = this.service.find(courseId, roundId).orElseThrow(ApiException::notFound);
		String pin = body.pin();
		String qrToken = body.qrToken();
		if ((pin == null) == (qrToken == null)) {
			throw ApiException.invalid("pin과 qrToken 중 하나만 보내야 합니다.");
		}
		if ("pin".equals(round.method()) ? pin == null || !PIN.matcher(pin).matches()
				: qrToken == null || !QR_TOKEN.matcher(qrToken).matches()) {
			throw ApiException.invalid("pin".equals(round.method()) ? "이 출석은 숫자 PIN으로 제출합니다." : "이 출석은 QR 코드로 제출합니다.");
		}
		this.rateLimiter.check("smart-attendance-check", actor.userId(), 20, Duration.ofMinutes(1));
		Outcome outcome = this.service.check(actor, courseId, roundId, pin, qrToken);
		switch (outcome.result()) {
			case OK, DUPLICATE -> {
				return ok(mine(outcome.round(), actor.userId(), true, outcome.now()));
			}
			case BAD_PIN -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "bad_pin", "PIN이 맞지 않습니다.");
			case BAD_QR -> throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "bad_qr",
					"QR 코드가 맞지 않거나 이미 바뀌었습니다. 화면의 QR 코드를 다시 읽어 주세요.");
			case LOCKED -> throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "attempts_locked",
					"틀린 횟수가 한도를 넘어 이번 출석에는 더 제출할 수 없습니다. 담당 교수에게 확인을 요청해 주세요.");
			case EXPIRED -> throw new ApiException(HttpStatus.CONFLICT, "round_expired", "출석 시간이 지났습니다.");
			case CLOSED -> throw new ApiException(HttpStatus.CONFLICT, "round_closed", "마감한 출석입니다.");
			case CANCELLED -> throw new ApiException(HttpStatus.CONFLICT, "round_cancelled", "취소한 출석입니다.");
			case NOT_TARGET -> throw new ApiException(HttpStatus.CONFLICT, "not_target", "출석 대상이 아닙니다.");
			default -> throw new IllegalStateException(outcome.result().name());
		}
	}

	private RoundView view(Round round, LocalDateTime now) {
		Counts counts = this.service.counts(round);
		QrToken qr = this.service.qr(round, now).orElse(null);
		return new RoundView(round.roundId(), round.sessionId(), round.groupId(), round.method(), round.pinLength(),
				round.displayState(now), round.openedAt(), round.lateAt(), round.endsAt(), round.closedAt(), now,
				this.service.pin(round, now).orElse(null), qr == null ? null : qr.token(),
				qr == null ? null : qr.refreshAt(), counts, pollAfter());
	}

	private StudentRoundView mine(Round round, long userId, boolean student, LocalDateTime now) {
		String state = this.service.stateOf(round, userId, student, now);
		RecordView attendance = "confirmed".equals(state)
				? this.service.record(round.sessionId(), userId).map(RecordView::of).orElse(null) : null;
		// 자기 시도만 센다. 다른 학생의 시도나 PIN에 관한 것은 없다(ADR-0030 3번).
		long failures = "not_target".equals(state) ? 0 : this.service.failures(round.roundId(), userId);
		return new StudentRoundView(round.roundId(), round.sessionId(), round.method(), round.pinLength(), state,
				round.openedAt(), round.lateAt(), round.endsAt(), now,
				"pending".equals(state) ? this.service.failuresLeft(round.roundId(), userId) : null, (int) failures,
				this.service.lockedOut(failures), attendance, pollAfter());
	}

	private int pollAfter() {
		return (int) this.properties.pollInterval().toSeconds();
	}

	private void requireInstructor(Actor actor, long courseId) {
		if (!this.access.canManage(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
	}

	private void requireStudent(Actor actor, long courseId) {
		if (this.access.roleIn(actor.userId(), courseId).filter((r) -> r == CourseRole.STUDENT).isEmpty()) {
			throw ApiException.notFound();
		}
	}

	private static OpenInput validated(OpenRound body, Settings settings) {
		if (!"pin".equals(body.method()) && !"qr".equals(body.method())) {
			throw ApiException.invalid("method는 pin 또는 qr이어야 합니다.");
		}
		int minutes = body.minutes() == null ? settings.roundMinutes() : body.minutes();
		if (minutes < 1 || minutes > AttendancePolicy.MAX_ROUND_MINUTES) {
			throw ApiException.invalid("minutes는 1 이상 " + AttendancePolicy.MAX_ROUND_MINUTES + " 이하여야 합니다.");
		}
		int lateAfter = body.lateAfterMinutes() == null ? Math.min(settings.lateAfterMinutes(), minutes)
				: body.lateAfterMinutes();
		if (lateAfter < 1 || lateAfter > minutes) {
			throw ApiException.invalid("lateAfterMinutes는 1 이상 minutes 이하여야 합니다.");
		}
		if (body.groupId() != null && body.groupId() < 1) {
			throw ApiException.invalid("groupId가 올바르지 않습니다.");
		}
		return new OpenInput(body.method(), minutes, lateAfter, body.groupId());
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/**
	 * @param minutes 진행 시간(분). 비우면 과목 정책
	 * @param lateAfterMinutes 지각 기준(분). 비우면 과목 정책
	 * @param groupId 대상 그룹. 비우면 수강생 전체
	 */
	record OpenRound(String method, Integer minutes, Integer lateAfterMinutes, Long groupId) {
	}

	/** 제출 본문. 사용자, 회차, 시각은 받지 않는다. */
	record CheckBody(String pin, String qrToken) {

		// 요청 본문을 로그에 찍는 설정에서도 PIN과 token이 나가지 않게 한다(SC-07).
		@Override
		public String toString() {
			return "CheckBody[]";
		}

	}

	/**
	 * @param state open|expired|closed|cancelled
	 * @param pin 진행 중인 PIN 회차의 PIN. 그 밖에는 null. PIN은 회차를 여는 순간 정해지고 회차가 끝날 때(endsAt이나 마감)까지 바뀌지
	 * 않는다. 그래서 PIN 회차의 qrRefreshAt은 null이다
	 * @param qrToken 진행 중인 QR 회차의 지금 token. 화면이 QR 코드로 그린다
	 * @param qrRefreshAt 다음 token으로 바뀌는 시각. 이 시각 뒤에 다시 조회한다. QR 회차에만 있다
	 * @param pollAfterSec 실시간 수(counts)를 다시 조회할 간격. PIN 교체 주기가 아니다
	 */
	record RoundView(long roundId, long sessionId, Long groupId, String method, Integer pinLength, String state,
			LocalDateTime openedAt, LocalDateTime lateAt, LocalDateTime endsAt, LocalDateTime closedAt,
			LocalDateTime serverTime, String pin, String qrToken, LocalDateTime qrRefreshAt, Counts counts,
			int pollAfterSec) {

		// 응답을 로그에 찍는 설정에서도 PIN과 QR token이 나가지 않게 한다(SC-07).
		@Override
		public String toString() {
			return "RoundView[roundId=" + this.roundId + ", state=" + this.state + "]";
		}

	}

	/**
	 * @param state confirmed|pending|locked|expired|closed|cancelled|not_target
	 * @param failuresLeft 남은 제출 기회. pending일 때만 있다
	 * @param failedAttempts 이 회차에 내가 낸 틀린 PIN·QR 수. 회차가 끝난 뒤에도 남는다. not_target이면 0
	 * @param lockedOut 틀린 횟수가 한도에 닿아 더 제출할 수 없었는지. expired·closed에서 true면 잠긴 뒤 끝난 것이고, false이고
	 * failedAttempts가 0이면 끝나기 전에 제출하지 않은 것이다
	 * @param attendance 서버가 확정한 이 차시의 기록. confirmed일 때만 있다
	 */
	record StudentRoundView(long roundId, long sessionId, String method, Integer pinLength, String state,
			LocalDateTime openedAt, LocalDateTime lateAt, LocalDateTime endsAt, LocalDateTime serverTime,
			Integer failuresLeft, int failedAttempts, boolean lockedOut, RecordView attendance, int pollAfterSec) {
	}

	record ActiveView(String role, LocalDateTime serverTime, int pollAfterSec, List<RoundView> rounds,
			List<StudentRoundView> mine) {
	}

}
