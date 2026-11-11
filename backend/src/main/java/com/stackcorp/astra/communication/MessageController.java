package com.stackcorp.astra.communication;

import com.stackcorp.astra.communication.DraftService.DraftRequest;
import com.stackcorp.astra.communication.DraftService.Saved;
import com.stackcorp.astra.communication.MessageService.Box;
import com.stackcorp.astra.communication.MessageService.ComposeDraft;
import com.stackcorp.astra.communication.MessageService.ContactPage;
import com.stackcorp.astra.communication.MessageService.DeleteRequest;
import com.stackcorp.astra.communication.MessageService.Deleted;
import com.stackcorp.astra.communication.MessageService.InboxPage;
import com.stackcorp.astra.communication.MessageService.ReadState;
import com.stackcorp.astra.communication.MessageService.RecallResult;
import com.stackcorp.astra.communication.MessageService.ReceivedView;
import com.stackcorp.astra.communication.MessageService.ReplyRequest;
import com.stackcorp.astra.communication.MessageService.SendRequest;
import com.stackcorp.astra.communication.MessageService.SentPage;
import com.stackcorp.astra.communication.MessageService.SentReceipt;
import com.stackcorp.astra.communication.MessageService.SentView;
import com.stackcorp.astra.communication.MessageService.UnreadCount;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 쪽지(EP-0221~EP-0241)와 쪽지쓰기 자동저장(EP-0115~EP-0117)의 대체 계약. 원본의 HTML 조각·폼 경로(EP-0225, EP-0227, EP-0231,
 * EP-0234, EP-0236, EP-0238)는 화면이 이 JSON으로 그리므로 따로 두지 않는다. 보관함의 주인은 session principal이고 경로의 ID가 그
 * 사람의 보관함에 없으면 존재와 관계없이 404다(COMM-13, SEC-08). 권한과 상태 전이는 {@link MessageService}, 초안은
 * {@link DraftService}에 있다.
 */
@RestController
class MessageController {

	private final MessageService messages;

	private final DraftService drafts;

	MessageController(MessageService messages, DraftService drafts) {
		this.messages = messages;
		this.drafts = drafts;
	}

	/** 받는사람 후보(EP-0222, EP-0237, EP-0221). courseId를 주면 그 과목 안에서만, q는 이름에 들어 있는 글자. */
	@GetMapping("/api/messages/recipients")
	ResponseEntity<ContactPage> recipients(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) Long courseId, @RequestParam(required = false) String q,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size) {
		return ok(this.messages.contacts(actor.userId(), courseId, q, page, size));
	}

	/** 보내기(EP-0230). Idempotency-Key 필수. 같은 key로 다시 보내면 처음 receipt를 돌려주고 쪽지를 더 만들지 않는다. 201 */
	@PostMapping("/api/messages")
	ResponseEntity<SentReceipt> send(@AuthenticationPrincipal Actor actor, @RequestBody SendRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.messages.send(actor, idempotencyKey, body));
	}

	/** 답장(EP-0229). 받는사람은 원래 쪽지의 발신자다. Idempotency-Key 필수. 201 */
	@PostMapping("/api/messages/inbox/{messageId}/reply")
	ResponseEntity<SentReceipt> reply(@AuthenticationPrincipal Actor actor, @PathVariable long messageId,
			@RequestBody ReplyRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.messages.reply(actor, messageId, idempotencyKey, body));
	}

	/** 받은 쪽지 전달(EP-0224). Idempotency-Key 필수. 201 */
	@PostMapping("/api/messages/inbox/{messageId}/forward")
	ResponseEntity<SentReceipt> forwardReceived(@AuthenticationPrincipal Actor actor, @PathVariable long messageId,
			@RequestBody SendRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.messages.forward(actor, Box.INBOX, messageId, idempotencyKey, body));
	}

	/** 보낸 쪽지 전달(EP-0232). Idempotency-Key 필수. 201 */
	@PostMapping("/api/messages/sent/{messageId}/forward")
	ResponseEntity<SentReceipt> forwardSent(@AuthenticationPrincipal Actor actor, @PathVariable long messageId,
			@RequestBody SendRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.messages.forward(actor, Box.SENT, messageId, idempotencyKey, body));
	}

	/** 받은함(EP-0226). read=false면 안 읽은 쪽지, true면 읽은 쪽지. q는 제목 검색. 발송 일시 역순, nextCursor로 다음 쪽. */
	@GetMapping("/api/messages/inbox")
	ResponseEntity<InboxPage> inbox(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) Boolean read,
			@RequestParam(required = false) String q, @RequestParam(required = false) String cursor,
			@RequestParam(defaultValue = "20") int size) {
		return ok(this.messages.inbox(actor.userId(), read, q, cursor, size));
	}

	/** 받은 쪽지 상세(EP-0228). 읽음을 남기지 않는다. */
	@GetMapping("/api/messages/inbox/{messageId}")
	ResponseEntity<ReceivedView> received(@AuthenticationPrincipal Actor actor, @PathVariable long messageId) {
		return ok(this.messages.received(actor.userId(), messageId));
	}

	/** 읽음 표시. 몇 번 보내도 결과가 같고 응답에 서버의 안 읽은 쪽지 수가 있다(COMM-02). */
	@PostMapping("/api/messages/inbox/{messageId}/read")
	ResponseEntity<ReadState> read(@AuthenticationPrincipal Actor actor, @PathVariable long messageId) {
		return ok(this.messages.markRead(actor, messageId));
	}

	/** 받은함에서만 지운다(EP-0240 단건). 발신자와 다른 수신자의 보관함은 그대로다. 204 */
	@DeleteMapping("/api/messages/inbox/{messageId}")
	ResponseEntity<Void> deleteReceived(@AuthenticationPrincipal Actor actor, @PathVariable long messageId) {
		this.messages.deleteReceived(actor, messageId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** 받은함 일괄·전체 삭제(EP-0240). */
	@PostMapping("/api/messages/inbox/delete")
	ResponseEntity<Deleted> deleteReceived(@AuthenticationPrincipal Actor actor, @RequestBody DeleteRequest body) {
		return ok(this.messages.deleteReceived(actor, body));
	}

	/** 안 읽은 쪽지 수(EP-0239). 헤더 배지 polling용이다. */
	@GetMapping("/api/messages/unread-count")
	ResponseEntity<UnreadCount> unreadCount(@AuthenticationPrincipal Actor actor) {
		return ok(this.messages.unread(actor.userId()));
	}

	/** 보낸함(EP-0233). q는 제목 검색. 발송 일시 역순, nextCursor로 다음 쪽. */
	@GetMapping("/api/messages/sent")
	ResponseEntity<SentPage> sentBox(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) String q,
			@RequestParam(required = false) String cursor, @RequestParam(defaultValue = "20") int size) {
		return ok(this.messages.sentBox(actor.userId(), q, cursor, size));
	}

	/** 보낸 쪽지 상세와 수신자별 읽음·회수 상태(EP-0235). */
	@GetMapping("/api/messages/sent/{messageId}")
	ResponseEntity<SentView> sent(@AuthenticationPrincipal Actor actor, @PathVariable long messageId) {
		return ok(this.messages.sent(actor.userId(), messageId));
	}

	/** 회수(EP-0223). 읽지 않은 수신자에게서만 거두고 수신자별 결과를 돌려준다. */
	@PostMapping("/api/messages/sent/{messageId}/recall")
	ResponseEntity<RecallResult> recall(@AuthenticationPrincipal Actor actor, @PathVariable long messageId) {
		return ok(this.messages.recall(actor, messageId));
	}

	/** 보낸함에서만 지운다(EP-0241 단건). 수신자의 받은함은 그대로다. 204 */
	@DeleteMapping("/api/messages/sent/{messageId}")
	ResponseEntity<Void> deleteSent(@AuthenticationPrincipal Actor actor, @PathVariable long messageId) {
		this.messages.deleteSent(actor, messageId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** 보낸함 일괄·전체 삭제(EP-0241). */
	@PostMapping("/api/messages/sent/delete")
	ResponseEntity<Deleted> deleteSent(@AuthenticationPrincipal Actor actor, @RequestBody DeleteRequest body) {
		return ok(this.messages.deleteSent(actor, body));
	}

	/**
	 * 쪽지쓰기 임시저장 조회(EP-0117). 저장한 과목과 받는사람을 지금 범위로 다시 판정해 보낼 수 있는 사람만 준다. 없으면 204
	 */
	@GetMapping("/api/messages/draft")
	ResponseEntity<ComposeDraft> draft(@AuthenticationPrincipal Actor actor) {
		return this.messages.composeDraft(actor.userId())
			.map(MessageController::ok)
			.orElseGet(() -> ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build());
	}

	/**
	 * 쪽지쓰기 임시저장(EP-0115). 제목, 본문, 과목, 받는사람. revision이 저장된 값보다 커야 덮어쓴다. 아니면 409 draft_stale
	 */
	@PutMapping("/api/messages/draft")
	ResponseEntity<Saved> saveDraft(@AuthenticationPrincipal Actor actor, @RequestBody DraftRequest body) {
		return ok(this.messages.saveComposeDraft(actor.userId(), body));
	}

	/** 쪽지쓰기 임시저장 삭제(EP-0116). 없어도 204 */
	@DeleteMapping("/api/messages/draft")
	ResponseEntity<Void> deleteDraft(@AuthenticationPrincipal Actor actor) {
		this.drafts.delete(actor.userId(), DraftKey.MESSAGE_COMPOSE);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	private static <T> ResponseEntity<T> created(T body) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
	}

}
