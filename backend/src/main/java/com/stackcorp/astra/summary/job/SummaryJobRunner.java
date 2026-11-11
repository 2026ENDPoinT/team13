package com.stackcorp.astra.summary.job;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.DocumentRenditions.Rendition;
import com.stackcorp.astra.files.api.RenditionException;
import com.stackcorp.astra.files.api.StoredFiles;
import com.stackcorp.astra.shared.metrics.MetricTags;
import com.stackcorp.astra.summary.api.LlmClient;
import com.stackcorp.astra.summary.pipeline.GenerateInput;
import com.stackcorp.astra.summary.pipeline.GenerateResult;
import com.stackcorp.astra.summary.pipeline.GenerationException;
import com.stackcorp.astra.summary.pipeline.GenerationProgress;
import com.stackcorp.astra.summary.pipeline.SummaryPipeline;
import com.stackcorp.astra.summary.pipeline.Usage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * worker 프로필에서 대기 작업을 하나씩 실행한다. 여러 worker가 떠도 원장의 SKIP LOCKED로 한 곳만 잡는다.
 * 로그에는 작업 ID와 코드만 남긴다. 자료 본문과 모델 출력은 남기지 않는다(ADR-0007 9번).
 * process가 종료할 때는 새 작업을 잡지 않고, 실행 중인 작업은 다음 모델 호출 경계에서 멈춰 lease 만료와 같은
 * {@code interrupted}로 바로 닫는다. lease가 끝날 때까지 작업이 running으로 남지 않는다.
 * <p>
 * 끝낸 작업을 상태와 오류 code별로 세고(astra.summary.jobs.finished), 모델 호출은 {@link MeteredLlmClient}로 잰다. 대기열 길이와 나이는
 * {@link SummaryBacklog}가 낸다.
 */
@Component
@Profile("worker")
class SummaryJobRunner implements SmartLifecycle, AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(SummaryJobRunner.class);

	private final SummaryJobRepository jobs;

	private final DocumentRenditions renditions;

	private final StoredFiles files;

	private final SummaryStorage storage;

	private final ObjectProvider<LlmClient> llm;

	private final SummaryProperties properties;

	private final String owner;

	private final ScheduledExecutorService leaseRenewer = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("summary-lease").factory());

	private volatile boolean running;

	private final MeterRegistry meters;

	SummaryJobRunner(SummaryJobRepository jobs, DocumentRenditions renditions, StoredFiles files,
			SummaryStorage storage, ObjectProvider<LlmClient> llm, SummaryProperties properties,
			MeterRegistry meters) {
		this.jobs = jobs;
		this.renditions = renditions;
		this.files = files;
		this.storage = storage;
		this.llm = llm;
		this.properties = properties;
		this.owner = properties.workerId() != null ? properties.workerId() : defaultOwner();
		this.meters = meters;
	}

	@Scheduled(fixedDelayString = "${astra.summary.poll-interval:5s}",
			initialDelayString = "${astra.summary.poll-interval:5s}")
	void poll() {
		while (runOnce()) {
			// 대기 작업이 없을 때까지 이어서 실행한다.
		}
	}

	@Scheduled(fixedDelayString = "${astra.summary.lease-renewal:30s}")
	void failExpired() {
		int closed = this.jobs.failExpired();
		finished(JobState.FAILED, "interrupted", closed);
		if (closed > 0) {
			log.warn("summary jobs interrupted by expired lease count={}", closed);
		}
	}

	/** @return 작업 하나를 실행했으면 true */
	boolean runOnce() {
		if (!this.running) {
			return false;
		}
		LlmClient configured = this.llm.getIfAvailable();
		LlmClient client = configured != null ? new MeteredLlmClient(configured, this.meters) : null;
		Optional<SummaryJob> claimed = this.jobs.claimNext(this.owner, this.properties.lease(),
				client != null ? client.model() : null, SummaryPipeline.PROMPT_VERSION);
		if (claimed.isEmpty()) {
			return false;
		}
		SummaryJob job = claimed.get();
		if (client == null) {
			finish(job, JobState.FAILED, new Usage(0, 0, 0), "not_configured",
					"모델 제공자가 설정되지 않았습니다.");
			return true;
		}
		AtomicBoolean leaseLost = new AtomicBoolean();
		long renewal = this.properties.leaseRenewal().toMillis();
		ScheduledFuture<?> renewer = this.leaseRenewer.scheduleAtFixedRate(() -> {
			try {
				if (!this.jobs.renewLease(job.id(), this.owner, this.properties.lease())) {
					leaseLost.set(true);
				}
			}
			catch (RuntimeException ex) {
				log.warn("summary lease renewal failed jobId={}", job.id(), ex);
			}
		}, renewal, renewal, TimeUnit.MILLISECONDS);
		try {
			execute(job, client, leaseLost);
		}
		finally {
			renewer.cancel(false);
		}
		return true;
	}

	@Override
	public void start() {
		this.running = true;
	}

	/** 종료 단계에서 부른다. 이후 대기 작업을 잡지 않고, 실행 중인 작업에 멈추라고 알린다. */
	@Override
	public void stop() {
		this.running = false;
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	@Override
	public void close() {
		this.leaseRenewer.shutdownNow();
	}

	private void execute(SummaryJob job, LlmClient client, AtomicBoolean leaseLost) {
		long started = System.nanoTime();
		Rendition rendition;
		try {
			rendition = this.renditions.prepare(job.fileId());
		}
		catch (RenditionException ex) {
			String detail = ex.detail() != null ? " (" + ex.detail().toLowerCase() + ")" : "";
			finish(job, JobState.FAILED, new Usage(0, 0, 0), "file", ex.getMessage() + detail);
			return;
		}
		if (rendition.fileVersionNo() != job.fileVersionNo()) {
			finish(job, JobState.FAILED, new Usage(0, 0, 0), "file", "요청한 뒤 파일이 바뀌었습니다. 다시 요청해 주세요.");
			return;
		}
		String title = this.files.find(job.fileId()).map((f) -> stripExtension(f.name())).orElse(null);
		// 취소 요청은 모델 호출 앞뒤마다 원장에서 확인한다. 기본키 조회 한 번이라 모델 호출에 비해 비용이 없다.
		// 자료 파일이 지워져도 멈춘다. 지운 파일에는 결과를 쓰지 않는다(ADR-0011).
		GenerateInput input = new GenerateInput(String.valueOf(job.fileId()), title,
				new RenditionSource(this.renditions, rendition),
				this.storage.checkpoints(job.fileId(), this.jobs.rootOf(job.id())),
				() -> !this.running || leaseLost.get() || this.jobs.cancelRequested(job.id()) || fileDeleted(job),
				(GenerationProgress p) -> this.jobs.progress(job.id(), this.owner, p.stage().name().toLowerCase(),
						p.pagesDone(), p.pagesTotal(), p.usage()),
				GenerateInput.Limits.DEFAULT);
		try {
			GenerateResult result = new SummaryPipeline(client).generate(input);
			if (fileDeleted(job)) {
				finishFileDeleted(job, result.usage());
				return;
			}
			this.storage.writeResult(job.fileId(), job.id(),
					new SummaryResult(result.pack(), result.review(), result.warnings()));
			finish(job, JobState.COMPLETED, result.usage(), null, null);
			log.info("summary job completed jobId={} pages={} calls={} elapsedMs={}", job.id(),
					result.pack().slides().size(), result.usage().calls(), (System.nanoTime() - started) / 1_000_000);
		}
		catch (GenerationException ex) {
			Usage usage = ex.usage();
			if (ex.code() == GenerationException.Code.CANCELLED) {
				if (leaseLost.get()) {
					log.warn("summary job lost lease jobId={}", job.id());
					return;
				}
				if (fileDeleted(job)) {
					finishFileDeleted(job, usage);
					return;
				}
				if (!this.running && !this.jobs.cancelRequested(job.id())) {
					finish(job, JobState.FAILED, usage, "interrupted", "작업을 실행하던 worker가 멈췄습니다. 다시 시도해 주세요.");
					log.info("summary job interrupted by shutdown jobId={}", job.id());
					return;
				}
				finish(job, JobState.CANCELLED, usage, null, null);
				return;
			}
			if (fileDeleted(job)) {
				finishFileDeleted(job, usage);
				return;
			}
			String stage = ex.stage().name().toLowerCase();
			this.storage.writeFailure(job.fileId(), job.id(), stage, ex.getMessage());
			finish(job, JobState.FAILED, usage, ex.code().name().toLowerCase(), failureMessage(ex));
		}
		catch (RuntimeException ex) {
			if (fileDeleted(job)) {
				finishFileDeleted(job, new Usage(0, 0, 0));
				return;
			}
			log.error("summary job failed jobId={} error={}", job.id(), ex.getClass().getName(), ex);
			finish(job, JobState.FAILED, new Usage(0, 0, 0), "internal", "요약 작업 중 내부 오류가 났습니다.");
		}
	}

	private boolean fileDeleted(SummaryJob job) {
		return this.files.find(job.fileId()).isEmpty();
	}

	// 실행 중에 자료가 지워졌다. 결과를 남기지 않고 실패로 닫는다. 지운 파일은 게시할 수도 없다.
	private void finishFileDeleted(SummaryJob job, Usage usage) {
		log.info("summary job stopped, file deleted jobId={}", job.id());
		finish(job, JobState.FAILED, usage, "file", "자료 파일이 삭제됐습니다.");
	}

	private void finish(SummaryJob job, JobState state, Usage usage, String errorCode, String errorMessage) {
		String message = errorMessage == null || errorMessage.length() <= 500 ? errorMessage
				: errorMessage.substring(0, 500);
		if (!this.jobs.finish(job.id(), this.owner, state, usage, errorCode, message)) {
			log.warn("summary job finish ignored, lease lost jobId={} state={}", job.id(), state);
			return;
		}
		finished(state, errorCode, 1);
	}

	private void finished(JobState state, String errorCode, int count) {
		if (count > 0) {
			Counter.builder("astra.summary.jobs.finished")
				.description("끝난 요약 작업 수")
				.tag("state", state.value())
				.tag("code", MetricTags.code(errorCode))
				.register(this.meters)
				.increment(count);
		}
	}

	// DB에는 자료 본문이 섞이지 않는 문구만 둔다. 검사 실패의 세부는 failure.json에 있다.
	private static String failureMessage(GenerationException ex) {
		String stage = ex.stage().name().toLowerCase();
		return switch (ex.code()) {
			case INVALID_OUTPUT -> "모델 출력이 검사를 통과하지 못했습니다 (단계: " + stage + ").";
			case PROVIDER, BUDGET, INPUT -> ex.getMessage();
			case CANCELLED -> null;
		};
	}

	private static String stripExtension(String name) {
		int dot = name.lastIndexOf('.');
		return dot > 0 ? name.substring(0, dot) : name;
	}

	private static String defaultOwner() {
		String host;
		try {
			host = InetAddress.getLocalHost().getHostName();
		}
		catch (UnknownHostException ex) {
			host = "unknown";
		}
		return host + ":" + ProcessHandle.current().pid();
	}

}
