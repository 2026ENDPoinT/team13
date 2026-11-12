package com.stackcorp.astra.summary.web;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.files.api.DeliveryCookies;
import com.stackcorp.astra.files.api.DeliveryCookies.DeliveryCookie;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.summary.job.PublishedSummary;
import com.stackcorp.astra.summary.job.SummaryJob;
import com.stackcorp.astra.summary.job.SummaryJobException;
import com.stackcorp.astra.summary.job.SummaryJobService;
import com.stackcorp.astra.summary.job.SummaryResult;
import com.stackcorp.astra.summary.web.SummaryViews.CandidateView;
import com.stackcorp.astra.summary.web.SummaryViews.JobView;
import com.stackcorp.astra.summary.web.SummaryViews.PublishedView;

/**
 * 강의 자료 쪽별 요약 API. 과목은 경로로 명시하고(SC-03), 권한이 없거나 다른 과목의 파일·작업이면 존재를 드러내지 않도록
 * 404로 응답한다. 요약 본문은 캐시하지 않는다(ADR-0007 7번).
 */
@RestController
class SummaryController {

	private final SummaryJobService jobs;

	private final SummaryPolicy policy;

	private final DocumentRenditions renditions;

	private final DeliveryCookies cookies;

	SummaryController(SummaryJobService jobs, SummaryPolicy policy, DocumentRenditions renditions,
			DeliveryCookies cookies) {
		this.jobs = jobs;
		this.policy = policy;
		this.renditions = renditions;
		this.cookies = cookies;
	}

	@PostMapping("/api/courses/{courseId}/files/{fileId}/summary-jobs")
	ResponseEntity<JobView> request(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId) {
		requireManage(actor, courseId, fileId);
		return noStore(HttpStatus.ACCEPTED).body(JobView.of(this.jobs.request(fileId, actor.userId())));
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
		return noStore(HttpStatus.OK).body(JobView.of(this.jobs.cancel(jobId)));
	}

	@PostMapping("/api/courses/{courseId}/summary-jobs/{jobId}/retry")
	ResponseEntity<JobView> retry(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long jobId) {
		managedJob(actor, courseId, jobId);
		return noStore(HttpStatus.ACCEPTED).body(JobView.of(this.jobs.retry(jobId, actor.userId())));
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
		return published(this.jobs.publish(jobId, actor.userId()));
	}

	@GetMapping("/api/courses/{courseId}/files/{fileId}/summary")
	ResponseEntity<PublishedView> published(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId) {
		if (!this.policy.canRead(actor, courseId, fileId)) {
			throw notFound();
		}
		return this.jobs.published(fileId).map(this::published).orElseThrow(SummaryController::notFound);
	}

	@ExceptionHandler(SummaryJobException.class)
	ResponseEntity<ProblemDetail> failure(SummaryJobException ex) {
		HttpStatus status = switch (ex.code()) {
			case NOT_FOUND -> HttpStatus.NOT_FOUND;
			case CONFLICT, INVALID_STATE, FILE_NOT_READY -> HttpStatus.CONFLICT;
			case UNSUPPORTED_FILE -> HttpStatus.UNPROCESSABLE_CONTENT;
		};
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, ex.getMessage());
		problem.setProperty("code", ex.code().name().toLowerCase());
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(problem);
	}

	private ResponseEntity<PublishedView> published(PublishedSummary summary) {
		SummaryJob job = this.jobs.get(summary.jobId());
		var key = this.renditions.readyKey(summary.fileId(), job.fileVersionNo());
		ResponseEntity.BodyBuilder response = noStore(HttpStatus.OK);
		key.ifPresent((k) -> {
			for (DeliveryCookie c : this.cookies.forDocument(k)) {
				response.header(HttpHeaders.SET_COOKIE, ResponseCookie.from(c.name(), c.value())
					.path(c.path())
					.maxAge(c.maxAge())
					.secure(true)
					.httpOnly(true)
					.sameSite("Lax")
					.build()
					.toString());
			}
		});
		var pack = summary.result().pack();
		return response.body(new PublishedView(summary.fileId(), summary.publishedAt(), pack.question(), pack.story(),
				pack.slides(), key.map((k) -> "/doc/" + k + "/").orElse(null)));
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
