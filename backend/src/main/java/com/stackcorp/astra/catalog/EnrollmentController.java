package com.stackcorp.astra.catalog;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.stackcorp.astra.catalog.EnrollmentRecords.Request;
import com.stackcorp.astra.catalog.EnrollmentService.Receipt;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserDirectory;
import com.stackcorp.astra.identity.api.UserDirectory.UserCard;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.dao.TransientDataAccessException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 수강신청, 청강 신청, 취소, 담당자 승인·반려 명령(EP-0197, EP-0199, EP-0208, EP-0209, EP-0210의 대체 계약, CHG-03).
 * 원본은 신청 팝업을 여는 요청이 곧 등록이었다. 여기서는 조회(GET /api/catalog/...)가 아무것도 바꾸지 않고, 등록은 이 컨트롤러의
 * POST 명령으로만 일어난다(SC-04). 신청자는 session에서 정하고 학번, 과목 학사 키, 역할을 받지 않는다(SC-02).
 * 좌석을 잡는 신청은 Idempotency-Key가 반드시 있어야 한다.
 */
@RestController
class EnrollmentController {

	private static final int MAX_REASON = 1_000;

	private static final int MAX_DECISION_REASON = 500;

	private static final int MAX_PAGE_SIZE = 50;

	// 교착이나 잠금 대기 시간 초과로 되돌아간 명령은 남긴 것이 없으므로 처음부터 다시 실행한다.
	private static final int ATTEMPTS = 3;

	private final EnrollmentService service;

	private final EnrollmentRecords records;

	private final CatalogQueries queries;

	private final Idempotency idempotency;

	private final RateLimiter rateLimiter;

	private final UserDirectory users;

	EnrollmentController(EnrollmentService service, EnrollmentRecords records, CatalogQueries queries,
			Idempotency idempotency, RateLimiter rateLimiter, UserDirectory users) {
		this.service = service;
		this.records = records;
		this.queries = queries;
		this.idempotency = idempotency;
		this.rateLimiter = rateLimiter;
		this.users = users;
	}

	/** 비정규 과정 수강신청. 201. 같은 key의 재전송은 처음 결과를 다시 돌려받는다. key가 없으면 400이다. */
	@PostMapping("/api/catalog/offerings/{courseId}/enrollments")
	ResponseEntity<Receipt> apply(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		if (this.queries.offering(courseId).isEmpty()) {
			throw ApiException.notFound();
		}
		if (idempotencyKey == null) {
			throw ApiException.invalid("수강신청에는 Idempotency-Key가 필요합니다.");
		}
		this.rateLimiter.check("enrollment-apply", actor.userId(), 30, Duration.ofMinutes(10));
		Receipt receipt = retrying(() -> this.idempotency.execute(actor.userId(), "enrollment.apply", idempotencyKey,
				List.of(courseId), Receipt.class, () -> this.service.apply(actor, courseId)));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(receipt);
	}

	/** 정규 과목 청강 신청. 201. Idempotency-Key를 보내면 같은 key의 재전송은 처음 결과를 돌려받는다. */
	@PostMapping("/api/catalog/courses/{courseId}/audit-requests")
	ResponseEntity<Receipt> requestAudit(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody AuditRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		if (this.queries.regularCourse(courseId).isEmpty()) {
			throw ApiException.notFound();
		}
		String reason = reason(body.reason(), MAX_REASON);
		if (reason == null) {
			throw ApiException.invalid("청강 사유를 입력해 주세요.");
		}
		this.rateLimiter.check("audit-request", actor.userId(), 20, Duration.ofMinutes(10));
		Receipt receipt = retrying(() -> this.idempotency.execute(actor.userId(), "audit_request.create",
				idempotencyKey, List.of(courseId, reason), Receipt.class,
				() -> this.service.requestAudit(actor, courseId, reason)));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(receipt);
	}

	/** 청강 사유 수정. 본문의 version이 다르면 409 version_conflict. */
	@PutMapping("/api/enrollments/{enrollmentId}/reason")
	ResponseEntity<Receipt> updateReason(@AuthenticationPrincipal Actor actor, @PathVariable long enrollmentId,
			@RequestBody ReasonUpdate body) {
		String reason = reason(body.reason(), MAX_REASON);
		if (reason == null || body.version() == null || body.version() < 1) {
			throw ApiException.invalid("청강 사유와 version이 필요합니다.");
		}
		return ok(retrying(() -> this.service.updateReason(actor, enrollmentId, body.version(), reason)));
	}

	/** 본인 신청 취소(비정규, 청강). 이미 취소한 신청이면 같은 결과를 돌려준다. */
	@PostMapping("/api/enrollments/{enrollmentId}/cancel")
	ResponseEntity<Receipt> cancel(@AuthenticationPrincipal Actor actor, @PathVariable long enrollmentId) {
		this.rateLimiter.check("enrollment-cancel", actor.userId(), 30, Duration.ofMinutes(10));
		return ok(retrying(() -> this.service.cancel(actor, enrollmentId)));
	}

	/** 처리를 기다리는 신청. 그 과목의 교수와 비정규 담당자만 본다. */
	@GetMapping("/api/courses/{courseId}/enrollment-requests")
	ResponseEntity<RequestPage> requests(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
		if (this.records.deciderRole(actor.userId(), courseId).isEmpty()) {
			throw ApiException.notFound();
		}
		if (page < 1 || page > 10_000 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		List<Request> rows = this.records.pending(courseId, (page - 1) * size, size);
		Map<Long, UserCard> cards = this.users.find(rows.stream().map(Request::userId).toList());
		List<RequestItem> items = rows.stream().map((r) -> {
			UserCard card = cards.get(r.userId());
			return new RequestItem(r.enrollmentId(), r.kind(), r.status(), r.version(), r.appliedAt(), r.userId(),
					card == null ? null : card.name(), card == null ? null : card.externalRef(), r.reason());
		}).toList();
		return ok(new RequestPage(items, this.records.pendingCount(courseId), page, size));
	}

	/**
	 * 승인(APPROVE) 또는 반려(REJECT). 읽은 version이 다르면 409 version_conflict, 이미 처리한 신청이면 409 not_pending.
	 * Idempotency-Key를 보내면 같은 key의 재전송은 처음 결과를 돌려받는다.
	 */
	@PostMapping("/api/courses/{courseId}/enrollments/{enrollmentId}/decision")
	ResponseEntity<Receipt> decide(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long enrollmentId, @RequestBody DecisionRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		if (this.records.deciderRole(actor.userId(), courseId).isEmpty()) {
			throw ApiException.notFound();
		}
		if (body.decision() == null || !(body.decision().equals("APPROVE") || body.decision().equals("REJECT"))
				|| body.version() == null || body.version() < 1) {
			throw ApiException.invalid("decision은 APPROVE 또는 REJECT이고 version이 필요합니다.");
		}
		String reason = reason(body.reason(), MAX_DECISION_REASON);
		boolean approve = body.decision().equals("APPROVE");
		Receipt receipt = retrying(() -> this.idempotency.execute(actor.userId(), "enrollment.decide", idempotencyKey,
				new DecisionFingerprint(courseId, enrollmentId, body.decision(), body.version(), reason), Receipt.class,
				() -> this.service.decide(actor, courseId, enrollmentId, approve, body.version(), reason)));
		return ok(receipt);
	}

	/** 여러 줄 일반 텍스트. 비어 있으면 null이다. 줄바꿈과 tab 말고는 제어 문자를 받지 않는다. */
	private static String reason(String value, int max) {
		if (value == null || value.isBlank()) {
			return null;
		}
		String text = value.strip();
		if (text.length() > max || text.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')) {
			throw ApiException.invalid("사유는 " + max + "자 이하의 일반 텍스트여야 합니다.");
		}
		return text;
	}

	private static <T> T retrying(Supplier<T> command) {
		for (int attempt = 1;; attempt++) {
			try {
				return command.get();
			}
			catch (TransientDataAccessException ex) {
				if (attempt == ATTEMPTS) {
					throw ex;
				}
			}
		}
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/** 청강 신청 본문. 신청자와 과목은 session과 경로에서 정한다. */
	record AuditRequest(String reason) {
	}

	record ReasonUpdate(String reason, Integer version) {
	}

	/**
	 * @param decision APPROVE|REJECT
	 * @param version 담당자가 읽은 신청의 version
	 * @param reason 처리 사유(일반 텍스트 500자). 없어도 된다
	 */
	record DecisionRequest(String decision, Integer version, String reason) {
	}

	record DecisionFingerprint(long courseId, long enrollmentId, String decision, int version,
			String reason) {
	}

	/**
	 * @param reason 청강 사유. 비정규 신청이면 null
	 */
	record RequestItem(long enrollmentId, String kind, String status, int version, LocalDateTime appliedAt,
			long studentId, String studentName, String studentNumber, String reason) {
	}

	record RequestPage(List<RequestItem> items, long total, int page, int size) {
	}

}
