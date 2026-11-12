package com.stackcorp.astra.summary.web;

import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.files.api.DeliveryCookies;
import com.stackcorp.astra.files.api.DeliveryCookies.DeliveryCookie;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;
import com.stackcorp.astra.summary.job.PublishedSummary;
import com.stackcorp.astra.summary.job.SummaryJob;
import com.stackcorp.astra.summary.job.SummaryJobException;
import com.stackcorp.astra.summary.job.SummaryJobService;
import com.stackcorp.astra.summary.job.SummaryResult;
import com.stackcorp.astra.summary.web.SummaryViews.CandidateView;
import com.stackcorp.astra.summary.web.SummaryViews.JobView;
import com.stackcorp.astra.summary.web.SummaryViews.OverviewView;
import com.stackcorp.astra.summary.web.SummaryViews.PublishedView;

/**
 * 강의 자료 쪽별 요약 API. 과목은 경로로 명시하고(SC-03), 권한이 없거나 다른 과목의 파일·작업이면 존재를 드러내지 않도록
 * 404로 응답한다. 요약 본문은 캐시하지 않는다(ADR-0007 7번). 생성과 재시도는 요청한 교수자의 쪽별 요약 동의가 있어야 하고,
 * 배포에서 외부 LLM을 끄면 503 {@code llm_disabled}다(ADR-0032).
 */
@RestController
class SummaryController {

	private final SummaryJobService jobs;

	private final SummaryPolicy policy;

	private final DocumentRenditions renditions;

	private final DeliveryCookies cookies;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	SummaryController(SummaryJobService jobs, SummaryPolicy policy, DocumentRenditions renditions,
			DeliveryCookies cookies, RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit) {
		this.rateLimiter = rateLimiter;
		this.jobs = jobs;
		this.policy = policy;
		this.renditions = renditions;
		this.cookies = cookies;
		this.idempotency = idempotency;
		this.audit = audit;
	}

	/** {@code Idempotency-Key}를 보내면 같은 key의 재전송은 처음 응답을 다시 돌려받고 작업을 더 만들지 않는다(SC-05). */
	@PostMapping("/api/courses/{courseId}/files/{fileId}/summary-jobs")
	ResponseEntity<JobView> request(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		requireManage(actor, courseId, fileId);
		limitGeneration(actor);
		JobView job = this.idempotency.execute(actor.userId(), "summary_job.request", idempotencyKey,
				List.of(courseId, fileId), JobView.class, () -> {
					JobView created = JobView.of(this.jobs.request(courseId, fileId, actor.userId()));
					this.audit.success(actor.userId(), actor.sessionId(), "summary_job.request", "summary_job",
							created.id(), courseId);
					return created;
				});
		return noStore(HttpStatus.ACCEPTED).body(job);
	}

	@GetMapping("/api/courses/{courseId}/files/{fileId}/summary-jobs")
	ResponseEntity<List<JobView>> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId) {
		requireManage(actor, courseId, fileId);
		return noStore(HttpStatus.OK).body(this.jobs.list(fileId).stream().map(JobView::of).toList());
	}

	@GetMapping("/api/courses/{courseId}/summary-jobs/{jobId}")
	ResponseEntity<JobView> get(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long jobId) {
		return noStore(HttpStatus.OK).body(JobView.of(managedJob(actor, courseId, jobId)));
	}

	@PostMapping("/api/courses/{courseId}/summary-jobs/{jobId}/cancel")
	ResponseEntity<JobView> cancel(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long jobId) {
		managedJob(actor, courseId, jobId);
		JobView job = JobView.of(this.jobs.cancel(jobId));
		this.audit.success(actor.userId(), actor.sessionId(), "summary_job.cancel", "summary_job", jobId, courseId);
		return noStore(HttpStatus.OK).body(job);
	}

	@PostMapping("/api/courses/{courseId}/summary-jobs/{jobId}/retry")
	ResponseEntity<JobView> retry(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long jobId) {
		managedJob(actor, courseId, jobId);
		limitGeneration(actor);
		JobView job = JobView.of(this.jobs.retry(courseId, jobId, actor.userId()));
		this.audit.success(actor.userId(), actor.sessionId(), "summary_job.retry", "summary_job", job.id(), courseId);
		return noStore(HttpStatus.ACCEPTED).body(job);
	}

	@GetMapping("/api/courses/{courseId}/summary-jobs/{jobId}/result")
	ResponseEntity<CandidateView> candidate(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long jobId) {
		managedJob(actor, courseId, jobId);
		SummaryResult result = this.jobs.candidate(jobId);
		return noStore(HttpStatus.OK).body(new CandidateView(jobId, result.pack().question(), result.pack().story(),
				result.pack().slides(), result.review(), result.warnings()));
	}

	@PostMapping("/api/courses/{courseId}/summary-jobs/{jobId}/publish")
	ResponseEntity<PublishedView> publish(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long jobId) {
		managedJob(actor, courseId, jobId);
		PublishedSummary summary = this.jobs.publish(jobId, actor.userId());
		this.audit.success(actor.userId(), actor.sessionId(), "summary_job.publish", "summary_job", jobId, courseId);
		return published(summary);
	}

	@GetMapping("/api/courses/{courseId}/files/{fileId}/summary")
	ResponseEntity<PublishedView> published(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId) {
		if (!this.policy.canRead(actor, courseId, fileId)) {
			throw notFound();
		}
		return this.jobs.published(fileId).map(this::published).orElseThrow(SummaryController::notFound);
	}

	/** 게시본을 내린다. 쪽별 요약, 파일 요약, 학습 대화가 학생에게 더는 보이지 않는다. 다시 게시할 수 있다. */
	@DeleteMapping("/api/courses/{courseId}/files/{fileId}/summary")
	ResponseEntity<Void> unpublish(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId) {
		requireManage(actor, courseId, fileId);
		if (!this.jobs.unpublish(fileId)) {
			throw notFound();
		}
		this.audit.success(actor.userId(), actor.sessionId(), "summary.unpublish", "file", fileId, courseId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/**
	 * 자료 상세 화면의 파일 요약("ASTRA 서포터즈 요약", 63_학습활동_자료상세). 게시한 쪽별 요약의 질문과 흐름으로 만들고 모델을 다시
	 * 부르지 않는다. 그 파일을 볼 수 있는 사람만 보고, 게시본이 없으면 404다(ADR-0032).
	 */
	@GetMapping("/api/courses/{courseId}/files/{fileId}/summary/overview")
	ResponseEntity<OverviewView> overview(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId) {
		if (!this.policy.canRead(actor, courseId, fileId)) {
			throw notFound();
		}
		PublishedSummary summary = this.jobs.published(fileId).orElseThrow(SummaryController::notFound);
		return noStore(HttpStatus.OK).body(OverviewView.of(summary));
	}

	@ExceptionHandler(SummaryJobException.class)
	ResponseEntity<ProblemDetail> failure(SummaryJobException ex) {
		HttpStatus status = switch (ex.code()) {
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case CONFLICT, INVALID_STATE, FILE_NOT_READY -> HttpStatus.CONFLICT;
			case UNSUPPORTED_FILE -> HttpStatus.UNPROCESSABLE_CONTENT;
			case LLM_DECIDER_REQUIRED -> HttpStatus.FORBIDDEN;
		};
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
		problem.setProperty("code", ex.code().name().toLowerCase());
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem);
	}

	private ResponseEntity<PublishedView> published(PublishedSummary summary) {
		SummaryJob job = this.jobs.get(summary.jobId());
		var key = this.renditions.readyKey(summary.fileId(), job.fileVersionNo());
		ResponseEntity.BodyBuilder response = noStore(HttpStatus.OK);
		// 전달 서명이 없는 환경에서는 쪽 이미지를 열 수 없으므로 경로도 알리지 않는다.
		List<DeliveryCookie> issued = key.map(this.cookies::forDocument).orElse(List.of());
		issued.forEach((c) -> response.header(HttpHeaders.SET_COOKIE, c.header()));
		var pack = summary.result().pack();
		return response.body(new PublishedView(summary.fileId(), summary.publishedAt(), pack.question(), pack.story(),
				pack.slides(), issued.isEmpty() ? null : "/doc/" + key.get() + "/"));
	}

	private SummaryJob managedJob(Actor actor, long courseId, long jobId) {
		SummaryJob job;
		try {
			job = this.jobs.get(jobId);
		}
		catch (SummaryJobException ex) {
			throw notFound();
		}
		requireManage(actor, courseId, job.fileId());
		return job;
	}

	// 생성은 LLM 비용이 드는 연산이라 사용자 단위로 횟수를 제한한다(SC-09).
	private void limitGeneration(Actor actor) {
		this.rateLimiter.check("summary-generate", actor.userId(), 20, Duration.ofHours(1));
	}

	private void requireManage(Actor actor, long courseId, long fileId) {
		if (!this.policy.canManage(actor, courseId, fileId)) {
			throw notFound();
		}
	}

	private static SummaryJobException notFound() {
		return new SummaryJobException(SummaryJobException.Code.NOT_FOUND, "찾을 수 없습니다.");
	}

	private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore());
	}

}
