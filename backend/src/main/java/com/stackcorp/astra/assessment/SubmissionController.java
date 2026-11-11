package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.stackcorp.astra.assessment.AssignmentController.GradeView;
import com.stackcorp.astra.assessment.SubmissionRecords.Grade;
import com.stackcorp.astra.assessment.SubmissionRecords.Submission;
import com.stackcorp.astra.assessment.SubmissionRecords.Version;
import com.stackcorp.astra.assessment.SubmissionService.Draft;
import com.stackcorp.astra.assessment.SubmissionService.NewSubmission;
import com.stackcorp.astra.assessment.SubmissionService.Receipt;
import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.idempotency.Idempotency;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * 과제 제출과 채점(EP-0077, EP-0078, EP-0082, EP-0083의 대체 계약).
 * 학생은 자기 제출물만 임시저장하고 최종 제출한다. 제출자는 session에서 정한다(SC-02). 최종 제출에는 Idempotency-Key가
 * 반드시 있어야 하고, 학생이 본 버전 번호(expectedVersion)를 함께 보내 다른 탭의 제출을 조용히 덮어쓰지 않는다(SEC-19).
 * 담당자는 제출 목록과 제출물을 보고 점수와 의견을 준다. 다른 학생의 제출물은 어떤 경로로도 보이지 않는다.
 */
@RestController
class SubmissionController {

	private static final int MAX_BODY = 50_000;

	private static final int MAX_FEEDBACK = 1_000;

	private static final int MAX_UPLOADS = 5;

	private final AssignmentAccess access;

	private final SubmissionService service;

	private final SubmissionRecords records;

	private final Attachments attachments;

	private final UserNames userNames;

	SubmissionController(AssignmentAccess access, SubmissionService service, SubmissionRecords records,
			Attachments attachments, UserNames userNames) {
		this.access = access;
		this.service = service;
		this.records = records;
		this.attachments = attachments;
		this.userNames = userNames;
	}

	@PutMapping("/api/courses/{courseId}/assignments/{assignmentId}/submission/draft")
	ResponseEntity<Draft> saveDraft(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long assignmentId, @RequestBody DraftRequest body) {
		String text = PlainText.block(body.body(), MAX_BODY, "body");
		return ok(this.service.saveDraft(actor.userId(), courseId, assignmentId, text));
	}

	@PostMapping("/api/courses/{courseId}/assignments/{assignmentId}/submission")
	ResponseEntity<Receipt> submit(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long assignmentId,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey,
			@RequestBody SubmitRequest body) {
		Idempotency.requireKey(idempotencyKey);
		Receipt receipt = this.service.submit(actor, courseId, assignmentId, idempotencyKey, validated(body));
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(receipt);
	}

	@GetMapping("/api/courses/{courseId}/assignments/{assignmentId}/submissions")
	ResponseEntity<List<SubmissionItem>> submissions(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long assignmentId) {
		Assignment assignment = this.access.managed(actor.userId(), courseId, assignmentId);
		List<Submission> submitted = this.records.submitted(assignmentId);
		Map<Long, String> names = this.userNames.namesOf(submitted.stream().map(Submission::userId).toList());
		Map<Long, Grade> grades = this.records.grades(assignment.settings().gradeItemId());
		return ok(submitted.stream()
			.map((s) -> new SubmissionItem(s.submissionId(), s.userId(), names.get(s.userId()), s.status(),
					s.currentVersion(), s.firstSubmittedAt(), s.submittedAt(), s.late(),
					grades.containsKey(s.userId()) ? grades.get(s.userId()).score() : null))
			.toList());
	}

	@GetMapping("/api/courses/{courseId}/assignments/{assignmentId}/submissions/{submissionId}")
	ResponseEntity<SubmissionDetail> submission(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long assignmentId, @PathVariable long submissionId) {
		Assignment assignment = this.access.managed(actor.userId(), courseId, assignmentId);
		Submission submission = this.records.find(submissionId)
			.filter((s) -> s.assignmentId() == assignmentId && s.submitted())
			.orElseThrow(ApiException::notFound);
		List<Version> versions = this.records.versions(submissionId);
		Map<Long, List<AttachedFile>> files = this.attachments
			.list(versions.stream().map(Version::attachmentGroupId).filter(Objects::nonNull).toList());
		List<Receipt> history = versions.stream()
			.map((v) -> new Receipt(v.submissionId(), v.versionNo(), v.submittedAt(), v.late(), v.body(),
					v.attachmentGroupId() == null ? List.of() : files.getOrDefault(v.attachmentGroupId(), List.of())))
			.toList();
		GradeView grade = this.records.grade(assignment.settings().gradeItemId(), submission.userId())
			.map(GradeView::of)
			.orElse(null);
		String name = this.userNames.namesOf(List.of(submission.userId())).get(submission.userId());
		return ok(new SubmissionDetail(submission.submissionId(), submission.userId(), name, submission.status(),
				history, grade));
	}

	@PutMapping("/api/courses/{courseId}/assignments/{assignmentId}/submissions/{submissionId}/grade")
	ResponseEntity<GradeView> grade(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long assignmentId, @PathVariable long submissionId, @RequestBody GradeRequest body) {
		Assignment assignment = this.access.managed(actor.userId(), courseId, assignmentId);
		String feedback = PlainText.block(body.feedback(), MAX_FEEDBACK, "feedback");
		this.service.grade(actor, courseId, assignment, submissionId, body.score(), feedback);
		Submission submission = this.records.find(submissionId).orElseThrow();
		return ok(this.records.grade(assignment.settings().gradeItemId(), submission.userId())
			.map(GradeView::of)
			.orElseThrow());
	}

	private static NewSubmission validated(SubmitRequest body) {
		if (body.expectedVersion() == null || body.expectedVersion() < 0) {
			throw ApiException.invalid("expectedVersion은 0 이상이어야 합니다.");
		}
		String text = PlainText.block(body.body(), MAX_BODY, "body");
		List<Long> uploadIds = ids(body.uploadIds(), "uploadIds");
		List<Long> keepFileIds = ids(body.keepFileIds(), "keepFileIds");
		if (uploadIds.size() + keepFileIds.size() > MAX_UPLOADS) {
			throw ApiException.invalid("첨부는 " + MAX_UPLOADS + "개까지입니다.");
		}
		if (text == null && uploadIds.isEmpty() && keepFileIds.isEmpty()) {
			throw ApiException.invalid("본문이나 첨부 중 하나는 있어야 합니다.");
		}
		return new NewSubmission(body.expectedVersion(), text, uploadIds, keepFileIds);
	}

	private static List<Long> ids(List<Long> values, String name) {
		List<Long> ids = values == null ? List.of() : values;
		if (ids.size() > MAX_UPLOADS || ids.stream().anyMatch((id) -> id == null || id < 1)
				|| new LinkedHashSet<>(ids).size() != ids.size()) {
			throw ApiException.invalid(name + "가 올바르지 않습니다.");
		}
		return ids;
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	/** 임시저장 본문. 비우려면 null을 보낸다. */
	record DraftRequest(String body) {
	}

	/**
	 * 최종 제출. 제출자와 과목은 받지 않는다(SC-02).
	 *
	 * @param expectedVersion 학생이 본 현재 버전 번호. 처음 제출이면 0
	 * @param uploadIds 이 버전에 새로 첨부할 본인 업로드(용도 assignment_submission)
	 * @param keepFileIds 현재 버전({@code expectedVersion})의 파일 가운데 이 버전에 이어받을 파일 ID. 이전 버전은 그 파일을 그대로 가진다
	 */
	record SubmitRequest(Integer expectedVersion, String body, List<Long> uploadIds, List<Long> keepFileIds) {
	}

	record GradeRequest(BigDecimal score, String feedback) {
	}

	/**
	 * @param versionCount 최종 제출 횟수
	 * @param score 준 점수. 채점하지 않았으면 null
	 */
	record SubmissionItem(long submissionId, long studentId, String studentName, String status, int versionCount,
			LocalDateTime firstSubmittedAt, LocalDateTime submittedAt, boolean late, BigDecimal score) {
	}

	/**
	 * @param versions 최종 제출 버전. 새 버전이 먼저 나온다
	 */
	record SubmissionDetail(long submissionId, long studentId, String studentName, String status,
			List<Receipt> versions, GradeView grade) {
	}

}
