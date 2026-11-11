package com.stackcorp.astra.communication;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.catalog.api.CourseMates;
import com.stackcorp.astra.catalog.api.CourseMates.CourseMate;
import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.MemberCourses;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.catalog.api.Notifications.DirectNotification;
import com.stackcorp.astra.communication.MessageRepository.InboxRow;
import com.stackcorp.astra.communication.MessageRepository.MessageRow;
import com.stackcorp.astra.communication.MessageRepository.Position;
import com.stackcorp.astra.communication.MessageRepository.RecipientRow;
import com.stackcorp.astra.communication.MessageRepository.SentRow;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.identity.api.UserSearch;
import com.stackcorp.astra.identity.api.UserSearch.Matches;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쪽지(EP-0221~EP-0241의 대체 계약, ADR-0025). 보내는 사람과 보관함의 주인은 session principal뿐이다(SC-02).
 * <ul>
 * <li>받는사람 범위: 같은 과목의 사람 가운데 담당자는 그 과목의 누구에게나, 학생은 그 과목 담당자에게만 보낸다. 과목을 고르면 그
 * 과목 안에서만 판정한다. 서버가 계산한 범위 밖의 ID가 하나라도 있으면 아무것도 보내지 않는다(COMM-05, SEC-08). 답장은 받은 쪽지의
 * 발신자에게만 가고 범위 판정을 하지 않는다. 발신자가 먼저 연락했기 때문이다.</li>
 * <li>발송: Idempotency-Key가 필요하다. 쪽지, 수신자 행, 감사 기록, 멱등 receipt가 한 transaction이다(SC-05, COMM-03).</li>
 * <li>보관함: 받은함은 수신자 행, 보낸함은 쪽지 행이다. 한쪽에서 지워도 다른 사람의 보관함은 그대로다. 일괄 삭제는 ID가 하나라도
 * 요청한 사람의 것이 아니면 전체를 404로 거부한다. 전체 삭제는 서버가 요청자의 목록 조건과 목록을 받은 시각으로 범위를 정한다.</li>
 * <li>읽음과 회수: 조회는 아무것도 바꾸지 않는다(SC-04). 읽음은 POST이고 처음 한 번만 시각을 남긴다. 회수는 아직 읽지 않은 수신자
 * 행만 바꾼다. 두 전이는 같은 행의 서로 배타적인 조건부 UPDATE라 동시에 와도 한쪽만 이긴다(COMM-04). 회수된 쪽지는 받은함에서
 * 사라진다.</li>
 * </ul>
 * 여러 행을 바꾸는 전이는 READ COMMITTED transaction에서 쪽지 행을 먼저 잠그고 수신자 행을 바꾸며, 교착은 정해진 횟수만 다시 시도한다.
 */
@Service
class MessageService {

	static final int MAX_TITLE = 300;

	/** 원본 화면의 본문 상한(07 §5.1). {@link PlainText#screenBytes}로 센다. */
	static final int MAX_BODY_BYTES = 2000;

	/** 한 과목 수강생 전체에게 한 번에 보낼 수 있는 수. */
	static final int MAX_RECIPIENTS = 300;

	static final int MAX_PAGE_SIZE = 50;

	static final int MAX_QUERY = 100;

	static final int MAX_NAME_QUERY = 50;

	static final int MAX_BULK = 500;

	private static final String NOT_ALLOWED = "쪽지를 보낼 수 없는 받는사람이 있습니다.";

	private final MessageRepository messages;

	private final DraftRepository drafts;

	private final CourseMemberships memberships;

	private final CourseMates mates;

	private final MemberCourses memberCourses;

	private final UserNames userNames;

	private final UserSearch userSearch;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final ReadCommitted readCommitted;

	private final Notifications notifications;

	MessageService(MessageRepository messages, DraftRepository drafts, CourseMemberships memberships, CourseMates mates,
			MemberCourses memberCourses, UserNames userNames, UserSearch userSearch, RateLimiter rateLimiter,
			Idempotency idempotency, AuditLog audit, ReadCommitted readCommitted, Notifications notifications) {
		this.messages = messages;
		this.drafts = drafts;
		this.memberships = memberships;
		this.mates = mates;
		this.memberCourses = memberCourses;
		this.userNames = userNames;
		this.userSearch = userSearch;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
		this.readCommitted = readCommitted;
		this.notifications = notifications;
	}

	// ---- 받는사람

	/** 받는사람 후보. 서버가 계산한 범위 안에서만 이름으로 찾는다(EP-0222, EP-0237). */
	@Transactional(readOnly = true)
	ContactPage contacts(long userId, Long courseId, String query, int page, int size) {
		requireMember(userId, courseId);
		String name = PlainText.query(query, MAX_NAME_QUERY);
		requirePage(page, size);
		this.rateLimiter.check("message-contacts", userId, 120, Duration.ofMinutes(10));
		Map<Long, CourseRole> reach = reachable(userId, courseId, null);
		Matches matches = this.userSearch.search(reach.keySet(), name,
				(int) Math.min((long) (page - 1) * size, Integer.MAX_VALUE), size);
		List<Contact> items = matches.items()
			.stream()
			.map((m) -> new Contact(m.userId(), m.name(), reach.get(m.userId()).name()))
			.toList();
		return new ContactPage(items, matches.total(), page, size);
	}

	// ---- 보내기

	SentReceipt send(Actor actor, String key, SendRequest body) {
		Idempotency.requireKey(key);
		Outgoing out = outgoing(body.title(), body.body());
		requireMember(actor.userId(), body.courseId());
		List<Long> recipients = recipientIds(body.recipientIds());
		requireReachable(actor.userId(), body.courseId(), recipients);
		limitSend(actor.userId());
		Fingerprint fingerprint = new Fingerprint(null, body.courseId(), out, recipients);
		return this.idempotency.execute(actor.userId(), "message.send", key, fingerprint, SentReceipt.class, () -> {
			SentReceipt receipt = deliver(actor.userId(), body.courseId(), out, recipients, null, null);
			// 보낸 내용은 더 이상 초안이 아니다(EP-0116 서버 요구: 게시 transaction에서 초안 삭제).
			this.drafts.delete(actor.userId(), DraftKey.MESSAGE_COMPOSE);
			this.audit.success(actor.userId(), actor.sessionId(), "message.send", "message", receipt.messageId(),
					body.courseId());
			return receipt;
		});
	}

	/** 받은 쪽지의 발신자에게 답한다(EP-0229). 받는사람과 과목은 원래 쪽지에서 서버가 정한다. */
	SentReceipt reply(Actor actor, long messageId, String key, ReplyRequest body) {
		Idempotency.requireKey(key);
		Outgoing out = outgoing(body.title(), body.body());
		MessageRow original = this.messages.received(actor.userId(), messageId)
			.orElseThrow(ApiException::notFound)
			.message();
		List<Long> recipients = List.of(original.senderId());
		limitSend(actor.userId());
		Fingerprint fingerprint = new Fingerprint(messageId, original.courseId(), out, recipients);
		return this.idempotency.execute(actor.userId(), "message.reply", key, fingerprint, SentReceipt.class, () -> {
			SentReceipt receipt = deliver(actor.userId(), original.courseId(), out, recipients, messageId, "REPLY");
			this.audit.success(actor.userId(), actor.sessionId(), "message.reply", "message", receipt.messageId(),
					original.courseId());
			return receipt;
		});
	}

	/** 받은 쪽지나 보낸 쪽지를 전달한다(EP-0224, EP-0232). 받는사람은 새 쪽지와 같은 범위 판정을 거친다. */
	SentReceipt forward(Actor actor, Box box, long messageId, String key, SendRequest body) {
		Idempotency.requireKey(key);
		Outgoing out = outgoing(body.title(), body.body());
		boolean visible = box == Box.INBOX ? this.messages.received(actor.userId(), messageId).isPresent()
				: this.messages.sent(actor.userId(), messageId).isPresent();
		if (!visible) {
			throw ApiException.notFound();
		}
		requireMember(actor.userId(), body.courseId());
		List<Long> recipients = recipientIds(body.recipientIds());
		requireReachable(actor.userId(), body.courseId(), recipients);
		limitSend(actor.userId());
		Fingerprint fingerprint = new Fingerprint(messageId, body.courseId(), out, recipients);
		return this.idempotency.execute(actor.userId(), "message.forward", key, fingerprint, SentReceipt.class, () -> {
			SentReceipt receipt = deliver(actor.userId(), body.courseId(), out, recipients, messageId, "FORWARD");
			this.audit.success(actor.userId(), actor.sessionId(), "message.forward", "message", receipt.messageId(),
					body.courseId());
			return receipt;
		});
	}

	private SentReceipt deliver(long senderId, Long courseId, Outgoing out, List<Long> recipients, Long originId,
			String originKind) {
		LocalDateTime now = this.messages.now();
		long messageId = this.messages.insertMessage(senderId, courseId, out.title(), out.body(), recipients.size(),
				originId, originKind, now);
		this.messages.insertRecipients(messageId, recipients, courseId, now);
		// 쪽지는 과목 관계가 끊겨도 받은함에 남으므로 알림에 과목을 묶지 않는다(ADR-0027).
		this.notifications.send(new DirectNotification(NotificationKind.MESSAGE_RECEIVED, recipients, null, messageId,
				out.title()));
		Map<Long, String> names = this.userNames.namesOf(recipients);
		return new SentReceipt(messageId, now, recipients.stream().map((id) -> new Person(id, names.get(id))).toList());
	}

	// ---- 받은함

	@Transactional(readOnly = true)
	InboxPage inbox(long userId, Boolean read, String query, String cursor, int size) {
		requireSize(size);
		String title = PlainText.query(query, MAX_QUERY);
		Position after = decode(cursor);
		LocalDateTime now = this.messages.now();
		List<InboxRow> rows = this.messages.inboxPage(userId, read, title, after, size + 1);
		List<InboxRow> page = rows.size() > size ? rows.subList(0, size) : rows;
		String next = rows.size() > size ? encode(page.getLast().sentAt(), page.getLast().messageId()) : null;
		Map<Long, String> names = this.userNames.namesOf(page.stream().map(InboxRow::senderId).distinct().toList());
		List<InboxItem> items = page.stream()
			.map((r) -> new InboxItem(r.messageId(), r.title(), new Person(r.senderId(), names.get(r.senderId())),
					r.courseId(), r.sentAt(), r.readAt() != null))
			.toList();
		return new InboxPage(now, items, this.messages.inboxCount(userId, read, title),
				this.messages.unreadCount(userId), next);
	}

	/** 받은 쪽지 상세. 읽음을 남기지 않는다(SC-04). 회수되거나 지운 쪽지는 404다. */
	@Transactional(readOnly = true)
	ReceivedView received(long userId, long messageId) {
		var received = this.messages.received(userId, messageId).orElseThrow(ApiException::notFound);
		MessageRow m = received.message();
		String sender = this.userNames.namesOf(List.of(m.senderId())).get(m.senderId());
		return new ReceivedView(m.messageId(), m.title(), m.body(), m.sentAt(), new Person(m.senderId(), sender),
				course(userId, m.courseId()), m.originKind(), received.readAt() != null, received.readAt());
	}

	/** 읽음 표시. 처음 한 번만 시각을 남기고, 몇 번 보내도 결과가 같다(EP-0228, COMM-02). */
	ReadState markRead(Actor actor, long messageId) {
		return this.readCommitted.execute(() -> {
			LocalDateTime now = this.messages.now();
			boolean wasUnread = this.messages.markRead(actor.userId(), messageId, now) == 1;
			if (!wasUnread && this.messages.received(actor.userId(), messageId).isEmpty()) {
				throw ApiException.notFound();
			}
			if (wasUnread) {
				this.audit.success(actor.userId(), actor.sessionId(), "message.read", "message", messageId, null);
			}
			return new ReadState(messageId, true, wasUnread, this.messages.unreadCount(actor.userId()));
		});
	}

	@Transactional(readOnly = true)
	UnreadCount unread(long userId) {
		return new UnreadCount(this.messages.unreadCount(userId));
	}

	void deleteReceived(Actor actor, long messageId) {
		this.readCommitted.execute(() -> {
			List<Long> ids = List.of(messageId);
			if (this.messages.deleteFromInbox(actor.userId(), ids, this.messages.now()) == 0
					&& this.messages.ownedInInbox(actor.userId(), ids) == 0) {
				throw ApiException.notFound();
			}
			this.audit.success(actor.userId(), actor.sessionId(), "message.inbox_delete", "message", messageId, null);
			return null;
		});
	}

	/**
	 * 받은함 일괄 삭제(EP-0240). ID 목록이면 모두 요청한 사람이 받은 쪽지여야 하고, 하나라도 아니면 아무것도 지우지 않고 404다.
	 * 전체 삭제면 목록과 같은 조건(read, q)으로 until까지 받은 쪽지에서 exceptIds를 뺀 것을 지운다. 그 뒤에 온 쪽지는 남는다(COMM-06).
	 */
	Deleted deleteReceived(Actor actor, DeleteRequest body) {
		Selection selection = selection(body, true);
		return this.readCommitted.execute(() -> {
			LocalDateTime now = this.messages.now();
			int deleted;
			if (selection.ids() != null) {
				if (this.messages.ownedInInbox(actor.userId(), selection.ids()) != selection.ids().size()) {
					throw ApiException.notFound();
				}
				deleted = this.messages.deleteFromInbox(actor.userId(), selection.ids(), now);
			}
			else {
				deleted = this.messages.deleteInboxMatching(actor.userId(), selection.read(), selection.query(),
						selection.until(), selection.except(), now);
			}
			this.audit.record(actor.userId(), actor.sessionId(), "message.inbox_delete", "message", null, null,
					AuditLog.OK);
			return new Deleted(deleted);
		});
	}

	// ---- 보낸함

	@Transactional(readOnly = true)
	SentPage sentBox(long userId, String query, String cursor, int size) {
		requireSize(size);
		String title = PlainText.query(query, MAX_QUERY);
		Position after = decode(cursor);
		LocalDateTime now = this.messages.now();
		List<SentRow> rows = this.messages.sentPage(userId, title, after, size + 1);
		List<SentRow> page = rows.size() > size ? rows.subList(0, size) : rows;
		String next = rows.size() > size ? encode(page.getLast().sentAt(), page.getLast().messageId()) : null;
		Map<Long, String> names = this.userNames
			.namesOf(page.stream().map(SentRow::firstRecipientId).distinct().toList());
		List<SentItem> items = page.stream()
			.map((r) -> new SentItem(r.messageId(), r.title(), r.courseId(), r.sentAt(),
					new Person(r.firstRecipientId(), names.get(r.firstRecipientId())), r.recipientCount(), r.readCount(),
					r.recalledCount()))
			.toList();
		return new SentPage(now, items, this.messages.sentCount(userId, title), next);
	}

	/** 보낸 쪽지 상세와 수신자별 읽음·회수 상태(EP-0235). */
	@Transactional(readOnly = true)
	SentView sent(long userId, long messageId) {
		MessageRow m = this.messages.sent(userId, messageId).orElseThrow(ApiException::notFound);
		List<RecipientRow> rows = this.messages.recipients(messageId);
		Map<Long, String> names = this.userNames.namesOf(rows.stream().map(RecipientRow::recipientId).toList());
		List<RecipientView> recipients = rows.stream()
			.map((r) -> new RecipientView(r.recipientId(), names.get(r.recipientId()), r.readAt(), r.recalledAt() != null))
			.toList();
		return new SentView(m.messageId(), m.title(), m.body(), m.sentAt(), course(userId, m.courseId()), m.originKind(),
				recipients);
	}

	/**
	 * 회수(EP-0223). 아직 읽지 않은 수신자에게서만 거둔다. 결과는 수신자마다 recalled 또는 read 하나이고 몇 번 보내도 같다. 이미 읽은
	 * 수신자의 쪽지는 그대로 남는다.
	 */
	RecallResult recall(Actor actor, long messageId) {
		return this.readCommitted.execute(() -> {
			this.messages.lockSent(actor.userId(), messageId).orElseThrow(ApiException::notFound);
			int recalled = this.messages.recall(messageId, this.messages.now());
			if (recalled > 0) {
				// 거둔 쪽지는 받은함에서 사라지므로 알림도 거둔다. 이미 읽은 수신자의 알림도 함께 지워진다.
				this.notifications.withdraw(NotificationKind.MESSAGE_RECEIVED, messageId);
			}
			List<RecipientRow> rows = this.messages.recipients(messageId);
			Map<Long, String> names = this.userNames.namesOf(rows.stream().map(RecipientRow::recipientId).toList());
			List<RecallOutcome> outcomes = rows.stream()
				.map((r) -> new RecallOutcome(r.recipientId(), names.get(r.recipientId()),
						r.recalledAt() != null ? "recalled" : "read"))
				.toList();
			this.audit.success(actor.userId(), actor.sessionId(), "message.recall", "message", messageId, null);
			return new RecallResult(messageId, recalled, outcomes);
		});
	}

	void deleteSent(Actor actor, long messageId) {
		this.readCommitted.execute(() -> {
			List<Long> ids = List.of(messageId);
			if (this.messages.deleteFromSent(actor.userId(), ids, this.messages.now()) == 0
					&& this.messages.ownedInSent(actor.userId(), ids) == 0) {
				throw ApiException.notFound();
			}
			this.audit.success(actor.userId(), actor.sessionId(), "message.sent_delete", "message", messageId, null);
			return null;
		});
	}

	/** 보낸함 일괄 삭제(EP-0241). 규칙은 받은함과 같다. 수신자의 받은함에는 손대지 않는다. */
	Deleted deleteSent(Actor actor, DeleteRequest body) {
		Selection selection = selection(body, false);
		return this.readCommitted.execute(() -> {
			LocalDateTime now = this.messages.now();
			int deleted;
			if (selection.ids() != null) {
				if (this.messages.ownedInSent(actor.userId(), selection.ids()) != selection.ids().size()) {
					throw ApiException.notFound();
				}
				deleted = this.messages.deleteFromSent(actor.userId(), selection.ids(), now);
			}
			else {
				deleted = this.messages.deleteSentMatching(actor.userId(), selection.query(), selection.until(),
						selection.except(), now);
			}
			this.audit.record(actor.userId(), actor.sessionId(), "message.sent_delete", "message", null, null,
					AuditLog.OK);
			return new Deleted(deleted);
		});
	}

	// ---- 판정과 검사

	/**
	 * 보낼 수 있는 사람과 그 사람의 역할. 같은 과목에서 내가 담당자이거나 상대가 담당자일 때만 넣는다. 여러 과목에서 만나면 담당자
	 * 역할을 앞세운다.
	 */
	private Map<Long, CourseRole> reachable(long userId, Long courseId, List<Long> only) {
		Map<Long, CourseRole> reach = new HashMap<>();
		for (CourseMate mate : this.mates.of(userId, courseId, only)) {
			if (mate.viewerRole() == CourseRole.INSTRUCTOR || mate.role() == CourseRole.INSTRUCTOR) {
				reach.merge(mate.userId(), mate.role(),
						(a, b) -> a == CourseRole.INSTRUCTOR || b == CourseRole.INSTRUCTOR ? CourseRole.INSTRUCTOR
								: CourseRole.STUDENT);
			}
		}
		return reach;
	}

	private void requireReachable(long userId, Long courseId, List<Long> recipients) {
		if (!reachable(userId, courseId, recipients).keySet().containsAll(recipients)) {
			throw new ApiException(HttpStatus.BAD_REQUEST, "recipient_not_allowed", NOT_ALLOWED);
		}
	}

	private void requireMember(long userId, Long courseId) {
		if (courseId != null && this.memberships.roleOf(userId, courseId).isEmpty()) {
			throw ApiException.notFound();
		}
	}

	/** 관련 과목. 보는 사람이 그 과목 구성원일 때만 과목명을 준다. */
	private CourseRef course(long userId, Long courseId) {
		if (courseId == null) {
			return null;
		}
		return this.memberCourses.of(userId)
			.stream()
			.filter((c) -> c.courseId() == courseId)
			.findFirst()
			.map((c) -> new CourseRef(c.courseId(), c.name(), c.code(), c.section()))
			.orElse(null);
	}

	private void limitSend(long userId) {
		this.rateLimiter.check("message-send", userId, 30, Duration.ofMinutes(10));
	}

	private static Outgoing outgoing(String title, String body) {
		String t = PlainText.line(title, MAX_TITLE, "title");
		String text = PlainText.block(body, MAX_BODY_BYTES * 10, "body").strip();
		if (text.isEmpty() || PlainText.screenBytes(text) > MAX_BODY_BYTES) {
			throw ApiException.invalid("body는 1byte 이상 " + MAX_BODY_BYTES + "byte 이하여야 합니다.");
		}
		return new Outgoing(t, text);
	}

	private static List<Long> recipientIds(List<Long> ids) {
		if (ids == null || ids.isEmpty() || ids.size() > MAX_RECIPIENTS) {
			throw ApiException.invalid("받는사람은 1명 이상 " + MAX_RECIPIENTS + "명 이하여야 합니다.");
		}
		return distinctIds(ids, "recipientIds");
	}

	private static List<Long> distinctIds(List<Long> ids, String field) {
		if (ids.stream().anyMatch((id) -> id == null || id < 1) || new LinkedHashSet<>(ids).size() != ids.size()) {
			throw ApiException.invalid(field + "에는 서로 다른 ID만 넣어야 합니다.");
		}
		return ids.stream().sorted().toList();
	}

	private static Selection selection(DeleteRequest body, boolean inbox) {
		boolean all = Boolean.TRUE.equals(body.all());
		if (!all) {
			if (body.messageIds() == null || body.messageIds().isEmpty() || body.messageIds().size() > MAX_BULK
					|| body.all() != null || body.read() != null || body.q() != null || body.until() != null
					|| body.exceptIds() != null) {
				throw ApiException.invalid("messageIds에 1개 이상 " + MAX_BULK + "개 이하의 ID를 넣거나 all을 true로 보내야 합니다.");
			}
			return new Selection(distinctIds(body.messageIds(), "messageIds"), null, null, null, List.of());
		}
		if (body.messageIds() != null || body.until() == null || (!inbox && body.read() != null)) {
			throw ApiException.invalid("전체 삭제에는 목록을 받은 시각(until)이 필요하고 messageIds는 줄 수 없습니다.");
		}
		List<Long> except = body.exceptIds() == null ? List.of() : body.exceptIds();
		if (except.size() > MAX_BULK) {
			throw ApiException.invalid("exceptIds는 " + MAX_BULK + "개 이하여야 합니다.");
		}
		return new Selection(null, body.read(), PlainText.query(body.q(), MAX_QUERY), body.until(),
				except.isEmpty() ? except : distinctIds(except, "exceptIds"));
	}

	private static void requireSize(int size) {
		if (size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
	}

	private static void requirePage(int page, int size) {
		if (page < 1) {
			throw ApiException.invalid("page는 1 이상이어야 합니다.");
		}
		requireSize(size);
	}

	/** 커서는 마지막 항목의 (발송 일시, 쪽지 ID)다. 다음 쪽의 시작 위치로만 쓰고 권한 판정에는 쓰지 않는다. */
	private static String encode(LocalDateTime sentAt, long messageId) {
		String raw = sentAt + "~" + messageId;
		return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	private static Position decode(String cursor) {
		if (cursor == null || cursor.isEmpty()) {
			return null;
		}
		try {
			String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
			int at = raw.indexOf('~');
			return new Position(LocalDateTime.parse(raw.substring(0, at)), Long.parseLong(raw.substring(at + 1)));
		}
		catch (RuntimeException ex) {
			throw ApiException.invalid("cursor가 올바르지 않습니다.");
		}
	}

	enum Box {

		INBOX, SENT

	}

	/**
	 * 새 쪽지와 전달. 보내는 사람은 받지 않는다(SC-02).
	 * @param courseId 관련 과목. 고르면 받는사람도 그 과목 안에서만 판정한다. "과목선택 안함"이면 null
	 * @param recipientIds {@code GET /api/messages/recipients}가 돌려준 사람의 ID
	 */
	record SendRequest(String title, String body, Long courseId, List<Long> recipientIds) {
	}

	record ReplyRequest(String title, String body) {
	}

	/**
	 * 일괄 삭제. messageIds와 all 중 하나만 쓴다.
	 * @param all true면 목록 조건(read, q)에 맞는 전체
	 * @param read 받은함만. true면 읽은 쪽지, false면 안 읽은 쪽지, 생략하면 전부
	 * @param until 목록 응답의 serverTime. 이 시각 뒤에 온 쪽지는 지우지 않는다
	 * @param exceptIds 전체 선택에서 뺀 쪽지
	 */
	record DeleteRequest(List<Long> messageIds, Boolean all, Boolean read, String q, LocalDateTime until,
			List<Long> exceptIds) {
	}

	/** 사람. 화면 표시용 이름만 준다. 학번 같은 학사 식별자는 주지 않는다(EP-0237). */
	record Person(long userId, String name) {
	}

	/**
	 * @param role 함께 있는 과목에서 그 사람의 역할. INSTRUCTOR|STUDENT
	 */
	record Contact(long userId, String name, String role) {
	}

	record ContactPage(List<Contact> items, long total, int page, int size) {
	}

	/** 발송 receipt. 같은 Idempotency-Key로 다시 보내면 같은 값을 돌려준다. */
	record SentReceipt(long messageId, LocalDateTime sentAt, List<Person> recipients) {
	}

	record InboxItem(long messageId, String title, Person sender, Long courseId, LocalDateTime sentAt, boolean read) {
	}

	/**
	 * @param serverTime 목록을 읽은 DB 시각. 전체 삭제의 until로 쓴다
	 * @param total 조건에 맞는 쪽지 수
	 * @param unreadCount 받은함 전체의 안 읽은 쪽지 수
	 */
	record InboxPage(LocalDateTime serverTime, List<InboxItem> items, long total, int unreadCount,
			String nextCursor) {
	}

	record CourseRef(long courseId, String name, String code, String section) {
	}

	/**
	 * @param course 관련 과목. 없거나 보는 사람이 그 과목 구성원이 아니면 null
	 * @param originKind 답장이면 REPLY, 전달이면 FORWARD
	 */
	record ReceivedView(long messageId, String title, String body, LocalDateTime sentAt, Person sender,
			CourseRef course, String originKind, boolean read, LocalDateTime readAt) {
	}

	/**
	 * @param wasUnread 이 요청이 읽음으로 바꿨는지. 이미 읽었으면 false
	 * @param unreadCount 바꾼 뒤 받은함의 안 읽은 쪽지 수
	 */
	record ReadState(long messageId, boolean read, boolean wasUnread, int unreadCount) {
	}

	record UnreadCount(int unreadCount) {
	}

	record Deleted(int deleted) {
	}

	/**
	 * @param firstRecipient 처음 넣은 받는사람. 목록의 "홍길동 외 n명" 표시용
	 */
	record SentItem(long messageId, String title, Long courseId, LocalDateTime sentAt, Person firstRecipient,
			int recipientCount, int readCount, int recalledCount) {
	}

	record SentPage(LocalDateTime serverTime, List<SentItem> items, long total, String nextCursor) {
	}

	record RecipientView(long userId, String name, LocalDateTime readAt, boolean recalled) {
	}

	record SentView(long messageId, String title, String body, LocalDateTime sentAt, CourseRef course,
			String originKind, List<RecipientView> recipients) {
	}

	/**
	 * @param outcome recalled(거뒀다. 이 요청 전에 거둔 것 포함)|read(이미 읽어 거두지 못했다)
	 */
	record RecallOutcome(long userId, String name, String outcome) {
	}

	/**
	 * @param recalledNow 이 요청이 새로 거둔 수신자 수
	 */
	record RecallResult(long messageId, int recalledNow, List<RecallOutcome> recipients) {
	}

	record Outgoing(String title, String body) {
	}

	/** 멱등 지문. 원래 쪽지, 과목, 검사한 본문, 정렬한 받는사람이다. */
	record Fingerprint(Long originId, Long courseId, Outgoing content, List<Long> recipients) {
	}

	private record Selection(List<Long> ids, Boolean read, String query, LocalDateTime until, List<Long> except) {
	}

}
