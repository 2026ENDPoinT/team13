package com.stackcorp.astra.summary.job;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import com.stackcorp.astra.files.api.StoredFiles;
import com.stackcorp.astra.files.api.StoredFiles.StoredFile;
import com.stackcorp.astra.files.api.StudyRoomDocumentSummaries;
import com.stackcorp.astra.files.api.StudyRoomDocuments;
import com.stackcorp.astra.files.api.StudyRoomDocuments.OwnedDocument;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.summary.policy.LlmGate;
import com.stackcorp.astra.summary.policy.ReadCommitted;

/**
 * 학생이 학습실에 올린 자기 PDF의 쪽별 요약(ADR-0040 14번). 강의자료 요약과 같은 원장(summary_jobs), pipeline, worker, 출력 검사를 쓴다.
 * 다른 점은 셋이다.
 * <ul>
 * <li>소유자가 학생이라 교수자 동의를 보지 않는다. 배포 스위치와 제공자 설정({@link LlmGate#requireEnabled})은 같다.</li>
 * <li>게시하지 않는다. 완료된 결과는 요청한 학생만 읽는다. 과목 경로의 요약 API는 이 파일이 과목 첨부가 아니어서 열지 못한다.</li>
 * <li>사용자마다 하루 요청 수 상한({@code astra.summary.study-document-daily-limit})이 있다. 상한은 사용자 행을 잠근 뒤 세므로 동시에
 * 보내도 넘지 않는다.</li>
 * </ul>
 * 같은 문서에 진행 중인 작업이 있으면 새 작업을 만들지 않고 그 작업을 돌려준다. 완료된 요약이 있어도 그대로 돌려준다. 실패하거나 취소된
 * 뒤의 요청은 새 작업이고, 앞 작업의 중간 결과를 이어받는다.
 */
@Service
public class StudyDocumentSummaryService implements StudyRoomDocumentSummaries {

	/** 검사 중인 문서에 요청했을 때의 {@code Retry-After}. 검사 결과 poll 간격과 같다. */
	static final Duration SCAN_RETRY_AFTER = Duration.ofSeconds(10);

	private final SummaryJobRepository jobs;

	private final StudyRoomDocuments documents;

	private final StoredFiles files;

	private final SummaryStorage storage;

	private final LlmGate gate;

	private final SummaryProperties properties;

	private final JdbcClient jdbc;

	private final ReadCommitted transaction;

	StudyDocumentSummaryService(SummaryJobRepository jobs, StudyRoomDocuments documents, StoredFiles files,
			SummaryStorage storage, LlmGate gate, SummaryProperties properties, JdbcClient jdbc,
			PlatformTransactionManager transactionManager) {
		this.jobs = jobs;
		this.documents = documents;
		this.files = files;
		this.storage = storage;
		this.gate = gate;
		this.properties = properties;
		this.jdbc = jdbc;
		this.transaction = new ReadCommitted(transactionManager);
	}

	/**
	 * 요약을 요청한다. 진행 중이거나 완료된 작업이 있으면 그 작업을 돌려준다.
	 *
	 * @return 요청 뒤의 상태와 새 작업을 만들었는지
	 * @throws ApiException 404 남의 문서나 없는 문서, 503 {@code llm_disabled}, 409 {@code file_scanning}, 422
	 * {@code content_rejected}, 429 {@code summary_daily_limit}
	 */
	public Requested request(long ownerUserId, long documentId) {
		OwnedDocument document = owned(ownerUserId, documentId);
		this.gate.requireEnabled();
		StoredFile file = summarizable(document);
		Optional<SummaryJob> latest = latest(document.fileId());
		if (latest.isPresent() && latest.get().state() != JobState.FAILED
				&& latest.get().state() != JobState.CANCELLED) {
			return new Requested(status(latest.get()), false);
		}
		Long retryOf = latest.map(SummaryJob::id).orElse(null);
		Long jobId = this.transaction.execute(() -> {
			// 사용자 행을 잠가 같은 사용자의 요청을 차례로 센다. 다른 사용자의 요청과는 엇갈리지 않는다.
			this.jdbc.sql("SELECT user_id FROM users WHERE user_id = :user FOR UPDATE")
				.param("user", ownerUserId)
				.query(Long.class)
				.single();
			if (this.jobs.studyRoomRequestsToday(ownerUserId) >= this.properties.studyDocumentDailyLimit()) {
				throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "summary_daily_limit",
						"오늘 요청할 수 있는 문서 요약을 모두 썼습니다. 내일 다시 요청해 주세요.", untilTomorrow());
			}
			try {
				return Long.valueOf(this.jobs.insertStudyRoom(file.fileId(), file.versionNo(), ownerUserId, retryOf));
			}
			catch (DuplicateKeyException ex) {
				// 같은 문서의 다른 요청이 먼저 작업을 만들었다.
				return null;
			}
		});
		if (jobId == null) {
			return new Requested(status(latest(document.fileId()).orElseThrow()), false);
		}
		return new Requested(status(this.jobs.find(jobId).orElseThrow()), true);
	}

	/**
	 * 가장 최근 요약의 상태.
	 * @throws ApiException 404 남의 문서나 없는 문서, 또는 요청한 적이 없을 때
	 */
	public Status status(long ownerUserId, long documentId) {
		OwnedDocument document = owned(ownerUserId, documentId);
		return status(latest(document.fileId()).orElseThrow(ApiException::notFound));
	}

	@Override
	public Map<Long, String> statuses(Collection<Long> fileIds) {
		Map<Long, String> statuses = new LinkedHashMap<>();
		if (!fileIds.isEmpty()) {
			for (SummaryJob job : this.jobs.latestOfFiles(fileIds)) {
				statuses.put(job.fileId(), code(job.state()));
			}
		}
		return statuses;
	}

	@Override
	public void discard(long fileId) {
		this.jobs.cancelForFile(fileId);
	}

	private Status status(SummaryJob job) {
		String code = code(job.state());
		if (READY.equals(code)) {
			SummaryResult result = this.storage.readResult(job.fileId(), job.id())
				.orElseThrow(() -> new IllegalStateException("완료된 작업의 결과가 저장소에 없습니다: " + job.id()));
			return new Status(job, code, null, result);
		}
		String message = FAILED.equals(code) ? FailureText.display(job.errorCode(), job.errorMessage()) : null;
		if (FAILED.equals(code) && message == null) {
			message = job.state() == JobState.CANCELLED ? "요약을 멈췄습니다. 다시 요청해 주세요." : null;
		}
		return new Status(job, code, message, null);
	}

	private Optional<SummaryJob> latest(long fileId) {
		return this.jobs.listByFile(fileId, 1).stream().findFirst();
	}

	private OwnedDocument owned(long ownerUserId, long documentId) {
		return this.documents.owned(ownerUserId, documentId).orElseThrow(ApiException::notFound);
	}

	private StoredFile summarizable(OwnedDocument document) {
		if (OwnedDocument.SCANNING.equals(document.state())) {
			throw new ApiException(HttpStatus.CONFLICT, "file_scanning", "악성코드 검사를 마치지 않은 문서입니다.",
					SCAN_RETRY_AFTER);
		}
		if (OwnedDocument.REJECTED.equals(document.state())) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "content_rejected", "검사에서 거부된 문서입니다.");
		}
		StoredFile file = this.files.find(document.fileId()).orElseThrow(ApiException::notFound);
		if (!file.ready()) {
			throw new ApiException(HttpStatus.CONFLICT, "file_scanning", "악성코드 검사를 마치지 않은 문서입니다.",
					SCAN_RETRY_AFTER);
		}
		return file;
	}

	private static String code(JobState state) {
		return switch (state) {
			case QUEUED -> QUEUED;
			case RUNNING -> PROCESSING;
			case COMPLETED -> READY;
			case FAILED, CANCELLED -> FAILED;
		};
	}

	// 기관 시간대의 다음 자정까지. JVM 시간대는 기관 시간대에 고정돼 있다(ADR-0039).
	private static Duration untilTomorrow() {
		ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
		ZonedDateTime midnight = LocalDate.now(ZoneId.systemDefault()).plusDays(1).atStartOfDay(ZoneId.systemDefault());
		return Duration.between(now, midnight).plusSeconds(1);
	}

	/**
	 * @param status queued, processing, ready, failed
	 * @param message 실패했을 때 화면에 보이는 정해 둔 문장
	 * @param result 완료됐을 때의 결과
	 */
	public record Status(SummaryJob job, String status, String message, SummaryResult result) {
	}

	/** @param created 이 요청이 새 작업을 만들었는지 */
	public record Requested(Status status, boolean created) {
	}

}
