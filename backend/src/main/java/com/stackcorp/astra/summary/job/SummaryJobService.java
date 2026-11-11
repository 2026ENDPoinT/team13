package com.stackcorp.astra.summary.job;

import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stackcorp.astra.files.api.StoredFiles;
import com.stackcorp.astra.files.api.StoredFiles.StoredFile;
import com.stackcorp.astra.summary.job.SummaryJobException.Code;

/**
 * 요약 작업의 명령과 조회. 권한 판정은 이 서비스를 부르는 web 계층의 SummaryPolicy가 한다(ADR-0006 9번).
 * 생성 시작과 게시는 교수자의 명시적 요청으로만 부른다.
 */
@Service
public class SummaryJobService {

	private final SummaryJobRepository jobs;

	private final StoredFiles files;

	private final SummaryStorage storage;

	SummaryJobService(SummaryJobRepository jobs, StoredFiles files, SummaryStorage storage) {
		this.jobs = jobs;
		this.files = files;
		this.storage = storage;
	}

	public SummaryJob request(long fileId, Long actorUserId) {
		StoredFile file = summarizable(fileId);
		return insert(file, actorUserId, null);
	}

	/** 실패하거나 취소된 작업을 새 작업으로 다시 실행한다. 끝난 쪽의 중간 결과는 이어받는다. */
	public SummaryJob retry(long jobId, Long actorUserId) {
		SummaryJob job = get(jobId);
		if (job.state() != JobState.FAILED && job.state() != JobState.CANCELLED) {
			throw new SummaryJobException(Code.INVALID_STATE, "실패하거나 취소된 작업만 다시 실행할 수 있습니다.");
		}
		return insert(summarizable(job.fileId()), actorUserId, jobId);
	}

	/** 대기 중이면 바로 취소하고, 실행 중이면 취소를 요청한다. worker는 다음 모델 호출 전에 멈춘다. */
	public SummaryJob cancel(long jobId) {
		if (get(jobId).state().terminal()
				|| !(this.jobs.cancelQueued(jobId) || this.jobs.requestCancel(jobId))) {
			throw new SummaryJobException(Code.INVALID_STATE, "이미 끝난 작업입니다.");
		}
		return get(jobId);
	}

	public SummaryJob get(long jobId) {
		return this.jobs.find(jobId).orElseThrow(() -> new SummaryJobException(Code.NOT_FOUND, "요약 작업이 없습니다."));
	}

	/** 게시 전 확인용 후보 결과. 완료된 작업에만 있다. */
	public SummaryResult candidate(long jobId) {
		SummaryJob job = get(jobId);
		if (job.state() != JobState.COMPLETED) {
			throw new SummaryJobException(Code.INVALID_STATE, "완료된 작업만 결과가 있습니다.");
		}
		return this.storage.readResult(job.fileId(), jobId)
			.orElseThrow(() -> new IllegalStateException("완료된 작업의 결과가 저장소에 없습니다: " + jobId));
	}

	/** 완료된 작업의 결과를 그 파일의 게시본으로 바꾼다. */
	@Transactional
	public PublishedSummary publish(long jobId, Long actorUserId) {
		SummaryResult result = candidate(jobId);
		SummaryJob job = get(jobId);
		this.jobs.publish(job.fileId(), jobId, actorUserId);
		var publication = this.jobs.findPublication(job.fileId()).orElseThrow();
		return new PublishedSummary(job.fileId(), jobId, publication.publishedAt(), result);
	}

	/** 파일의 최근 작업 20개. 최신이 먼저다. */
	public List<SummaryJob> list(long fileId) {
		return this.jobs.listByFile(fileId, 20);
	}

	/** 학생에게 보이는 게시본. */
	public Optional<PublishedSummary> published(long fileId) {
		return this.jobs.findPublication(fileId)
			.map((p) -> new PublishedSummary(fileId, p.jobId(), p.publishedAt(), this.storage
				.readResult(fileId, p.jobId())
				.orElseThrow(() -> new IllegalStateException("게시한 결과가 저장소에 없습니다: " + p.jobId()))));
	}

	private StoredFile summarizable(long fileId) {
		StoredFile file = this.files.find(fileId)
			.orElseThrow(() -> new SummaryJobException(Code.NOT_FOUND, "파일이 없습니다."));
		if (!file.pdf()) {
			throw new SummaryJobException(Code.UNSUPPORTED_FILE, "PDF 파일만 요약합니다.");
		}
		if (!file.ready()) {
			throw new SummaryJobException(Code.FILE_NOT_READY, "검사를 마치지 않은 파일입니다.");
		}
		return file;
	}

	private SummaryJob insert(StoredFile file, Long actorUserId, Long retryOf) {
		try {
			return get(this.jobs.insert(file.fileId(), file.versionNo(), actorUserId, retryOf));
		}
		catch (DuplicateKeyException ex) {
			throw new SummaryJobException(Code.CONFLICT, "이 파일에 진행 중인 요약 작업이 있습니다.");
		}
	}

}
