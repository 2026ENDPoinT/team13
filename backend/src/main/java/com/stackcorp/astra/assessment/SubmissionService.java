package com.stackcorp.astra.assessment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

import com.stackcorp.astra.assessment.Assignment.Phase;
import com.stackcorp.astra.assessment.SubmissionRecords.Submission;
import com.stackcorp.astra.assessment.SubmissionRecords.Version;
import com.stackcorp.astra.files.api.AttachmentException;
import com.stackcorp.astra.files.api.Attachments;
import com.stackcorp.astra.files.api.Attachments.AttachRequest;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 과제 임시저장, 최종 제출, 채점(SC-05, SC-05-B, SC-06, SEC-19, ADR-0015).
 * <p>
 * 최종 제출 순서: 권한 → 입력 → 제출물 잠금 → 같은 Idempotency-Key의 receipt 재응답 → 버전 확인 → DB 시계로 기간 판정 →
 * 첨부 확정 → 버전과 receipt 기록 → commit. 같은 key의 같은 요청은 마감이나 버전을 다시 따지지 않고 처음 receipt를 돌려준다.
 * 같은 key에 다른 요청이면 409다. 공용 멱등 원장이 생기기 전까지 receipt는 제출 버전 행이고, 유일 제약
 * (submitted_by_user_id, idempotency_key)가 중복을 막는다.
 */
@Service
class SubmissionService {

	static final String PURPOSE = "assignment_submission";

	private final AssignmentAccess access;

	private final SubmissionRecords records;

	private final Attachments attachments;

	private final RateLimiter rateLimiter;

	private final TransactionTemplate transaction;

	private final GradebookRecords gradebook;

	private final GradeWrites writes;

	private final AuditLog audit;

	SubmissionService(AssignmentAccess access, SubmissionRecords records, Attachments attachments,
			RateLimiter rateLimiter, TransactionTemplate transaction, GradebookRecords gradebook, GradeWrites writes,
			AuditLog audit) {
		this.access = access;
		this.records = records;
		this.attachments = attachments;
		this.rateLimiter = rateLimiter;
		this.transaction = transaction;
		this.gradebook = gradebook;
		this.writes = writes;
		this.audit = audit;
	}

	/** 임시저장. 제출 기간 안에서만 받고, 제출로 치지 않는다. */
	Draft saveDraft(long userId, long courseId, long assignmentId, String body) {
		LocalDateTime now = this.access.now();
		Assignment assignment = this.access.submittable(userId, courseId, assignmentId, now);
		if (!assignment.settings().allowText() && body != null) {
			throw ApiException.invalid("이 과제는 글 입력 제출을 받지 않습니다.");
		}
		this.rateLimiter.check("assignment-draft", userId, 120, Duration.ofMinutes(10));
		if (!assignment.phase(now).acceptsSubmissions()) {
			throw deadlinePassed();
		}
		this.records.ensure(assignmentId, userId);
		this.records.saveDraft(assignmentId, userId, body);
		Submission submission = this.records.find(assignmentId, userId).orElseThrow();
		return new Draft(submission.draftBody(), submission.draftSavedAt());
	}

	/** 최종 제출. 응답은 서버 receipt다. 새 버전을 만든 제출만 감사 기록을 남긴다. 같은 key의 재응답은 남기지 않는다. */
	Receipt submit(Actor actor, long courseId, long assignmentId, String key, NewSubmission submission) {
		long userId = actor.userId();
		Assignment assignment = this.access.submittable(userId, courseId, assignmentId, this.access.now());
		if (!assignment.settings().allowText() && submission.body() != null) {
			throw ApiException.invalid("이 과제는 글 입력 제출을 받지 않습니다.");
		}
		if (!assignment.settings().allowFile() && !submission.uploadIds().isEmpty()) {
			throw ApiException.invalid("이 과제는 파일 첨부 제출을 받지 않습니다.");
		}
		this.rateLimiter.check("assignment-submit", userId, 30, Duration.ofMinutes(10));
		String fingerprint = fingerprint(assignmentId, submission);
		this.records.ensure(assignmentId, userId);
		try {
			return this.transaction
				.execute((tx) -> submitLocked(actor, courseId, assignment, key, fingerprint, submission));
		}
		catch (DuplicateKeyException ex) {
			// 같은 key가 다른 과제의 제출에서 동시에 쓰였다. 먼저 commit한 쪽을 기준으로 답한다.
			return replay(userId, key, fingerprint).orElseThrow(() -> ex);
		}
	}

	private Receipt submitLocked(Actor actor, long courseId, Assignment assignment, String key, String fingerprint,
			NewSubmission submission) {
		long userId = actor.userId();
		Submission locked = this.records.lock(assignment.assignmentId(), userId);
		// 잠금을 얻은 뒤의 첫 일반 읽기라 먼저 끝난 같은 key 요청의 버전이 보인다.
		Optional<Receipt> replay = replay(userId, key, fingerprint);
		if (replay.isPresent()) {
			return replay.get();
		}
		if (locked.currentVersion() != submission.expectedVersion()) {
			throw new ApiException(HttpStatus.CONFLICT, "version_conflict", "다른 제출이 먼저 저장되었습니다. 다시 불러온 뒤 제출해 주세요.");
		}
		LocalDateTime now = this.access.now();
		Phase phase = assignment.phase(now);
		if (phase == Phase.NOT_OPEN) {
			throw ApiException.notFound();
		}
		if (!phase.acceptsSubmissions()) {
			throw deadlinePassed();
		}
		Long group = null;
		if (!submission.uploadIds().isEmpty()) {
			try {
				group = this.attachments.attach(
						new AttachRequest(userId, courseId, PURPOSE, submission.uploadIds(), "과제 제출"));
			}
			catch (AttachmentException ex) {
				throw new ApiException(HttpStatus.CONFLICT, "attachment_not_ready", "제출할 수 없는 첨부가 있습니다.");
			}
		}
		int versionNo = locked.currentVersion() + 1;
		boolean late = phase == Phase.LATE;
		this.records.insertVersion(locked.submissionId(), versionNo, submission.body(), group, now, userId, late, key,
				fingerprint);
		this.records.advance(locked.submissionId(), versionNo, now, late);
		this.audit.success(userId, actor.sessionId(), "assignment.submit", "assignment_submission",
				locked.submissionId(), courseId);
		return receipt(this.records.version(locked.submissionId(), versionNo).orElseThrow());
	}

	private Optional<Receipt> replay(long userId, String key, String fingerprint) {
		return this.records.byKey(userId, key).map((version) -> {
			if (!fingerprint.equals(version.fingerprint())) {
				throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "같은 Idempotency-Key를 다른 요청에 썼습니다.");
			}
			return receipt(version);
		});
	}

	/**
	 * 담당자가 최종 제출물에 점수와 의견을 준다. 학생에게는 점수를 공개한 뒤에만 보인다(SEC-14). 성적 항목 행을 잠근 뒤 그때의 배점으로
	 * 검사하므로 배점을 낮추는 수정과 겹쳐도 점수가 배점을 넘지 않는다. 점수 쓰기는 감사 기록을 남긴다.
	 */
	void grade(Actor grader, long courseId, Assignment assignment, long submissionId, BigDecimal score,
			String feedback) {
		Submission submission = this.records.find(submissionId)
			.filter((s) -> s.assignmentId() == assignment.assignmentId() && s.submitted())
			.orElseThrow(ApiException::notFound);
		long itemId = assignment.settings().gradeItemId();
		this.writes.run(() -> {
			GradeItem item = this.gradebook.lock(itemId);
			this.gradebook.putScore(itemId, submission.userId(), PlainText.points(score, item.maxScore(), "score"),
					feedback, grader.userId());
			long scoreId = this.gradebook.scoresOf(itemId, List.of(submission.userId())).get(submission.userId()).scoreId();
			this.audit.success(grader.userId(), grader.sessionId(), "grade_score.write", "grade_score", scoreId, courseId);
			return scoreId;
		});
	}

	Receipt receipt(Version version) {
		return new Receipt(version.submissionId(), version.versionNo(), version.submittedAt(), version.late(),
				version.body(), files(version.attachmentGroupId()));
	}

	List<AttachedFile> files(Long attachmentGroupId) {
		return attachmentGroupId == null ? List.of()
				: this.attachments.list(List.of(attachmentGroupId)).getOrDefault(attachmentGroupId, List.of());
	}

	private static ApiException deadlinePassed() {
		return new ApiException(HttpStatus.CONFLICT, "deadline_passed", "제출 기간이 아닙니다.");
	}

	/** 요청의 정규형. 과제, 기대 버전, 첨부 순서, 본문이 모두 같아야 같은 요청이다. */
	private static String fingerprint(long assignmentId, NewSubmission submission) {
		String canonical = "assignment:" + assignmentId + "\nexpected:" + submission.expectedVersion() + "\nuploads:"
				+ submission.uploadIds().stream().map(String::valueOf).collect(Collectors.joining(",")) + "\nbody:"
				+ (submission.body() == null ? "" : submission.body());
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/**
	 * @param expectedVersion 학생이 본 현재 버전 번호. 처음 제출이면 0
	 * @param body 일반 텍스트 본문. 없으면 null
	 */
	record NewSubmission(int expectedVersion, String body, List<Long> uploadIds) {
	}

	/**
	 * 서버 receipt. 같은 Idempotency-Key로 다시 보내면 같은 값을 돌려준다.
	 *
	 * @param submittedAt 서버가 접수한 시각(DB 시계)
	 * @param late 지각 구간에 접수했는지
	 */
	record Receipt(long submissionId, int version, LocalDateTime submittedAt, boolean late, String body,
			List<AttachedFile> files) {
	}

	record Draft(String body, LocalDateTime savedAt) {
	}

}
