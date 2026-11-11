package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.stackcorp.astra.assessment.SubmissionRecords.Counts;
import com.stackcorp.astra.assessment.SubmissionRecords.Grade;
import com.stackcorp.astra.assessment.SubmissionRecords.Submission;
import com.stackcorp.astra.assessment.SubmissionService.Draft;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.learning.api.ReportActivities.ReportActivity;
import com.stackcorp.astra.learning.api.ReportActivities.ReportActivityInput;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * 과제 등록·수정·조회(EP-0088의 대체 계약). 과목은 경로로 명시한다(SC-03). 담당자는 그 과목의 과제를 등록하고 고친다.
 * 학생에게는 공개됐고 시작한 과제만 보이고, 응답에는 본인 제출 상태만 담는다. 제출 상태와 기간 구간은 서버 시각으로 정한다(SC-06).
 * 점수와 의견은 점수를 공개한 뒤에만 학생 응답에 들어간다. 공개 전에는 값이 응답에 아예 없다(SEC-14).
 * 설명은 일반 텍스트로만 받고 내보낸다.
 */
@RestController
class AssignmentController {

	private static final int MAX_TITLE = 200;

	private static final int MAX_DESCRIPTION = 10_000;

	private static final int MAX_WEEK = 60;

	private static final BigDecimal MAX_SCORE = new BigDecimal("1000");

	private final AssignmentAccess access;

	private final AssignmentService assignments;

	private final SubmissionRecords submissions;

	private final SubmissionService submissionService;

	private final RateLimiter rateLimiter;

	AssignmentController(AssignmentAccess access, AssignmentService assignments, SubmissionRecords submissions,
			SubmissionService submissionService, RateLimiter rateLimiter) {
		this.access = access;
		this.assignments = assignments;
		this.submissions = submissions;
		this.submissionService = submissionService;
		this.rateLimiter = rateLimiter;
	}

	@PostMapping("/api/courses/{courseId}/assignments")
	ResponseEntity<InstructorAssignment> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody AssignmentRequest body) {
		if (this.access.role(actor.userId(), courseId) != CourseRole.INSTRUCTOR) {
			throw ApiException.notFound();
		}
		if (body.revision() != null) {
			throw ApiException.invalid("revision은 수정할 때만 보냅니다.");
		}
		AssignmentInput input = validated(body);
		this.rateLimiter.check("assignment-create", actor.userId(), 60, Duration.ofMinutes(10));
		long assignmentId = this.assignments.create(actor, courseId, input);
		Assignment created = this.access.find(courseId, assignmentId).orElseThrow();
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(instructorView(created, this.access.now()));
	}

	@PutMapping("/api/courses/{courseId}/assignments/{assignmentId}")
	ResponseEntity<InstructorAssignment> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long assignmentId, @RequestBody AssignmentRequest body) {
		Assignment current = this.access.managed(actor.userId(), courseId, assignmentId);
		if (body.revision() == null) {
			throw ApiException.invalid("revision은 반드시 보내야 합니다.");
		}
		AssignmentInput input = validated(body);
		this.rateLimiter.check("assignment-update", actor.userId(), 120, Duration.ofMinutes(10));
		this.assignments.update(actor, courseId, current, body.revision(), input);
		Assignment updated = this.access.find(courseId, assignmentId).orElseThrow();
		return ok(instructorView(updated, this.access.now()));
	}

	/**
	 * 점수 공개(released=true)와 거두기(false). 과제 전체를 다시 보내지 않는다. 읽은 {@code revision}이 다르면 409 {@code edit_conflict}다.
	 * 성적부의 공개 명령(PUT /api/courses/{courseId}/grade-items/{itemId}/release)과 같은 본문, 같은 감사 기록이다.
	 */
	@PutMapping("/api/courses/{courseId}/assignments/{assignmentId}/release")
	ResponseEntity<InstructorAssignment> release(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long assignmentId, @RequestBody ReleaseRequest body) {
		Assignment current = this.access.managed(actor.userId(), courseId, assignmentId);
		if (body.released() == null || body.revision() == null || body.revision() < 1) {
			throw ApiException.invalid("released와 revision을 보내야 합니다.");
		}
		this.rateLimiter.check("assignment-update", actor.userId(), 120, Duration.ofMinutes(10));
		this.assignments.release(actor, courseId, current, body.revision(), body.released());
		Assignment updated = this.access.find(courseId, assignmentId).orElseThrow();
		return ok(instructorView(updated, this.access.now()));
	}

	/**
	 * 과제 삭제(ADR-0011, ADR-0037). {@code If-Match}에 읽은 {@code revision}을 보낸다. 없으면 428, 다르면 409 {@code edit_conflict}다.
	 * 제출물과 그 파일, 점수는 보존하고, 그 commit부터 과제, 제출, 제출 파일, 성적 항목은 누구에게도 없다. 보낸 알림은 거둔다. 204.
	 */
	@DeleteMapping("/api/courses/{courseId}/assignments/{assignmentId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long assignmentId,
			@RequestHeader(name = HttpHeaders.IF_MATCH, required = false) String ifMatch) {
		Assignment current = this.access.managed(actor.userId(), courseId, assignmentId);
		long revision = IfMatch.revision(ifMatch);
		this.rateLimiter.check("assignment-update", actor.userId(), 120, Duration.ofMinutes(10));
		this.assignments.delete(actor, courseId, current, revision);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	@GetMapping("/api/courses/{courseId}/assignments")
	ResponseEntity<List<AssignmentItem>> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		CourseRole role = this.access.role(actor.userId(), courseId);
		LocalDateTime now = this.access.now();
		List<Assignment> all = this.access.all(courseId);
		if (role == CourseRole.INSTRUCTOR) {
			Map<Long, Counts> counts = this.submissions.counts(all.stream().map(Assignment::assignmentId).toList());
			return ok(all.stream()
				.map((a) -> AssignmentItem.of(a, now, null,
						counts.getOrDefault(a.assignmentId(), new Counts(0, 0))))
				.toList());
		}
		List<Assignment> visible = all.stream().filter((a) -> a.activity().openToStudents(now)).toList();
		Map<Long, Submission> mine = this.submissions.ofUser(actor.userId(),
				visible.stream().map(Assignment::assignmentId).toList());
		return ok(visible.stream().map((a) -> AssignmentItem.of(a, now, status(mine.get(a.assignmentId())), null)).toList());
	}

	@GetMapping("/api/courses/{courseId}/assignments/{assignmentId}")
	ResponseEntity<AssignmentDetail> get(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long assignmentId) {
		CourseRole role = this.access.role(actor.userId(), courseId);
		LocalDateTime now = this.access.now();
		Assignment assignment = this.access.viewable(role, courseId, assignmentId, now);
		if (role == CourseRole.INSTRUCTOR) {
			return ok(instructorView(assignment, now));
		}
		Submission submission = this.submissions.find(assignmentId, actor.userId()).orElse(null);
		return ok(new StudentAssignment(Info.of(assignment, now), assignment.phase(now).code(), now,
				mySubmission(assignment, submission, actor.userId(), now)));
	}

	private InstructorAssignment instructorView(Assignment assignment, LocalDateTime now) {
		Counts counts = this.submissions.counts(List.of(assignment.assignmentId()))
			.getOrDefault(assignment.assignmentId(), new Counts(0, 0));
		return new InstructorAssignment(Info.of(assignment, now), assignment.settings().revision(),
				assignment.phase(now).code(), now, counts.submitted(), counts.late());
	}

	private MySubmission mySubmission(Assignment assignment, Submission submission, long userId, LocalDateTime now) {
		Draft draft = submission == null || submission.draftSavedAt() == null ? null
				: new Draft(submission.draftBody(), submission.draftSavedAt());
		if (submission == null || !submission.submitted()) {
			return new MySubmission("not_submitted", 0, null, null, null, List.of(), draft, null);
		}
		SubmissionRecords.Version current = this.submissions.version(submission.submissionId(), submission.currentVersion())
			.orElseThrow();
		// 공개 전에는 점수를 읽지도 않는다. 응답에 들어갈 길을 만들지 않는다(SEC-14).
		GradeView grade = assignment.scoresReleased(now)
				? this.submissions.grade(assignment.settings().gradeItemId(), userId).map(GradeView::of).orElse(null)
				: null;
		return new MySubmission(submission.status(), submission.currentVersion(), submission.firstSubmittedAt(),
				submission.submittedAt(), current.body(), this.submissionService.files(current.attachmentGroupId()), draft,
				grade);
	}

	private static String status(Submission submission) {
		return submission == null ? "not_submitted" : submission.status();
	}

	private static AssignmentInput validated(AssignmentRequest body) {
		if (body.weekNo() == null || body.weekNo() < 1 || body.weekNo() > MAX_WEEK) {
			throw ApiException.invalid("weekNo는 1 이상 " + MAX_WEEK + " 이하여야 합니다.");
		}
		String title = PlainText.line(body.title(), MAX_TITLE, "title");
		String description = PlainText.block(body.description(), MAX_DESCRIPTION, "description");
		if (body.closeAt() == null) {
			throw ApiException.invalid("closeAt은 반드시 보내야 합니다.");
		}
		// 활동의 기간 컬럼은 초 단위다. 반올림되지 않도록 초 아래를 버린다.
		LocalDateTime closeAt = body.closeAt().truncatedTo(ChronoUnit.SECONDS);
		LocalDateTime openAt = body.openAt() == null ? null : body.openAt().truncatedTo(ChronoUnit.SECONDS);
		if (openAt != null && !openAt.isBefore(closeAt)) {
			throw ApiException.invalid("openAt은 closeAt보다 앞이어야 합니다.");
		}
		String latePolicy = body.latePolicy() == null ? Assignment.LATE_NONE : body.latePolicy();
		LocalDateTime lateUntil = body.lateUntil() == null ? null : body.lateUntil().truncatedTo(ChronoUnit.SECONDS);
		if (Assignment.LATE_NONE.equals(latePolicy)) {
			if (lateUntil != null) {
				throw ApiException.invalid("latePolicy가 none이면 lateUntil을 보내지 않습니다.");
			}
		}
		else if (Assignment.LATE_ALLOW_UNTIL.equals(latePolicy)) {
			if (lateUntil == null || !lateUntil.isAfter(closeAt)) {
				throw ApiException.invalid("latePolicy가 allow_until이면 lateUntil은 closeAt보다 뒤여야 합니다.");
			}
		}
		else {
			throw ApiException.invalid("latePolicy는 none 또는 allow_until이어야 합니다.");
		}
		boolean allowText = body.allowText() == null || body.allowText();
		boolean allowFile = body.allowFile() == null || body.allowFile();
		if (!allowText && !allowFile) {
			throw ApiException.invalid("allowText와 allowFile 중 하나는 허용해야 합니다.");
		}
		BigDecimal maxScore = PlainText.points(body.maxScore(), MAX_SCORE, "maxScore");
		if (maxScore.signum() == 0) {
			throw ApiException.invalid("maxScore는 0보다 커야 합니다.");
		}
		return new AssignmentInput(
				new ReportActivityInput(body.weekNo(), title, description, openAt, closeAt,
						body.published() == null || body.published()),
				allowText, allowFile, latePolicy, lateUntil, maxScore,
				body.scoresReleased() != null && body.scoresReleased());
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/**
	 * 요청 본문. 작성자와 과목은 받지 않는다. session과 경로에서 정한다(SC-02).
	 *
	 * @param revision 수정할 때 읽은 과제 설정 수정 번호. 등록할 때는 보내지 않는다
	 */
	record AssignmentRequest(Integer weekNo, String title, String description, LocalDateTime openAt,
			LocalDateTime closeAt, Boolean published, String latePolicy, LocalDateTime lateUntil, Boolean allowText,
			Boolean allowFile, BigDecimal maxScore, Boolean scoresReleased, Long revision) {
	}

	/**
	 * @param released 학생에게 점수와 의견을 공개하는지
	 * @param revision 읽은 과제 설정 수정 번호. 성공하면 1 오른다
	 */
	record ReleaseRequest(Boolean released, Long revision) {
	}

	/** 담당자와 학생에게 같은 과제 정보. */
	record Info(long assignmentId, long activityId, int weekNo, String title, String description, LocalDateTime openAt,
			LocalDateTime closeAt, boolean published, String latePolicy, LocalDateTime lateUntil, boolean allowText,
			boolean allowFile, BigDecimal maxScore, boolean scoresReleased) {

		static Info of(Assignment a, LocalDateTime now) {
			ReportActivity activity = a.activity();
			return new Info(a.assignmentId(), activity.activityId(), activity.weekNo(), activity.title(),
					activity.description(), activity.openAt(), activity.closeAt(), activity.published(),
					a.settings().latePolicy(), a.settings().lateUntil(), a.settings().allowText(),
					a.settings().allowFile(), a.settings().maxScore(), a.scoresReleased(now));
		}

	}

	sealed interface AssignmentDetail permits InstructorAssignment, StudentAssignment {
	}

	/**
	 * @param revision 수정 요청에 그대로 보낼 수정 번호
	 * @param phase 지금 제출 구간: not_open, open, late, closed
	 */
	record InstructorAssignment(Info assignment, long revision, String phase, LocalDateTime serverTime,
			int submittedCount, int lateCount) implements AssignmentDetail {
	}

	record StudentAssignment(Info assignment, String phase, LocalDateTime serverTime, MySubmission submission)
			implements AssignmentDetail {
	}

	/**
	 * 학생 본인의 제출 상태. 본문과 첨부는 현재 버전이다.
	 *
	 * @param status not_submitted, submitted, late. 임시저장은 제출로 치지 않는다
	 * @param version 현재 버전 번호. 다음 제출의 expectedVersion으로 보낸다
	 * @param grade 점수를 공개한 뒤에만 있다. 공개 전에는 응답에 이 속성이 없다
	 */
	record MySubmission(String status, int version, LocalDateTime firstSubmittedAt, LocalDateTime submittedAt,
			String body, List<AttachedFile> files, Draft draft,
			@JsonInclude(JsonInclude.Include.NON_NULL) GradeView grade) {
	}

	record GradeView(BigDecimal score, String feedback, LocalDateTime gradedAt) {

		static GradeView of(Grade grade) {
			return new GradeView(grade.score(), grade.feedback(), grade.gradedAt());
		}

	}

	/**
	 * 목록 항목. 담당자에게는 제출 수를, 학생에게는 본인 상태를 담는다.
	 *
	 * @param status 학생 본인의 제출 상태. 담당자 응답에는 없다
	 * @param submittedCount 최종 제출한 학생 수. 학생 응답에는 없다
	 */
	record AssignmentItem(long assignmentId, int weekNo, String title, LocalDateTime openAt, LocalDateTime closeAt,
			LocalDateTime lateUntil, boolean published, String phase,
			@JsonInclude(JsonInclude.Include.NON_NULL) String status,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer submittedCount,
			@JsonInclude(JsonInclude.Include.NON_NULL) Integer lateCount) {

		static AssignmentItem of(Assignment a, LocalDateTime now, String status, Counts counts) {
			ReportActivity activity = a.activity();
			return new AssignmentItem(a.assignmentId(), activity.weekNo(), activity.title(), activity.openAt(),
					activity.closeAt(), a.settings().lateUntil(), activity.published(), a.phase(now).code(), status,
					counts == null ? null : counts.submitted(), counts == null ? null : counts.late());
		}

	}

}
