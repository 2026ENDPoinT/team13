package com.stackcorp.astra.files.studyroom;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.files.api.SpeechToText;
import com.stackcorp.astra.files.api.SpeechToText.Transcript;
import com.stackcorp.astra.files.api.SpeechToTextException;
import com.stackcorp.astra.files.api.TextModel;
import com.stackcorp.astra.files.api.TextModelException;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.files.studyroom.RecordingJobRecords.Claimed;
import com.stackcorp.astra.files.studyroom.RecordingJobRecords.Expired;
import com.stackcorp.astra.files.studyroom.RecordingJobRecords.Running;
import com.stackcorp.astra.files.studyroom.RecordingJobRecords.TranscriptRow;
import com.stackcorp.astra.files.studyroom.RecordingJobRecords.Usage;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Segment;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Summary;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Transcription;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.metrics.MetricTags;
import com.stackcorp.astra.shared.schedule.SchedulerLock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.json.JsonMapper;

/**
 * worker 프로필에서 학습실 녹음의 전사·요약 작업을 실행한다(ADR-0041).
 * <p>
 * 잡기: {@link SchedulerLock}({@value #CLAIM_LOCK})으로 모든 worker의 잡기를 차례로 세운 뒤 한 transaction에서 정리(거부된 녹음,
 * 설정이 없어진 종류, 전사가 실패한 녹음의 요약)를 하고, 종류마다 실행 중 수가 상한 미만이면 그 종류의 가장 오래된 대기 작업을 잠가
 * 전사와 요약 가운데 다음 시도 시각이 이른 것을 실행 중으로 바꾼다. 잡기가 차례로 서므로 실행 중 수를 세고 늘리는 사이에 다른 worker가
 * 끼어들지 못한다. 전사는 검사를 마친(ready) 녹음만, 요약은 전사가 ready인 녹음만 잡는다. 전사는 길이 상한과 사용자별 하루·한 달
 * 사용량을 확인하고 선언 길이를 사용량에 잡아 둔다.
 * <p>
 * 실행: poll이 빈 자리만큼 잡아 executor(종류별 상한의 합만큼 thread)에 넘기고, 작업마다 lease를 따로 늘린다. 잠금과 transaction 밖에서
 * 한다. 원본은 저장소에서 stream으로 열어 그대로 보낸다. 기록은 lease 소유자일 때만 반영되어, 그 사이 녹음을 지웠거나 lease를 잃었으면
 * 결과를 버리되 제공자가 잰 실제 길이는 청구한다. 전사가 끝나면 사용량을 실제 길이로 고치고, 실제 길이가 상한을 넘거나 선언보다 허용
 * 범위 넘게 길면 결과를 내주지 않는다.
 * <p>
 * 일시 오류는 대기 시간을 늘려 가며 다시 하고, 횟수를 다 쓰거나 다시 해도 같은 실패면 정해 둔 코드로 닫는다. 실패·재시도·lease 만료는
 * 잡아 둔 사용량을 돌려준다. 로그와 감사 기록에는 ID, 종류, 코드, 길이만 남긴다. 전사 글, 요약, 제공자 응답은 남기지 않는다.
 * <p>
 * 지표: astra.files.recording_study.jobs{kind, outcome, code}(실행 하나의 시간과 결과, outcome은 ready, retry, failed, discarded),
 * astra.files.recording_study.interrupted{kind, outcome}(lease가 끝나 회수한 실행).
 */
@Component
@Profile("worker")
class RecordingStudyRunner implements AutoCloseable {

	static final String CLAIM_LOCK = "files.recording_study_claim";

	private static final Duration CLAIM_LOCK_AT_MOST = Duration.ofMinutes(1);

	private static final Logger log = LoggerFactory.getLogger(RecordingStudyRunner.class);

	private static final int ATTEMPTS = 3;

	private static final int BATCH = 50;

	/** 잡기 한 번에 한도 초과로 닫고 다음 작업을 보는 최대 수. */
	private static final int CLAIM_SCAN = 20;

	private final RecordingJobRecords jobs;

	private final ObjectStore store;

	private final SpeechToText speech;

	private final TextModel model;

	private final JsonMapper json;

	private final TransactionTemplate transaction;

	private final AuditLog audit;

	private final RecordingStudyProperties properties;

	private final SchedulerLock lock;

	private final MeterRegistry meters;

	private final String owner;

	/** 잡은 작업을 실행한다. 크기는 종류마다 상한(max-concurrent)을 합친 것이다. 넘게 잡지 않도록 {@link #slots}로 센다. */
	private final ExecutorService executor;

	private final Semaphore slots;

	private volatile boolean closed;

	private final ScheduledExecutorService leaseRenewer = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().daemon().name("recording-study-lease").factory());

	RecordingStudyRunner(RecordingJobRecords jobs, ObjectStore store, SpeechToText speech, TextModel model,
			JsonMapper json, PlatformTransactionManager transactionManager, AuditLog audit,
			RecordingStudyProperties properties, SchedulerLock lock, MeterRegistry meters) {
		this.jobs = jobs;
		this.store = store;
		this.speech = speech;
		this.model = model;
		this.json = json;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.audit = audit;
		this.properties = properties;
		this.lock = lock;
		this.meters = meters;
		this.owner = properties.workerId() != null ? properties.workerId() : defaultOwner();
		int size = properties.maxConcurrent() * RecordingJobKind.values().length;
		this.slots = new Semaphore(size);
		this.executor = Executors.newFixedThreadPool(size,
				Thread.ofPlatform().daemon().name("recording-study-", 0).factory());
	}

	/** 빈 실행 자리만큼 작업을 잡아 executor에 넘긴다. 잡은 작업은 각자 lease를 늘리며 병렬로 돈다. */
	@Scheduled(fixedDelayString = "${astra.recording-study.poll-interval:10s}",
			initialDelayString = "${astra.recording-study.poll-interval:10s}")
	void poll() {
		dispatch();
	}

	/** @return executor에 넘긴 작업 수 */
	int dispatch() {
		int dispatched = 0;
		while (!this.closed && this.slots.tryAcquire()) {
			Optional<Claimed> claimed;
			try {
				claimed = claimNext();
			}
			catch (RuntimeException ex) {
				this.slots.release();
				throw ex;
			}
			if (claimed.isEmpty()) {
				this.slots.release();
				break;
			}
			Claimed job = claimed.get();
			try {
				this.executor.execute(() -> {
					try {
						execute(job);
					}
					finally {
						this.slots.release();
					}
				});
				dispatched++;
			}
			catch (RejectedExecutionException ex) {
				// 종료하는 중이다. 잡은 작업은 lease가 끝나면 recoverExpired가 다시 대기로 돌린다.
				this.slots.release();
				log.info("recording study job not started, worker shutting down jobId={}", job.jobId());
				break;
			}
		}
		return dispatched;
	}

	/** 작업 하나를 잡아 이 thread에서 끝까지 실행한다. 테스트가 순서를 정해 부른다. @return 잡았으면 true */
	boolean runOnce() {
		Optional<Claimed> claimed = claimNext();
		claimed.ifPresent(this::execute);
		return claimed.isPresent();
	}

	private Optional<Claimed> claimNext() {
		AtomicReference<Optional<Claimed>> claimed = new AtomicReference<>(Optional.empty());
		this.lock.runExclusively(CLAIM_LOCK, CLAIM_LOCK_AT_MOST, () -> claimed.set(inTransaction((tx) -> claim())));
		return claimed.get();
	}

	private void execute(Claimed job) {
		Timer.Sample sample = Timer.start(this.meters);
		Outcome outcome = run(job);
		sample.stop(Timer.builder("astra.files.recording_study.jobs")
			.description("녹음 전사·요약 실행 하나의 시간과 결과")
			.tag("kind", job.kind().value())
			.tag("outcome", outcome.outcome())
			.tag("code", MetricTags.code(outcome.code()))
			.register(this.meters));
	}

	@Scheduled(fixedDelayString = "${astra.recording-study.lease-renewal:1m}",
			initialDelayString = "${astra.recording-study.lease-renewal:1m}")
	void recoverExpired() {
		int recovered = inTransaction((tx) -> {
			int count = 0;
			for (Expired row : this.jobs.lockExpired(BATCH)) {
				refund(row.owner(), row.running());
				String outcome;
				if (row.attempts() < this.properties.maxAttempts()) {
					this.jobs.requeue(row.jobId(), RecordingStudyFailures.INTERRUPTED,
							this.properties.backoff(row.attempts()));
					outcome = "retry";
				}
				else {
					this.jobs.finish(row.jobId(), "failed", RecordingStudyFailures.INTERRUPTED, null, null);
					if (row.kind() == RecordingJobKind.TRANSCRIPTION) {
						this.jobs.failQueuedSummary(row.recordingId(), RecordingStudyFailures.TRANSCRIPT_FAILED);
					}
					outcome = "failed";
				}
				Counter.builder("astra.files.recording_study.interrupted")
					.description("lease가 끝나 회수한 녹음 전사·요약 실행")
					.tag("kind", row.kind().value())
					.tag("outcome", outcome)
					.register(this.meters)
					.increment();
				count++;
			}
			return count;
		});
		if (recovered > 0) {
			log.warn("recording study jobs interrupted by expired lease count={}", recovered);
		}
	}

	/**
	 * 새 작업을 잡지 않고, 실행 중인 작업을 interrupt한다. 끊긴 호출은 일시 오류로 기록되어(다시 대기, 사용량 반환) 다음 worker가
	 * 이어받는다. 기록하지 못하고 멈춘 작업은 lease가 끝나면 회수된다.
	 */
	@Override
	public void close() {
		this.closed = true;
		this.executor.shutdownNow();
		try {
			if (!this.executor.awaitTermination(30, TimeUnit.SECONDS)) {
				log.warn("recording study jobs still running at shutdown; their leases will expire");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		this.leaseRenewer.shutdownNow();
	}

	// ---------- 잡기 ----------

	/**
	 * 정리한 뒤 실행할 작업 하나를 고른다. 종류마다 실행 중 수가 상한 미만이면 그 종류의 가장 오래된 후보를 잠그고, 전사와 요약 가운데
	 * 다음 시도 시각이 이른(같으면 ID가 작은) 것을 실행 중으로 바꾼다. 그래서 요약이 뒤에 들어온 전사 뒤에서 기다리지 않는다.
	 */
	private Optional<Claimed> claim() {
		this.jobs.failRejected();
		if (!this.speech.available()) {
			this.jobs.failQueued(RecordingJobKind.TRANSCRIPTION, RecordingStudyFailures.NOT_CONFIGURED);
		}
		if (!this.model.available()) {
			this.jobs.failQueued(RecordingJobKind.SUMMARY, RecordingStudyFailures.NOT_CONFIGURED);
		}
		this.jobs.failOrphanSummaries();
		boolean transcribing = this.speech.available()
				&& this.jobs.processing(RecordingJobKind.TRANSCRIPTION) < this.properties.maxConcurrent();
		boolean summarizing = this.model.available()
				&& this.jobs.processing(RecordingJobKind.SUMMARY) < this.properties.maxConcurrent();
		Optional<Claimed> summary = summarizing ? this.jobs.lockNextSummary() : Optional.empty();
		for (int i = 0; i < CLAIM_SCAN; i++) {
			Optional<Claimed> transcription = transcribing ? this.jobs.lockNextTranscription() : Optional.empty();
			if (summary.isPresent() && (transcription.isEmpty() || summary.get().before(transcription.get()))) {
				this.jobs.start(summary.get().jobId(), this.owner, this.properties.lease(), null);
				return summary;
			}
			if (transcription.isEmpty()) {
				return Optional.empty();
			}
			Claimed job = transcription.get();
			Admission admission = admitTranscription(job);
			if (admission.refused() == null) {
				return Optional.of(job.reserved(admission.reservation()));
			}
			this.jobs.failLocked(job.jobId(), admission.refused());
			this.jobs.failQueuedSummary(job.recordingId(), RecordingStudyFailures.TRANSCRIPT_FAILED);
			audit(job, admission.refused());
			log.info("recording transcription refused jobId={} recordingId={} code={}", job.jobId(), job.recordingId(),
					admission.refused());
		}
		return Optional.empty();
	}

	/**
	 * 선언 길이로 상한과 사용량을 확인하고, 받으면 사용량에 잡아 두고 실행 중으로 바꾼다. 선언 길이는 client 값이라 끝난 뒤 실제 길이로
	 * 다시 판정한다({@link #transcribed}).
	 *
	 * @return 받지 않으면 그 코드
	 */
	private Admission admitTranscription(Claimed job) {
		long seconds = (long) Math.ceil(job.durationMs() / 1000.0);
		if (seconds > this.properties.maxRecordingMinutes() * 60L) {
			return Admission.refused(RecordingStudyFailures.TOO_LONG);
		}
		Usage usage = this.jobs.lockUsage(job.owner());
		if (usage.today() + seconds > this.properties.dailyMinutes() * 60L) {
			return Admission.refused(RecordingStudyFailures.DAILY_LIMIT);
		}
		if (usage.month() + seconds > this.properties.monthlyMinutes() * 60L) {
			return Admission.refused(RecordingStudyFailures.MONTHLY_LIMIT);
		}
		this.jobs.addToday(job.owner(), seconds);
		this.jobs.start(job.jobId(), this.owner, this.properties.lease(), (int) seconds);
		return new Admission(null, new Running((int) seconds, this.jobs.today()));
	}

	/**
	 * 결과를 버린 전사(기록할 행이 없었다)의 사용량을 실제 길이로 고친다. 제공자는 이미 처리했다. 녹음을 지워 행이 없어졌으면 잡아 둔
	 * 값이 그대로 남아 있으므로 차이만, lease가 끝나 회수됐으면 회수가 잡아 둔 값을 돌려줬으므로 실제 길이 전부를 더한다. 그래서 처리 중에
	 * 지워도 짧게 선언한 녹음의 실제 길이가 청구된다.
	 */
	private Outcome chargeDiscarded(Claimed job, int actual) {
		Running reservation = job.reservation();
		if (reservation == null || reservation.reservedSeconds() == null) {
			return Outcome.DISCARDED;
		}
		try {
			inTransaction((tx) -> {
				long delta = this.jobs.exists(job.jobId()) ? actual : (long) actual - reservation.reservedSeconds();
				this.jobs.adjust(job.owner(), reservation.reservedOn(), delta);
				return null;
			});
		}
		catch (RuntimeException ex) {
			log.error("failed to charge discarded transcription jobId={} actualSec={}", job.jobId(), actual, ex);
		}
		return Outcome.DISCARDED;
	}

	// ---------- 실행 ----------

	private Outcome run(Claimed job) {
		long renewal = this.properties.leaseRenewal().toMillis();
		ScheduledFuture<?> renewer = this.leaseRenewer.scheduleAtFixedRate(() -> {
			try {
				this.jobs.renewLease(job.jobId(), this.owner, this.properties.lease());
			}
			catch (RuntimeException ex) {
				log.warn("recording study lease renewal failed jobId={}", job.jobId(), ex);
			}
		}, renewal, renewal, TimeUnit.MILLISECONDS);
		try {
			return job.kind() == RecordingJobKind.TRANSCRIPTION ? transcribe(job) : summarize(job);
		}
		catch (SpeechToTextException ex) {
			String code = switch (ex.code()) {
				case INVALID_AUDIO -> RecordingStudyFailures.UNREADABLE_AUDIO;
				case CONFIG -> RecordingStudyFailures.STT_CONFIG;
				default -> RecordingStudyFailures.STT_UNAVAILABLE;
			};
			log.warn("recording transcription failed jobId={} attempt={} code={} provider={}", job.jobId(),
					job.attempt(), code, ex.code());
			return failed(job, code, ex.retryable());
		}
		catch (TextModelException ex) {
			String code = switch (ex.kind()) {
				case TRUNCATED -> RecordingStudyFailures.OUTPUT_TRUNCATED;
				case CONFIG -> RecordingStudyFailures.MODEL_CONFIG;
				case UNAVAILABLE -> RecordingStudyFailures.MODEL_UNAVAILABLE;
			};
			log.warn("recording summary failed jobId={} attempt={} code={}", job.jobId(), job.attempt(), code);
			return failed(job, code, ex.retryable());
		}
		catch (RecordingSummarizer.InvalidOutput ex) {
			log.warn("recording summary output rejected jobId={} attempt={} reason={}", job.jobId(), job.attempt(),
					ex.getMessage());
			return failed(job, RecordingStudyFailures.INVALID_OUTPUT, true);
		}
		catch (RuntimeException ex) {
			log.error("recording study job failed jobId={} kind={} error={}", job.jobId(), job.kind().value(),
					ex.getClass().getName());
			return failed(job, RecordingStudyFailures.INTERNAL, true);
		}
		finally {
			renewer.cancel(false);
		}
	}

	private Outcome transcribe(Claimed job) {
		SpeechToText.Audio audio = new SpeechToText.Audio(job.mimeType(), job.sizeBytes(),
				() -> this.store.open(Bucket.CONTENT, job.storageKey())
					.orElseThrow(() -> new IOException("원본 객체가 없습니다.")));
		Transcript transcript = this.speech.transcribe(audio);
		int actual = RecordingTranscripts.actualSeconds(transcript.durationSeconds(), job.durationMs(),
				transcript.utterances());
		double clampTo = transcript.durationSeconds() != null ? transcript.durationSeconds() : actual;
		Transcription result = RecordingTranscripts.build(transcript.utterances(), clampTo);
		String resultJson = this.json.writeValueAsString(result);
		String code = transcribed(job, actual);
		Outcome outcome = Boolean.TRUE.equals(inTransaction((tx) -> {
			Optional<Running> running = this.jobs.lockRunning(job.jobId(), this.owner);
			if (running.isEmpty()) {
				return false;
			}
			// 제공자가 처리한 길이를 청구한다. 결과를 내주지 않을 때도 같다.
			adjust(job.owner(), running.get(), actual);
			if (code == null) {
				this.jobs.finish(job.jobId(), "ready", null, resultJson, actual);
			}
			else {
				this.jobs.finish(job.jobId(), "failed", code, null, actual);
				this.jobs.failQueuedSummary(job.recordingId(), RecordingStudyFailures.TRANSCRIPT_FAILED);
			}
			audit(job, code == null ? AuditLog.OK : code);
			return true;
		})) ? (code == null ? Outcome.READY : new Outcome("failed", code)) : chargeDiscarded(job, actual);
		log.info("recording transcription finished jobId={} recordingId={} outcome={} code={} declaredMs={} actualSec={} segments={}",
				job.jobId(), job.recordingId(), outcome.outcome(), outcome.code(), job.durationMs(), actual,
				result.segments().size());
		return outcome;
	}

	/**
	 * 실제 길이로 다시 판정한다. 선언 길이는 client 값이다.
	 *
	 * @return 결과를 내주지 않을 코드. 내주면 null
	 */
	private String transcribed(Claimed job, int actualSeconds) {
		if (actualSeconds > this.properties.maxRecordingMinutes() * 60L) {
			return RecordingStudyFailures.TOO_LONG;
		}
		if (RecordingTranscripts.understated(actualSeconds, job.durationMs())) {
			return RecordingStudyFailures.LENGTH_MISMATCH;
		}
		return null;
	}

	private Outcome summarize(Claimed job) {
		Optional<TranscriptRow> row = this.jobs.transcriptOf(job.recordingId());
		if (row.isEmpty()) {
			// 그 사이 녹음을 지웠다. 기록은 0행으로 버려진다.
			return finishSummary(job, null, RecordingStudyFailures.TRANSCRIPT_FAILED);
		}
		Transcription transcription = this.json.readValue(row.get().resultJson(), Transcription.class);
		if (transcription.segments().isEmpty()) {
			return finishSummary(job, null, RecordingStudyFailures.NO_SPEECH);
		}
		double duration = row.get().audioSeconds() != null ? row.get().audioSeconds()
				: transcription.segments().getLast().at();
		RecordingSummarizer summarizer = new RecordingSummarizer(this.model, this.json,
				this.properties.summaryChunkChars());
		Summary summary = summarizer.summarize(transcription.segments(), duration);
		return finishSummary(job, this.json.writeValueAsString(summary), null);
	}

	private Outcome finishSummary(Claimed job, String resultJson, String code) {
		boolean committed = Boolean.TRUE.equals(inTransaction((tx) -> {
			if (this.jobs.lockRunning(job.jobId(), this.owner).isEmpty()) {
				return false;
			}
			this.jobs.finish(job.jobId(), code == null ? "ready" : "failed", code, resultJson, null);
			audit(job, code == null ? AuditLog.OK : code);
			return true;
		}));
		log.info("recording summary finished jobId={} recordingId={} committed={} code={}", job.jobId(),
				job.recordingId(), committed, code);
		return !committed ? Outcome.DISCARDED : code == null ? Outcome.READY : new Outcome("failed", code);
	}

	private Outcome failed(Claimed job, String code, boolean retryable) {
		boolean retry = retryable && job.attempt() < this.properties.maxAttempts();
		try {
			boolean committed = Boolean.TRUE.equals(inTransaction((tx) -> {
				Optional<Running> running = this.jobs.lockRunning(job.jobId(), this.owner);
				if (running.isEmpty()) {
					return false;
				}
				refund(job.owner(), running.get());
				if (retry) {
					this.jobs.requeue(job.jobId(), code, this.properties.backoff(job.attempt()));
					return true;
				}
				this.jobs.finish(job.jobId(), "failed", code, null, null);
				if (job.kind() == RecordingJobKind.TRANSCRIPTION) {
					this.jobs.failQueuedSummary(job.recordingId(), RecordingStudyFailures.TRANSCRIPT_FAILED);
				}
				audit(job, code);
				return true;
			}));
			if (!committed) {
				return new Outcome("discarded", code);
			}
		}
		catch (RuntimeException ex) {
			// 기록하지 못했다. lease가 끝나면 recoverExpired가 이어받는다.
			log.error("failed to record recording study failure jobId={} code={}", job.jobId(), code, ex);
		}
		return new Outcome(retry ? "retry" : "failed", code);
	}

	// ---------- 사용량 ----------

	private void refund(long owner, Running running) {
		if (running.reservedSeconds() != null) {
			this.jobs.adjust(owner, running.reservedOn(), -running.reservedSeconds());
		}
	}

	private void adjust(long owner, Running running, int actualSeconds) {
		if (running.reservedSeconds() != null) {
			this.jobs.adjust(owner, running.reservedOn(), (long) actualSeconds - running.reservedSeconds());
		}
	}

	private void audit(Claimed job, String result) {
		this.audit.record(null, null, "study_room.recording." + job.kind().value(), "study_room_recording",
				job.recordingId(), job.courseId(), result);
	}

	// 교착, 잠금 대기 초과처럼 다시 하면 될 수 있는 실패만 다시 한다. transaction은 실패할 때마다 통째로 되돌아간다.
	private <T> T inTransaction(TransactionCallback<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute(work);
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= ATTEMPTS) {
					throw ex;
				}
				log.info("recording study transaction retried attempt={} error={}", attempt,
						ex.getClass().getSimpleName());
			}
		}
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

	/** 전사를 받을지. @param refused 받지 않으면 그 코드 @param reservation 받으면 잡아 둔 초와 날짜 */
	private record Admission(String refused, Running reservation) {

		static Admission refused(String code) {
			return new Admission(code, null);
		}

	}

	/** 실행 하나의 결과. code는 원장의 failure_code다. */
	private record Outcome(String outcome, String code) {

		static final Outcome READY = new Outcome("ready", null);

		static final Outcome DISCARDED = new Outcome("discarded", null);

	}

}
