package com.stackcorp.astra.summary.job;

import java.util.List;
import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.stackcorp.astra.files.api.StoredFiles;
import com.stackcorp.astra.files.api.StoredFiles.StoredFile;
import com.stackcorp.astra.summary.api.CourseStaff;
import com.stackcorp.astra.summary.job.SummaryJobException.Code;
import com.stackcorp.astra.summary.policy.ConsentRevoked;
import com.stackcorp.astra.summary.policy.LlmGate;
import com.stackcorp.astra.summary.policy.ProcessingScope;

/**
 * 요약 작업의 명령과 조회. 권한 판정은 이 서비스를 부르는 web 계층의 SummaryPolicy가 한다(ADR-0006 9번).
 * 생성 시작과 게시는 교수자의 명시적 요청으로만 부른다. 생성과 재시도는 과목의 결정권자(담당자 명단의 교수와 비정규 담당자)가
 * 요청하고, 그 사람의 쪽별 요약 동의가 있어야 한다(ADR-0032). 조교는 요약을 관리하지만 생성과 재시도는 요청하지 않는다.
 */
@Service
public class SummaryJobService {

	private final SummaryJobRepository jobs;

	private final StoredFiles files;

	private final SummaryStorage storage;

	private final LlmGate gate;

	private final CourseStaff staff;

	SummaryJobService(SummaryJobRepository jobs, StoredFiles files, SummaryStorage storage, LlmGate gate,
			CourseStaff staff) {
		this.jobs = jobs;
		this.files = files;
		this.storage = storage;
		this.gate = gate;
		this.staff = staff;
	}

	/**
	 * @param courseId 경로의 과목. 부르는 쪽이 파일이 이 과목에 속하는지 확인했다
	 * @throws com.stackcorp.astra.shared.ApiException 503 {@code llm_disabled}, 409 {@code llm_consent_required}
	 * @throws SummaryJobException {@link Code#LLM_DECIDER_REQUIRED} 요청한 사람이 결정권자가 아니다
	 */
	public SummaryJob request(long courseId, long fileId, long actorUserId) {
		requireLlmFor(courseId, actorUserId);
		StoredFile file = summarizable(fileId);
		return insert(file, courseId, actorUserId, null);
	}

	/** 실패하거나 취소된 작업을 새 작업으로 다시 실행한다. 끝난 쪽의 중간 결과는 이어받는다. */
	public SummaryJob retry(long courseId, long jobId, long actorUserId) {
		SummaryJob job = get(jobId);
		if (job.state() != JobState.FAILED && job.state() != JobState.CANCELLED) {
			throw new SummaryJobException(Code.INVALID_STATE, "실패하거나 취소된 작업만 다시 실행할 수 있습니다.");
		}
		requireLlmFor(courseId, actorUserId);
		return insert(summarizable(job.fileId()), courseId, actorUserId, jobId);
	}

	/**
	 * 요청한 사람이 책임 교수자다. 조교의 요청은 동의를 보기 전에 따로 거절한다. 문으로 보내면 교수가 동의했어도
	 * {@code llm_consent_required}가 되어 조교가 할 수 없는 동의를 안내하기 때문이다. 학생도 지나는 {@link LlmGate#require}에는
	 * 넣지 않는다.
	 */
	private void requireLlmFor(long courseId, long actorUserId) {
		this.gate.requireEnabled();
		if (!this.staff.isDecider(actorUserId, courseId)) {
			throw new SummaryJobException(Code.LLM_DECIDER_REQUIRED,
					"외부 AI 처리는 과목 담당 교수나 비정규 담당자가 요청합니다. 조교 계정으로는 쪽별 요약을 만들거나 다시 실행할 수 없습니다.");
		}
		this.gate.require(courseId, actorUserId, ProcessingScope.PAGE_SUMMARY);
	}

	/** 쪽별 요약 동의를 철회하면 그 교수자가 그 과목에 요청한 대기 작업을 취소하고, 실행 중인 작업에는 취소를 요청한다. */
	@EventListener
	public void onConsentRevoked(ConsentRevoked event) {
		if (event.scope() == ProcessingScope.PAGE_SUMMARY) {
			this.jobs.cancelForRevokedConsent(event.courseId(), event.instructorUserId());
		}
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
		return new PublishedSummary(job.fileId(), jobId, publication.publishedByUserId(), publication.publishedAt(),
				result);
	}

	/**
	 * 게시본을 내린다. 학생에게 쪽별 요약, 파일 요약, 학습 대화가 더는 보이지 않는다. 작업과 결과 객체는 남아 다시 게시할 수 있다.
	 * @return 게시본이 있었으면 true
	 */
	public boolean unpublish(long fileId) {
		return this.jobs.deletePublication(fileId);
	}

	/** 파일의 최근 작업 20개. 최신이 먼저다. */
	public List<SummaryJob> list(long fileId) {
		return this.jobs.listByFile(fileId, 20);
	}

	/** 게시 pointer만 읽는다. 결과 객체를 읽지 않으므로 질문마다 불러도 된다. */
	public Optional<Publication> publication(long fileId) {
		return this.jobs.findPublication(fileId);
	}

	/** 학생에게 보이는 게시본. */
	public Optional<PublishedSummary> published(long fileId) {
		return this.jobs.findPublication(fileId)
			.map((p) -> new PublishedSummary(fileId, p.jobId(), p.publishedByUserId(), p.publishedAt(), this.storage
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

	private SummaryJob insert(StoredFile file, long courseId, long actorUserId, Long retryOf) {
		try {
			return get(this.jobs.insert(file.fileId(), file.versionNo(), courseId, actorUserId, retryOf));
		}
		catch (DuplicateKeyException ex) {
			throw new SummaryJobException(Code.CONFLICT, "이 파일에 진행 중인 요약 작업이 있습니다.");
		}
	}

}
