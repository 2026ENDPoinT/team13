package com.stackcorp.astra.catalog;

import java.time.LocalDateTime;
import java.util.List;

import com.stackcorp.astra.catalog.CatalogQueries.CourseRow;
import com.stackcorp.astra.catalog.CatalogQueries.OfferingRow;
import com.stackcorp.astra.catalog.EnrollmentRecords.Enrollment;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비정규 수강신청, 청강 신청, 취소, 승인·반려(CHG-03, ADR-0026). 명령마다 한 transaction에서 상태 전이, 좌석 카운터, 상태 이벤트,
 * 감사 기록을 함께 commit한다. 판정 시각은 그 transaction 안에서 읽은 DB 시각이다. 중복 신청은 활성 행 unique 제약이 막고,
 * 좌석은 과정 행을 잠근 뒤 조건부 갱신으로 잡는다(SC-05).
 */
@Service
class EnrollmentService {

	static final String NONREGULAR = "NONREGULAR";

	static final String AUDIT = "AUDIT";

	private final EnrollmentRecords records;

	private final CatalogQueries queries;

	private final CourseMemberships memberships;

	private final AuditLog audit;

	EnrollmentService(EnrollmentRecords records, CatalogQueries queries, CourseMemberships memberships,
			AuditLog audit) {
		this.records = records;
		this.queries = queries;
		this.memberships = memberships;
		this.audit = audit;
	}

	/**
	 * 비정규 과정 신청(EP-0197). 자동 승인 과정은 좌석을 잡고 바로 ENROLLED, 승인 과정은 좌석 없이 PENDING이다. 대기자 명단은 두지
	 * 않는다. 정원이 차면 409 seat_full이고 아무것도 남지 않는다.
	 */
	@Transactional
	Receipt apply(Actor actor, long courseId) {
		OfferingRow offering = this.records.lockOffering(courseId).orElseThrow(ApiException::notFound);
		LocalDateTime now = this.queries.now();
		List<String> audiences = this.queries.audiences(List.of(courseId)).getOrDefault(courseId, List.of());
		boolean instructor = this.memberships.roleOf(actor.userId(), courseId)
			.filter((role) -> role == CourseRole.INSTRUCTOR)
			.isPresent();
		// 중복과 정원은 아래의 unique 제약과 조건부 갱신이 판정한다. 미리 읽은 값으로 정하지 않는다.
		OfferingRules.refusals(offering, now, actor.role(), audiences, instructor, false)
			.stream()
			.filter((reason) -> !OfferingRules.SEAT_FULL.equals(reason))
			.findFirst()
			.ifPresent((reason) -> {
				throw refused(reason);
			});
		boolean auto = OfferingRules.autoApproval(offering);
		String status = auto ? "ENROLLED" : "PENDING";
		long id = insert(courseId, actor.userId(), NONREGULAR, "STUDENT", status, auto, now);
		if (auto && !this.records.takeSeat(courseId)) {
			throw refused(OfferingRules.SEAT_FULL);
		}
		this.records.event(id, 1, "APPLY", null, status, actor.userId(), "USER", null, now);
		this.audit.success(actor.userId(), actor.sessionId(), "enrollment.apply", "course_enrollment", id, courseId);
		return new Receipt(id, courseId, NONREGULAR, status, 1);
	}

	/**
	 * 정규 과목 청강 신청(EP-0209). 청강을 허용한 개설 과목이고 신청자가 그 과목의 구성원이 아니어야 한다. 청강은 좌석을 쓰지 않는다.
	 */
	@Transactional
	Receipt requestAudit(Actor actor, long courseId, String reason) {
		CourseRow course = this.queries.regularCourse(courseId).orElseThrow(ApiException::notFound);
		if (!course.auditAllowed() || !OfferingRules.OPEN.equals(course.status())) {
			throw conflict("audit_not_allowed", "청강을 신청할 수 없는 과목입니다.");
		}
		if (this.memberships.roleOf(actor.userId(), courseId).isPresent()) {
			throw conflict("already_member", "이미 이 과목의 구성원입니다.");
		}
		LocalDateTime now = this.queries.now();
		long id = insert(courseId, actor.userId(), AUDIT, "AUDITOR", "REQUESTED", false, now);
		this.records.insertAuditReason(id, reason);
		this.records.event(id, 1, "APPLY", null, "REQUESTED", actor.userId(), "USER", null, now);
		this.audit.success(actor.userId(), actor.sessionId(), "audit_request.create", "course_enrollment", id,
				courseId);
		return new Receipt(id, courseId, AUDIT, "REQUESTED", 1);
	}

	/**
	 * 본인 신청 취소(EP-0199, EP-0208). 행을 지우지 않고 CANCELLED로 바꾼다. 좌석을 점유했으면 같은 transaction에서 한 번만
	 * 돌려준다. 이미 취소한 신청이면 아무것도 바꾸지 않고 같은 결과를 돌려준다. 학사 원장(REGISTRAR)은 바꾸지 않는다.
	 */
	@Transactional
	Receipt cancel(Actor actor, long enrollmentId) {
		Enrollment seen = this.records.find(enrollmentId)
			.filter((e) -> e.userId() == actor.userId())
			.orElseThrow(ApiException::notFound);
		if (!NONREGULAR.equals(seen.kind()) && !AUDIT.equals(seen.kind())) {
			throw notCancellable();
		}
		// 잠금 순서: 과정 행 → 수강 행 → 좌석 카운터.
		OfferingRow offering = NONREGULAR.equals(seen.kind())
				? this.records.lockOffering(seen.courseId()).orElseThrow(ApiException::notFound) : null;
		Enrollment row = this.records.lock(enrollmentId).orElseThrow(ApiException::notFound);
		if ("CANCELLED".equals(row.status())) {
			return Receipt.of(row);
		}
		LocalDateTime now = this.queries.now();
		if (!OfferingRules.cancellable(row.kind(), row.status(), offering == null ? null : offering.cancelAllowed(),
				offering == null ? null : offering.cancelCloseAt(), now)) {
			if ("ENROLLED".equals(row.status()) && offering != null && offering.cancelAllowed()) {
				throw conflict("cancel_closed", "수강취소 기간이 지났습니다.");
			}
			throw notCancellable();
		}
		int version = this.records.transition(row, "CANCELLED", false, now);
		if (row.holdsSeat()) {
			this.records.returnSeat(row.courseId());
		}
		this.records.event(row.enrollmentId(), version, "CANCEL", row.status(), "CANCELLED", actor.userId(), "USER",
				null, now);
		this.audit.success(actor.userId(), actor.sessionId(), "enrollment.cancel", "course_enrollment",
				row.enrollmentId(), row.courseId());
		return new Receipt(row.enrollmentId(), row.courseId(), row.kind(), "CANCELLED", version);
	}

	/** 청강 사유 수정(EP-0210). 처리 전(REQUESTED)이고 읽은 version이 같을 때만 바꾼다. */
	@Transactional
	Receipt updateReason(Actor actor, long enrollmentId, int version, String reason) {
		Enrollment row = this.records.lock(enrollmentId)
			.filter((e) -> e.userId() == actor.userId() && AUDIT.equals(e.kind()))
			.orElseThrow(ApiException::notFound);
		if (!"REQUESTED".equals(row.status())) {
			throw conflict("not_editable", "처리된 청강 신청은 고칠 수 없습니다.");
		}
		if (row.version() != version || !this.records.updateReason(enrollmentId, version, reason)) {
			throw versionConflict();
		}
		this.audit.success(actor.userId(), actor.sessionId(), "audit_request.update", "course_enrollment",
				enrollmentId, row.courseId());
		return new Receipt(enrollmentId, row.courseId(), AUDIT, row.status(), version + 1);
	}

	/**
	 * 담당자의 승인·반려. 비정규 PENDING과 청강 REQUESTED만 처리한다. 비정규 승인은 승인 시점에 좌석을 잡고, 정원이 찼으면 409
	 * seat_full로 PENDING에 남긴다. 반려는 좌석을 점유하지 않은 상태에서만 일어나므로 좌석 수가 바뀌지 않는다. 학생 취소와 겹치면
	 * 수강 행 잠금과 version 비교로 하나만 성공한다.
	 * @param reason 처리 사유. 없으면 null
	 */
	@Transactional
	Receipt decide(Actor actor, long courseId, long enrollmentId, boolean approve, int version, String reason) {
		String deciderRole = this.records.deciderRole(actor.userId(), courseId).orElseThrow(ApiException::notFound);
		Enrollment seen = this.records.find(enrollmentId)
			.filter((e) -> e.courseId() == courseId && (NONREGULAR.equals(e.kind()) || AUDIT.equals(e.kind())))
			.orElseThrow(ApiException::notFound);
		boolean nonRegular = NONREGULAR.equals(seen.kind());
		if (nonRegular) {
			this.records.lockOffering(courseId).orElseThrow(ApiException::notFound);
		}
		Enrollment row = this.records.lock(enrollmentId).orElseThrow(ApiException::notFound);
		if (!(nonRegular ? "PENDING" : "REQUESTED").equals(row.status())) {
			throw conflict("not_pending", "이미 처리된 신청입니다.");
		}
		if (row.version() != version) {
			throw versionConflict();
		}
		LocalDateTime now = this.queries.now();
		boolean seat = approve && nonRegular;
		if (seat && !this.records.takeSeat(courseId)) {
			throw refused(OfferingRules.SEAT_FULL);
		}
		String to = approve ? "ENROLLED" : "REJECTED";
		int next = this.records.transition(row, to, seat, now);
		this.records.event(enrollmentId, next, approve ? "APPROVE" : "REJECT", row.status(), to, actor.userId(),
				"MANAGER".equals(deciderRole) ? "STAFF" : "PROFESSOR", reason, now);
		this.audit.success(actor.userId(), actor.sessionId(), approve ? "enrollment.approve" : "enrollment.reject",
				"course_enrollment", enrollmentId, courseId);
		return new Receipt(enrollmentId, courseId, row.kind(), to, next);
	}

	private long insert(long courseId, long userId, String kind, String role, String status, boolean holdsSeat,
			LocalDateTime now) {
		try {
			return this.records.insert(courseId, userId, kind, role, status, holdsSeat, now);
		}
		catch (DuplicateKeyException ex) {
			throw refused(OfferingRules.ALREADY_APPLIED);
		}
	}

	private static ApiException refused(String reason) {
		return conflict(reason, switch (reason) {
			case OfferingRules.NOT_OPEN_YET -> "아직 신청 기간이 아닙니다.";
			case OfferingRules.APPLICATION_CLOSED -> "신청 기간이 아닙니다.";
			case OfferingRules.NOT_TARGET -> "신청대상이 아닙니다.";
			case OfferingRules.OWN_COURSE -> "본인 강의과목은 신청할 수 없습니다.";
			case OfferingRules.ALREADY_APPLIED -> "이미 신청했습니다.";
			case OfferingRules.SEAT_FULL -> "정원이 찼습니다.";
			default -> throw new IllegalArgumentException(reason);
		});
	}

	private static ApiException notCancellable() {
		return conflict("not_cancellable", "취소할 수 없는 신청입니다.");
	}

	private static ApiException versionConflict() {
		return conflict("version_conflict", "다른 곳에서 먼저 바뀌었습니다. 다시 불러온 뒤 시도해 주세요.");
	}

	private static ApiException conflict(String code, String detail) {
		return new ApiException(HttpStatus.CONFLICT, code, detail);
	}

	/**
	 * 명령의 결과. 멱등 receipt로도 저장한다.
	 * @param kind NONREGULAR|AUDIT
	 * @param status 명령 뒤의 상태: ENROLLED|PENDING|REQUESTED|REJECTED|CANCELLED
	 * @param version 명령 뒤의 version. 다음 수정·처리 요청에 보낸다
	 */
	record Receipt(long enrollmentId, long courseId, String kind, String status, int version) {

		static Receipt of(Enrollment row) {
			return new Receipt(row.enrollmentId(), row.courseId(), row.kind(), row.status(), row.version());
		}

	}

}
