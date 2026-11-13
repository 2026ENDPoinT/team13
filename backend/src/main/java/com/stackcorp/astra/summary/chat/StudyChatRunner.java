package com.stackcorp.astra.summary.chat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;

import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.DocumentRenditions.Rendition;
import com.stackcorp.astra.files.api.FileAccess;
import com.stackcorp.astra.files.api.FileAction;
import com.stackcorp.astra.files.api.RenditionException;
import com.stackcorp.astra.files.api.StoredFiles;
import com.stackcorp.astra.shared.metrics.MetricTags;
import com.stackcorp.astra.shared.schedule.SchedulerLock;
import com.stackcorp.astra.summary.api.LlmClient;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.chat.StudyAgent.Tally;
import com.stackcorp.astra.summary.job.PublishedSummary;
import com.stackcorp.astra.summary.job.SummaryJobService;
import com.stackcorp.astra.summary.pipeline.TextRedactor;
import com.stackcorp.astra.summary.policy.LlmGate;
import com.stackcorp.astra.summary.policy.MeteredLlmClient;
import com.stackcorp.astra.summary.policy.ProcessingScope;
import com.stackcorp.astra.summary.policy.ReadCommitted;
import com.stackcorp.astra.summary.policy.UsageLedger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * worker 프로필에서 답을 기다리는 질문을 하나씩 처리한다. 여러 worker가 떠도 SKIP LOCKED로 한 곳만 잡는다(ADR-0032).
 * <p>
 * 모델을 부르기 전마다 lease를 갱신하고 다음을 다시 확인한다. 하나라도 어긋나면 모델을 더 부르지 않고 멈춘다.
 * <ul>
 * <li>대화 소유자가 지금도 그 파일을 볼 수 있다(권한을 잃으면 {@code forbidden}).</li>
 * <li>그 파일에 게시본이 있다({@code unavailable}).</li>
 * <li>게시한 교수자의 학습 대화 동의가 유효하고 배포 스위치가 켜져 있다({@code consent}, {@code disabled}).</li>
 * </ul>
 * 답은 검사를 통과한 것만 저장한다. 끝난 질문의 사용량은 같은 transaction에서 과목 합계에 더한다. 로그에는 ID와 코드만 남긴다.
 * <p>
 * 끝낸 질문을 상태와 code별로 세고(astra.studychat.exchanges.finished), 모델 호출은 요약 작업과 같은 {@link MeteredLlmClient}로
 * {@code kind=study_chat}을 붙여 잰다. 대기열 길이와 나이는 {@link StudyChatBacklog}가 낸다.
 * <p>
 * 대화 정리는 {@link SchedulerLock}으로 묶는다(ADR-0034). 정리 후보를 잠그지 않고 읽고 대화마다 저장소 prefix를 지우므로, 두 worker가
 * 함께 돌면 같은 대화의 객체 목록 조회와 삭제를 두 번 한다. lease 만료 처리는 조건부 UPDATE 한 문장이라 묶지 않는다.
 */
@Component
@Profile("worker")
class StudyChatRunner implements SmartLifecycle {

	private static final Logger log = LoggerFactory.getLogger(StudyChatRunner.class);

	private static final int PURGE_BATCH = 100;

	private static final String PURGE_LOCK = "summary.studychat_purge";

	private static final Duration PURGE_LOCK_AT_MOST = Duration.ofMinutes(10);

	private final StudyChatRepository chats;

	private final StudyChatStorage storage;

	private final StudyChatService service;

	private final SummaryJobService summaries;

	private final DocumentRenditions renditions;

	private final StoredFiles files;

	private final FileAccess access;

	private final LlmGate gate;

	private final UsageLedger usage;

	private final TextRedactor redactor;

	private final ObjectProvider<LlmClient> llm;

	private final StudyChatProperties properties;

	private final ReadCommitted transaction;

	private final String owner;

	private volatile boolean running;

	private final SchedulerLock lock;

	private final MeterRegistry meters;

	StudyChatRunner(StudyChatRepository chats, StudyChatStorage storage, StudyChatService service,
			SummaryJobService summaries, DocumentRenditions renditions, StoredFiles files, FileAccess access,
			LlmGate gate, UsageLedger usage, TextRedactor redactor, ObjectProvider<LlmClient> llm,
			StudyChatProperties properties, PlatformTransactionManager transactionManager, SchedulerLock lock,
			MeterRegistry meters) {
		this.chats = chats;
		this.storage = storage;
		this.service = service;
		this.summaries = summaries;
		this.renditions = renditions;
		this.files = files;
		this.access = access;
		this.gate = gate;
		this.usage = usage;
		this.redactor = redactor;
		this.llm = llm;
		this.properties = properties;
		this.transaction = new ReadCommitted(transactionManager);
		this.owner = properties.workerId() != null ? properties.workerId() : defaultOwner();
		this.lock = lock;
		this.meters = meters;
	}

	@Scheduled(fixedDelayString = "${astra.studychat.poll-interval:1s}",
			initialDelayString = "${astra.studychat.poll-interval:1s}")
	void poll() {
		while (runOnce()) {
			// 대기 질문이 없을 때까지 이어서 처리한다.
		}
	}

	@Scheduled(fixedDelayString = "${astra.studychat.sweep-interval:30s}",
			initialDelayString = "${astra.studychat.sweep-interval:30s}")
	void sweep() {
		int interrupted = failExpired();
		if (interrupted > 0) {
			log.warn("study chat exchanges interrupted by expired lease count={}", interrupted);
		}
		this.lock.runExclusively(PURGE_LOCK, PURGE_LOCK_AT_MOST, () -> {
			int purged = purge();
			if (purged > 0) {
				log.info("study chats purged count={}", purged);
			}
		});
	}

	/** @return 질문 하나를 처리했으면 true */
	boolean runOnce() {
		if (!this.running) {
			return false;
		}
		Optional<Exchange> claimed = this.transaction
			.execute(() -> this.chats.claimNext(this.owner, this.properties.lease()));
		if (claimed.isEmpty()) {
			return false;
		}
		Exchange exchange = claimed.get();
		StudyChat chat = this.chats.findChat(exchange.chatId()).orElseThrow();
		try {
			execute(exchange, chat);
		}
		catch (RuntimeException ex) {
			log.error("study chat exchange failed exchangeId={} error={}", exchange.id(), ex.getClass().getName(), ex);
			finish(exchange, chat, ExchangeState.FAILED, "internal", new Tally(0, 0, 0, 0));
		}
		return true;
	}

	/** lease가 끝난 실행 중 질문을 닫는다. */
	int failExpired() {
		int closed = this.transaction.execute(this.chats::failExpired);
		finished(ExchangeState.FAILED, "interrupted", closed);
		return closed;
	}

	/** 지운 대화와 보존기한이 지난 대화를 정리한다. @return 다 지운 대화 수 */
	int purge() {
		int purged = 0;
		for (StudyChat chat : this.chats.purgeCandidates(this.properties.retention(), PURGE_BATCH)) {
			if (!chat.deleted()) {
				this.transaction.execute(() -> this.chats.markDeleted(chat.id()));
			}
			if (this.service.purge(chat)) {
				purged++;
			}
		}
		return purged;
	}

	@Override
	public void start() {
		this.running = true;
	}

	@Override
	public void stop() {
		this.running = false;
	}

	@Override
	public boolean isRunning() {
		return this.running;
	}

	private void execute(Exchange exchange, StudyChat chat) {
		if (chat.deleted()) {
			finish(exchange, chat, ExchangeState.CANCELLED, "deleted", new Tally(0, 0, 0, 0));
			return;
		}
		Optional<String> refused = refusal(chat).or(() -> budget(chat));
		LlmClient client = this.llm.getIfAvailable();
		if (refused.isEmpty() && client == null) {
			refused = Optional.of("not_configured");
		}
		if (refused.isPresent()) {
			finish(exchange, chat, stateFor(refused.get()), refused.get(), new Tally(0, 0, 0, 0));
			return;
		}
		Optional<PublishedSummary> published = this.summaries.published(chat.fileId());
		if (published.isEmpty()) {
			finish(exchange, chat, ExchangeState.FAILED, "unavailable", new Tally(0, 0, 0, 0));
			return;
		}
		Rendition rendition;
		try {
			rendition = this.renditions.prepare(chat.fileId());
		}
		catch (RenditionException ex) {
			finish(exchange, chat, ExchangeState.FAILED, "unavailable", new Tally(0, 0, 0, 0));
			return;
		}
		String title = this.files.find(chat.fileId()).map((f) -> stripExtension(f.name())).orElse("");
		SourcePages source = SourcePages.of(title, rendition, published.get().result().pack(), this.redactor);
		String question = this.storage.question(chat.fileId(), chat.id(), exchange.id())
			.orElseThrow(() -> new IllegalStateException("질문 본문이 저장소에 없습니다: " + exchange.id()));
		StudyAgent agent = new StudyAgent(new MeteredLlmClient(client, this.meters, ProcessingScope.STUDY_CHAT), source,
				new StudyAgent.Limits(this.properties.maxToolSteps(), this.properties.maxInputTokensPerAnswer(),
						this.properties.maxOutputTokens(), this.properties.pageMaxChars(),
						this.properties.historyMaxChars()),
				() -> guard(exchange, chat));
		StudyAgent.Outcome outcome;
		try {
			outcome = agent.run(history(chat, exchange), this.redactor.redact(question));
		}
		catch (StudyAgent.Stopped ex) {
			if (!"lost".equals(ex.reason())) {
				finish(exchange, chat, stateFor(ex.reason()), ex.reason(), ex.tally());
			}
			else {
				log.warn("study chat exchange lost lease exchangeId={}", exchange.id());
			}
			return;
		}
		catch (StudyAgent.Failed ex) {
			// 질문에는 오류 코드만 남는다. 제공자 문구(key는 client가 지웠다)는 로그에만 남긴다.
			if (ex.getCause() instanceof LlmException provider) {
				log.warn("study chat provider call failed exchangeId={} kind={} detail={}", exchange.id(),
						provider.code().name().toLowerCase(), provider.getMessage());
			}
			finish(exchange, chat, ExchangeState.FAILED, ex.code(), ex.tally());
			return;
		}
		this.storage.putAnswer(chat.fileId(), chat.id(), exchange.id(), outcome.answer());
		if (!finish(exchange, chat, ExchangeState.ANSWERED, null, outcome.tally())) {
			// 그 사이 대화가 지워졌거나 다른 쪽이 질문을 닫았다. 답한 상태가 아니므로 답 본문을 남기지 않는다.
			this.storage.deleteAnswer(chat.fileId(), chat.id(), exchange.id());
		}
	}

	// 모델 호출 직전마다 부른다.
	private Optional<String> guard(Exchange exchange, StudyChat chat) {
		if (!this.running) {
			return Optional.of("interrupted");
		}
		if (!this.chats.renewLease(exchange.id(), this.owner, this.properties.lease())) {
			return Optional.of("lost");
		}
		return refusal(chat);
	}

	private Optional<String> refusal(StudyChat chat) {
		if (!this.gate.enabled()) {
			return Optional.of(LlmGate.DISABLED);
		}
		if (!this.access.allowed(chat.ownerUserId(), chat.courseId(), chat.fileId(), FileAction.READ)) {
			return Optional.of("forbidden");
		}
		var publication = this.summaries.publication(chat.fileId());
		if (publication.isEmpty()) {
			return Optional.of("unavailable");
		}
		return this.gate.closed(chat.courseId(), publication.get().publishedByUserId(), ProcessingScope.STUDY_CHAT);
	}

	private Optional<String> budget(StudyChat chat) {
		return this.usage.todayTokens(chat.courseId(), ProcessingScope.STUDY_CHAT) >= this.properties
			.dailyTokenBudget() ? Optional.of("budget") : Optional.empty();
	}

	private List<StudyAgent.Turn> history(StudyChat chat, Exchange exchange) {
		List<StudyAgent.Turn> turns = new ArrayList<>();
		for (Exchange previous : this.chats.answeredBefore(chat.id(), exchange.id(),
				this.properties.historyExchanges())) {
			Optional<String> question = this.storage.question(chat.fileId(), chat.id(), previous.id());
			Optional<Answer> answer = this.storage.answer(chat.fileId(), chat.id(), previous.id());
			if (question.isPresent() && answer.isPresent()) {
				turns.add(new StudyAgent.Turn(this.redactor.redact(question.get()), answer.get().text()));
			}
		}
		return turns;
	}

	// 상태 전이와 과목 사용량을 한 transaction으로 반영한다. 반영하지 못했으면(lease 상실, 대화 삭제) 사용량도 더하지 않는다.
	private boolean finish(Exchange exchange, StudyChat chat, ExchangeState state, String errorCode, Tally tally) {
		boolean finished = this.transaction.execute(() -> {
			if (!this.chats.finish(exchange.id(), this.owner, state, errorCode, tally.calls(), tally.toolCalls(),
					tally.inputTokens(), tally.outputTokens())) {
				return false;
			}
			this.usage.add(chat.courseId(), ProcessingScope.STUDY_CHAT, tally.calls(), tally.inputTokens(),
					tally.outputTokens());
			return true;
		});
		if (!finished) {
			log.warn("study chat finish ignored exchangeId={} state={}", exchange.id(), state.value());
		}
		else {
			finished(state, errorCode, 1);
		}
		return finished;
	}

	private void finished(ExchangeState state, String errorCode, int count) {
		if (count > 0) {
			Counter.builder("astra.studychat.exchanges.finished")
				.description("끝난 학습 대화 질문 수")
				.tag("state", state.value())
				.tag("code", MetricTags.code(errorCode))
				.register(this.meters)
				.increment(count);
		}
	}

	private static ExchangeState stateFor(String code) {
		return LlmGate.CONSENT.equals(code) || LlmGate.DISABLED.equals(code) ? ExchangeState.CANCELLED
				: ExchangeState.FAILED;
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
