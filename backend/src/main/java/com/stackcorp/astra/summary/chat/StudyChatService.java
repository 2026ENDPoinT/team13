package com.stackcorp.astra.summary.chat;

import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.summary.job.Publication;
import com.stackcorp.astra.summary.job.SummaryJobService;
import com.stackcorp.astra.summary.policy.ConsentRevoked;
import com.stackcorp.astra.summary.policy.LlmGate;
import com.stackcorp.astra.summary.policy.ProcessingScope;
import com.stackcorp.astra.summary.policy.ReadCommitted;
import com.stackcorp.astra.summary.policy.UsageLedger;

/**
 * 학습 대화의 명령과 조회(ADR-0032). 파일 권한과 대화 소유 확인은 이 서비스를 부르는 web 계층이 요청마다 한다.
 * <ul>
 * <li>대화는 게시한 요약이 있는 파일에서만 열고 질문한다. 게시한 교수자가 그 과목에 학습 대화 동의를 했어야 한다.</li>
 * <li>질문은 대화마다 하나씩만 답을 기다린다. DB 유일 제약이 지킨다.</li>
 * <li>질문 본문은 transaction 안에서 저장소에 쓴다. commit이 실패하면 질문 객체만 남고, 대화를 지울 때 함께 지워진다.</li>
 * <li>대화 삭제는 삭제 표시와 대기 질문 취소를 먼저 commit하고 본문 객체와 행을 지운다. 본문을 지우지 못하면 worker가 다시
 * 지운다.</li>
 * </ul>
 */
@Service
public class StudyChatService {

	/** 목록 한 쪽의 크기. */
	public static final int CHAT_PAGE = 20;

	/** 질문 목록 한 쪽의 크기. */
	public static final int EXCHANGE_PAGE = 10;

	private static final Logger log = LoggerFactory.getLogger(StudyChatService.class);

	private final StudyChatRepository chats;

	private final StudyChatStorage storage;

	private final SummaryJobService summaries;

	private final LlmGate gate;

	private final UsageLedger usage;

	private final StudyChatProperties properties;

	private final ReadCommitted transaction;

	StudyChatService(StudyChatRepository chats, StudyChatStorage storage, SummaryJobService summaries, LlmGate gate,
			UsageLedger usage, StudyChatProperties properties, PlatformTransactionManager transactionManager) {
		this.chats = chats;
		this.storage = storage;
		this.summaries = summaries;
		this.gate = gate;
		this.usage = usage;
		this.properties = properties;
		this.transaction = new ReadCommitted(transactionManager);
	}

	/**
	 * 이 파일로 학습 대화를 할 수 있는지 확인한다.
	 * @throws ApiException 503 {@code llm_disabled}, 409 {@code summary_not_published}, 409
	 * {@code llm_consent_required}
	 */
	public void requireAvailable(long courseId, long fileId) {
		this.gate.requireEnabled();
		Publication publication = this.summaries.publication(fileId)
			.orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "summary_not_published",
					"게시한 요약이 있는 자료에서만 학습 대화를 할 수 있습니다."));
		this.gate.require(courseId, publication.publishedByUserId(), ProcessingScope.STUDY_CHAT);
	}

	/**
	 * @throws ApiException 429 {@code study_chat_budget_exhausted} 과목의 오늘 token 예산을 다 썼을 때
	 */
	public void requireBudget(long courseId) {
		if (this.usage.todayTokens(courseId, ProcessingScope.STUDY_CHAT) >= this.properties.dailyTokenBudget()) {
			throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "study_chat_budget_exhausted",
					"이 과목의 오늘 학습 대화 사용량을 모두 썼습니다. 내일 다시 질문해 주세요.");
		}
	}

	public StudyChat open(long ownerUserId, long courseId, long fileId) {
		long id = this.chats.insertChat(ownerUserId, courseId, fileId);
		return this.chats.findChat(id).orElseThrow();
	}

	/** 지운 대화는 없는 것으로 본다. */
	public Optional<StudyChat> find(long chatId) {
		return this.chats.findChat(chatId).filter((c) -> !c.deleted());
	}

	/** 소유자의 그 파일 대화. 최신이 먼저이고 한 쪽은 {@value #CHAT_PAGE}개다. */
	public List<StudyChat> list(long ownerUserId, long fileId, Long before) {
		return this.chats.listChats(ownerUserId, fileId, before, CHAT_PAGE);
	}

	/**
	 * 질문을 받는다. 부르는 쪽의 transaction(멱등 원장) 안에서 부른다. 답은 worker가 쓴다.
	 * @throws ApiException 404 대화가 지워졌을 때, 409 {@code study_chat_busy} 앞 질문의 답을 기다릴 때
	 */
	@Transactional
	public ExchangeDetail ask(StudyChat chat, String question) {
		if (!this.chats.touchChat(chat.id())) {
			throw ApiException.notFound();
		}
		long id;
		try {
			id = this.chats.insertExchange(chat.id());
		}
		catch (DuplicateKeyException ex) {
			throw new ApiException(HttpStatus.CONFLICT, "study_chat_busy", "앞 질문의 답을 기다리고 있습니다. 답을 받은 뒤 다시 질문해 주세요.");
		}
		this.storage.putQuestion(chat.fileId(), chat.id(), id, question);
		return new ExchangeDetail(this.chats.findExchange(id).orElseThrow(), question, null);
	}

	/** 대화의 질문과 답. 최신이 먼저이고 한 쪽은 {@value #EXCHANGE_PAGE}개다. */
	public List<ExchangeDetail> exchanges(StudyChat chat, Long before) {
		return this.chats.listExchanges(chat.id(), before, EXCHANGE_PAGE).stream().map((e) -> detail(chat, e)).toList();
	}

	public Optional<ExchangeDetail> exchange(StudyChat chat, long exchangeId) {
		return this.chats.findExchange(exchangeId).filter((e) -> e.chatId() == chat.id()).map((e) -> detail(chat, e));
	}

	/** 대화를 지운다. 답을 기다리던 질문은 취소하고 본문 객체와 행을 지운다. */
	public void delete(StudyChat chat) {
		this.transaction.execute(() -> this.chats.markDeleted(chat.id()));
		purge(chat);
	}

	/** 학습 대화 동의를 철회하면 그 교수자가 게시한 파일의 대화에서 대기 중인 질문을 취소한다. */
	@EventListener
	public void onConsentRevoked(ConsentRevoked event) {
		if (event.scope() == ProcessingScope.STUDY_CHAT) {
			this.chats.cancelQueued(this.chats.queuedForPublisher(event.courseId(), event.instructorUserId()),
					LlmGate.CONSENT);
		}
	}

	/**
	 * 지운 것으로 표시한 대화의 본문과 행을 지운다. 본문 객체를 먼저 지워 실패하면 행이 남고 다음 정리에서 다시 지운다.
	 * @return 다 지웠으면 true
	 */
	boolean purge(StudyChat chat) {
		try {
			this.storage.deleteChat(chat.fileId(), chat.id());
		}
		catch (RuntimeException ex) {
			log.warn("study chat body purge failed chatId={} error={}", chat.id(), ex.getClass().getName());
			return false;
		}
		this.transaction.execute(() -> {
			this.chats.deleteRows(chat.id());
			return null;
		});
		return true;
	}

	private ExchangeDetail detail(StudyChat chat, Exchange exchange) {
		String question = this.storage.question(chat.fileId(), chat.id(), exchange.id()).orElse(null);
		Answer answer = exchange.state() == ExchangeState.ANSWERED
				? this.storage.answer(chat.fileId(), chat.id(), exchange.id()).orElse(null) : null;
		return new ExchangeDetail(exchange, question, answer);
	}

}
