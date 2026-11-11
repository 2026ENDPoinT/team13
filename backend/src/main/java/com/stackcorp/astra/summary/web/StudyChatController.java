package com.stackcorp.astra.summary.web;

import java.time.Instant;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;
import com.stackcorp.astra.summary.chat.Citation;
import com.stackcorp.astra.summary.chat.ExchangeDetail;
import com.stackcorp.astra.summary.chat.Slice;
import com.stackcorp.astra.summary.chat.StudyChat;
import com.stackcorp.astra.summary.chat.StudyChatProperties;
import com.stackcorp.astra.summary.chat.StudyChatService;

/**
 * 자료 하나에 근거한 학습 대화(ADR-0032). 원본 ASTRA Supporters(EP-0298~EP-0304)의 대화 생성·목록·보기·삭제를 파일 단위로
 * 바꾼 것이다. Socket.IO 대신 {@code /api} 요청과 조회로 주고받는다.
 * <ul>
 * <li>대화는 연 사람만 읽고 지운다. 같은 과목의 학생, 교수자, 다른 과목 경로에는 모두 404다(SC-02).</li>
 * <li>검색 범위는 경로의 파일 하나이고 요청마다 그 파일의 열람 권한을 다시 본다. 요청 본문은 질문 하나만 받는다. 카탈로그, 분류,
 * 쪽 목록 같은 범위 값은 받지 않는다(SEC-16).</li>
 * <li>질문은 {@code Idempotency-Key}가 필수다. 연결이 끊겨도 같은 key로 다시 보내면 같은 질문을 돌려받고, 질문 목록이나 질문
 * 조회로 답의 최종 상태를 받는다(SEC-07).</li>
 * </ul>
 */
@RestController
class StudyChatController {

	private final StudyChatService chats;

	private final SummaryPolicy policy;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final StudyChatProperties properties;

	StudyChatController(StudyChatService chats, SummaryPolicy policy, RateLimiter rateLimiter, Idempotency idempotency,
			AuditLog audit, StudyChatProperties properties) {
		this.chats = chats;
		this.policy = policy;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
		this.properties = properties;
	}

	/** EP-0300. 대화를 연다. {@code Idempotency-Key}를 보내면 재전송이 대화를 더 만들지 않는다. */
	@PostMapping("/api/courses/{courseId}/files/{fileId}/study-chats")
	ResponseEntity<ChatView> open(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		requireRead(actor, courseId, fileId);
		this.chats.requireAvailable(courseId, fileId);
		ChatView chat = this.idempotency.execute(actor.userId(), "study_chat.open", idempotencyKey,
				List.of(courseId, fileId), ChatView.class, () -> {
					ChatView created = ChatView.of(this.chats.open(actor.userId(), courseId, fileId));
					this.audit.success(actor.userId(), actor.sessionId(), "study_chat.open", "study_chat", created.id(),
							courseId);
					return created;
				});
		return noStore(HttpStatus.CREATED).body(chat);
	}

	/**
	 * EP-0301. 그 파일에서 내가 연 대화. 최신이 먼저이고 {@code before}보다 작은 ID부터 20개다. 다음 쪽이 있으면 {@code nextBefore}가
	 * 그 값이고, 마지막 쪽이면 null이다.
	 */
	@GetMapping("/api/courses/{courseId}/files/{fileId}/study-chats")
	ResponseEntity<ChatPage> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long fileId, @RequestParam(required = false) Long before) {
		requireRead(actor, courseId, fileId);
		Slice<StudyChat> page = this.chats.list(actor.userId(), fileId, before);
		List<ChatView> items = page.items().stream().map(ChatView::of).toList();
		return noStore(HttpStatus.OK).body(new ChatPage(items, page.more() ? items.getLast().id() : null));
	}

	@GetMapping("/api/courses/{courseId}/study-chats/{chatId}")
	ResponseEntity<ChatView> get(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long chatId) {
		return noStore(HttpStatus.OK).body(ChatView.of(readable(actor, courseId, chatId)));
	}

	/**
	 * EP-0299. 대화를 지운다. 답을 기다리던 질문은 취소하고 질문과 답 본문을 지운다. 파일 열람 권한을 잃은 뒤에도 자기 대화는 지울 수
	 * 있다.
	 */
	@DeleteMapping("/api/courses/{courseId}/study-chats/{chatId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long chatId) {
		StudyChat chat = owned(actor, courseId, chatId);
		this.chats.delete(chat);
		this.audit.success(actor.userId(), actor.sessionId(), "study_chat.delete", "study_chat", chatId, courseId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/**
	 * 질문한다. 202로 대기 중인 질문의 ID와 상태를 돌려주고 답은 worker가 쓴다. 응답은 멱등 원장에 receipt로 남으므로 질문 본문을
	 * 담지 않는다. 대화마다 답을 기다리는 질문은 하나다(409
	 * {@code study_chat_busy}). 사용자별 횟수 제한(429)과 과목별 일일 token 예산(429 {@code study_chat_budget_exhausted})이
	 * 있다.
	 */
	@PostMapping("/api/courses/{courseId}/study-chats/{chatId}/exchanges")
	ResponseEntity<ExchangeView> ask(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long chatId, @RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey,
			@RequestBody AskRequest body) {
		StudyChat chat = readable(actor, courseId, chatId);
		String question = validated(body);
		if (idempotencyKey == null) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "idempotency_key_required", "Idempotency-Key header가 필요합니다.");
		}
		this.chats.requireAvailable(courseId, chat.fileId());
		this.rateLimiter.check("study-chat-ask", actor.userId(), this.properties.rateLimit(),
				this.properties.rateWindow());
		this.chats.requireBudget(courseId);
		ExchangeView exchange = this.idempotency.execute(actor.userId(), "study_chat.ask", idempotencyKey,
				List.of(courseId, chatId, question), ExchangeView.class, () -> {
					ExchangeView created = ExchangeView.receipt(this.chats.ask(chat, question));
					this.audit.success(actor.userId(), actor.sessionId(), "study_chat.ask", "study_chat_exchange",
							created.id(), courseId);
					return created;
				});
		return noStore(HttpStatus.ACCEPTED).body(exchange);
	}

	/** EP-0304. 질문과 답. 최신이 먼저이고 {@code before}보다 작은 ID부터 10개다. 다음 쪽은 대화 목록과 같은 {@code nextBefore}다. */
	@GetMapping("/api/courses/{courseId}/study-chats/{chatId}/exchanges")
	ResponseEntity<ExchangePage> exchanges(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long chatId, @RequestParam(required = false) Long before) {
		StudyChat chat = readable(actor, courseId, chatId);
		Slice<ExchangeDetail> page = this.chats.exchanges(chat, before);
		List<ExchangeView> items = page.items().stream().map(ExchangeView::of).toList();
		return noStore(HttpStatus.OK).body(new ExchangePage(items, page.more() ? items.getLast().id() : null));
	}

	/** 질문 하나의 상태. 답을 기다리는 동안 client가 이 경로를 다시 읽는다. */
	@GetMapping("/api/courses/{courseId}/study-chats/{chatId}/exchanges/{exchangeId}")
	ResponseEntity<ExchangeView> exchange(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long chatId, @PathVariable long exchangeId) {
		StudyChat chat = readable(actor, courseId, chatId);
		return noStore(HttpStatus.OK).body(this.chats.exchange(chat, exchangeId)
			.map(ExchangeView::of)
			.orElseThrow(ApiException::notFound));
	}

	private void requireRead(Actor actor, long courseId, long fileId) {
		if (!this.policy.canRead(actor, courseId, fileId)) {
			throw ApiException.notFound();
		}
	}

	// 소유자이고 경로의 과목이 대화의 과목과 같아야 한다.
	private StudyChat owned(Actor actor, long courseId, long chatId) {
		return this.chats.find(chatId)
			.filter((c) -> c.ownerUserId() == actor.userId() && c.courseId() == courseId)
			.orElseThrow(ApiException::notFound);
	}

	// 읽기와 질문은 소유에 더해 지금 그 파일을 볼 수 있어야 한다(CHG-06: 권한을 잃으면 저장한 대화도 못 본다).
	private StudyChat readable(Actor actor, long courseId, long chatId) {
		StudyChat chat = owned(actor, courseId, chatId);
		requireRead(actor, courseId, chat.fileId());
		return chat;
	}

	private String validated(AskRequest body) {
		if (body == null || body.question() == null || body.question().isBlank()) {
			throw ApiException.invalid("question 값이 필요합니다.");
		}
		String question = body.question().strip();
		if (question.length() > this.properties.questionMaxChars()) {
			throw ApiException.invalid("질문은 " + this.properties.questionMaxChars() + "자까지 쓸 수 있습니다.");
		}
		if (question.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) && cp != '\n' && cp != '\r' && cp != '\t')) {
			throw ApiException.invalid("질문에 제어 문자를 쓸 수 없습니다.");
		}
		return question;
	}

	private static ResponseEntity.BodyBuilder noStore(HttpStatus status) {
		return ResponseEntity.status(status).cacheControl(CacheControl.noStore());
	}

	/** 요청 본문. 질문 말고는 받지 않는다. 다른 필드는 400이다. */
	record AskRequest(String question) {
	}

	record ChatView(long id, long fileId, int exchangeCount, Instant createdAt, Instant lastActivityAt) {

		static ChatView of(StudyChat chat) {
			return new ChatView(chat.id(), chat.fileId(), chat.exchangeCount(), chat.createdAt(), chat.lastActivityAt());
		}

	}

	/**
	 * @param answer 답한 질문에만 있는 Markdown. raw HTML, 이미지, 링크가 없다
	 * @param citations 답이 근거로 밝힌 그 파일의 쪽
	 * @param errorCode 실패·취소 이유. consent|disabled|forbidden|unavailable|not_configured|budget|provider|
	 * invalid_output|interrupted|deleted|internal
	 */
	record ExchangeView(long id, String state, String errorCode, String question, String answer,
			List<Citation> citations, Instant createdAt, Instant finishedAt) {

		// 멱등 원장에 남는 응답. 질문 본문은 DB에 두지 않는다(ADR-0032).
		static ExchangeView receipt(ExchangeDetail detail) {
			var e = detail.exchange();
			return new ExchangeView(e.id(), e.state().name().toLowerCase(), e.errorCode(), null, null, List.of(),
					e.createdAt(), e.finishedAt());
		}

		static ExchangeView of(ExchangeDetail detail) {
			var e = detail.exchange();
			return new ExchangeView(e.id(), e.state().name().toLowerCase(), e.errorCode(), detail.question(),
					detail.answer() != null ? detail.answer().text() : null,
					detail.answer() != null ? detail.answer().citations() : List.of(), e.createdAt(), e.finishedAt());
		}

	}

	/** @param nextBefore 다음 쪽을 읽을 때 before로 보낼 값. 마지막 쪽이면 null */
	record ChatPage(List<ChatView> items, Long nextBefore) {
	}

	/** @param nextBefore 다음 쪽을 읽을 때 before로 보낼 값. 마지막 쪽이면 null */
	record ExchangePage(List<ExchangeView> items, Long nextBefore) {
	}

}
