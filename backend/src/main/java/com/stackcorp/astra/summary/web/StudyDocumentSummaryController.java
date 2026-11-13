package com.stackcorp.astra.summary.web;

import java.time.Duration;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.files.api.DeliveryCookies;
import com.stackcorp.astra.files.api.DeliveryCookies.DeliveryCookie;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.StudyRoomDocumentSummaries;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;
import com.stackcorp.astra.summary.job.StudyDocumentSummaryService;
import com.stackcorp.astra.summary.job.StudyDocumentSummaryService.Requested;
import com.stackcorp.astra.summary.job.StudyDocumentSummaryService.Status;
import com.stackcorp.astra.summary.web.SummaryViews.PublishedView;

/**
 * 학습실 내 문서의 쪽별 요약 API(ADR-0040 14번). 문서의 소유자만 요청하고 읽는다. 남의 문서와 없는 문서는 같은 404다. 완료된 요약은
 * 강의자료의 게시본과 같은 모양({@link PublishedView})이고, 쪽 이미지 cookie는 이 응답을 받는 소유자에게만 발급한다. 요약 본문은
 * 캐시하지 않는다(ADR-0007 7번).
 */
@RestController
class StudyDocumentSummaryController {

	static final String PATH = "/api/me/study-room/documents/{documentId}/summary";

	/** 대기·처리 중인 요약을 다시 읽을 간격. */
	private static final Duration POLL_AFTER = Duration.ofSeconds(5);

	private final StudyDocumentSummaryService summaries;

	private final DocumentRenditions renditions;

	private final DeliveryCookies cookies;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	StudyDocumentSummaryController(StudyDocumentSummaryService summaries, DocumentRenditions renditions,
			DeliveryCookies cookies, RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit) {
		this.summaries = summaries;
		this.renditions = renditions;
		this.cookies = cookies;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
	}

	/**
	 * 요약을 요청한다. 새 작업을 만들었거나 진행 중이면 202, 이미 완료된 요약이 있으면 200과 그 요약이다. 실패한 뒤의 요청은 새 작업이다.
	 * {@code Idempotency-Key}를 보내면 같은 key의 재전송은 작업을 더 만들지 않고 지금 상태를 돌려준다.
	 */
	@PostMapping(PATH)
	ResponseEntity<DocumentSummaryView> request(@AuthenticationPrincipal Actor actor, @PathVariable long documentId,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		long id = positive(documentId);
		// 생성은 모델 비용이 드는 연산이라 강의자료 요약 생성과 같은 사용자 단위 제한을 함께 쓴다(SC-09).
		this.rateLimiter.check("summary-generate", actor.userId(), 20, Duration.ofHours(1));
		this.idempotency.execute(actor.userId(), "study_room.document.summary", idempotencyKey, List.of(id),
				Receipt.class, () -> {
					Requested requested = this.summaries.request(actor.userId(), id);
					if (requested.created()) {
						this.audit.success(actor.userId(), actor.sessionId(), "study_room.document.summary",
								"summary_job", requested.status().job().id(), null);
					}
					return new Receipt(requested.status().job().id());
				});
		Status status = this.summaries.status(actor.userId(), id);
		return view(status, StudyRoomDocumentSummaries.READY.equals(status.status()) ? HttpStatus.OK
				: HttpStatus.ACCEPTED);
	}

	/** 가장 최근 요약의 상태. 요청한 적이 없으면 404다. */
	@GetMapping(PATH)
	ResponseEntity<DocumentSummaryView> get(@AuthenticationPrincipal Actor actor, @PathVariable long documentId) {
		return view(this.summaries.status(actor.userId(), positive(documentId)), HttpStatus.OK);
	}

	private ResponseEntity<DocumentSummaryView> view(Status status, HttpStatus code) {
		ResponseEntity.BodyBuilder response = ResponseEntity.status(code).cacheControl(CacheControl.noStore());
		String state = status.status();
		if (StudyRoomDocumentSummaries.QUEUED.equals(state) || StudyRoomDocumentSummaries.PROCESSING.equals(state)) {
			response.header(HttpHeaders.RETRY_AFTER, Long.toString(POLL_AFTER.toSeconds()));
		}
		PublishedView summary = null;
		if (status.result() != null) {
			var job = status.job();
			var key = this.renditions.readyKey(job.fileId(), job.fileVersionNo());
			// 전달 서명이 없는 환경에서는 쪽 이미지를 열 수 없으므로 경로도 알리지 않는다. 게시본 응답과 같다.
			List<DeliveryCookie> issued = key.map(this.cookies::forDocument).orElse(List.of());
			issued.forEach((c) -> response.header(HttpHeaders.SET_COOKIE, c.header()));
			var pack = status.result().pack();
			summary = new PublishedView(job.fileId(), job.finishedAt(), pack.question(), pack.story(), pack.slides(),
					issued.isEmpty() ? null : "/doc/" + key.get() + "/");
		}
		return response.body(new DocumentSummaryView(state, status.message(), summary));
	}

	private static long positive(long id) {
		if (id < 1) {
			throw ApiException.invalid("ID가 올바르지 않습니다.");
		}
		return id;
	}

	/** 멱등 원장의 receipt. 요약 본문은 원장에 남기지 않는다. */
	record Receipt(long jobId) {
	}

	/**
	 * @param status queued, processing, ready, failed
	 * @param message 실패했을 때만 있는 정해 둔 문장
	 * @param summary 완료됐을 때만 있는 요약. 강의자료 게시본과 같은 모양이고 publishedAt은 작업이 끝난 일시다
	 */
	record DocumentSummaryView(String status, @JsonInclude(JsonInclude.Include.NON_NULL) String message,
			@JsonInclude(JsonInclude.Include.NON_NULL) PublishedView summary) {
	}

}
