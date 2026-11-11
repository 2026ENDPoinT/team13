package com.stackcorp.astra.catalog;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

import com.stackcorp.astra.catalog.NotificationRecords.Event;
import com.stackcorp.astra.catalog.NotificationRecords.NotificationRow;
import com.stackcorp.astra.catalog.NotificationRecords.Position;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.CourseRosters;
import com.stackcorp.astra.catalog.api.NotificationCounts;
import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림 생성(다른 모듈이 부르는 {@link Notifications})과 받는 사람의 알림함(EP-0138, EP-0280~0282, ADR-0027).
 * <p>
 * 생성은 부르는 쪽 transaction 안에서만 한다. 과목 알림은 사건 행을 먼저 잠그고 그다음 알림 행을 쓴다. worker도 같은 순서다.
 * 알림함의 목록, 안 읽은 수, 읽음, 삭제는 {@link NotificationRecords}의 같은 조건을 쓴다. 사용자는 session principal로만 정하고,
 * 다른 사람의 알림이나 지금 볼 수 없는 알림은 ID를 알아도 404다(SC-02). 조회는 읽음을 바꾸지 않는다(SC-04).
 */
@Service
class NotificationService implements Notifications, NotificationCounts {

	static final int DEFAULT_PAGE = 10;

	static final int MAX_PAGE = 50;

	/** notifications.message_text 길이(글자). */
	private static final int MAX_MESSAGE = 500;

	private final NotificationRecords records;

	private final CourseRosters rosters;

	private final AuditLog audit;

	private final TransactionTemplate commands;

	NotificationService(NotificationRecords records, CourseRosters rosters, AuditLog audit,
			PlatformTransactionManager transactionManager) {
		this.records = records;
		this.rosters = rosters;
		this.audit = audit;
		// 읽음·삭제는 사용자 범위의 여러 행을 바꾼다. 같은 사용자의 행에 알림을 만드는 transaction과 범위 잠금으로 엉키지 않게 한다.
		this.commands = new TransactionTemplate(transactionManager);
		this.commands.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	// ---- 생성 ----

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void publish(CourseNotification notification) {
		NotificationKind kind = Objects.requireNonNull(notification.kind());
		CourseRole audience = Objects.requireNonNull(notification.audience());
		String message = message(kind, notification.subject());
		LocalDateTime now = this.records.now();
		LocalDateTime visibleAt = notification.visibleAt() == null ? now : notification.visibleAt();
		Event event = this.records.upsertEvent(kind, notification.targetId(), notification.courseId(),
				activityOf(kind, notification.targetId()), audience, message, visibleAt);
		if (visibleAt.isAfter(now)) {
			// 아직 보이지 않는다. 이미 보냈으면 거두고 그 시각에 다시 보낸다.
			if (event.delivered()) {
				this.records.deleteAll(kind, notification.targetId());
				this.records.markPending(event.eventId());
			}
			return;
		}
		if (event.delivered()) {
			this.records.retitle(kind, notification.targetId(), message);
			return;
		}
		deliver(event);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void send(DirectNotification notification) {
		NotificationKind kind = Objects.requireNonNull(notification.kind());
		TreeSet<Long> unique = new TreeSet<>(notification.recipients());
		if (unique.size() > MAX_RECIPIENTS) {
			throw new IllegalArgumentException("recipients exceed " + MAX_RECIPIENTS);
		}
		String message = message(kind, notification.subject());
		List<Long> recipients = notification.courseId() == null ? List.copyOf(unique)
				: this.records.members(notification.courseId(), unique);
		this.records.insert(recipients, kind, notification.courseId(), activityOf(kind, notification.targetId()),
				notification.targetId(), message);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void withdraw(NotificationKind kind, long targetId) {
		this.records.deleteEvent(kind, targetId);
		this.records.deleteAll(kind, targetId);
	}

	/**
	 * 사건의 받는 사람을 지금 정해 알림 행을 만들고 보낸 것으로 표시한다. 사건 행을 잠근 transaction 안에서 부른다.
	 * @return 새로 만든 알림 행 수
	 */
	int deliver(Event event) {
		List<Long> recipients = event.audience() == CourseRole.STUDENT ? this.rosters.students(event.courseId())
				: this.records.instructors(event.courseId());
		int created = this.records.insert(recipients, event.kind(), event.courseId(), event.activityId(),
				event.targetId(), event.message());
		this.records.markDelivered(event.eventId());
		return created;
	}

	// ---- 알림함 ----

	@Override
	public int unread(long userId, LocalDateTime now) {
		return this.records.unread(userId, now);
	}

	/**
	 * @param kind 이 유형만. {@link NotificationKind} 이름
	 * @param cursor 앞 응답의 nextCursor. 처음이면 null
	 */
	@Transactional(readOnly = true)
	Inbox inbox(long userId, Long courseId, String kind, boolean unreadOnly, String cursor, int size) {
		if (size < 1 || size > MAX_PAGE) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE + " 이하여야 합니다.");
		}
		NotificationKind type = kind(kind);
		Position after = decode(cursor);
		LocalDateTime now = this.records.now();
		// 한 건 더 읽어 다음 쪽이 있는지 안다.
		List<NotificationRow> rows = this.records.page(userId, now, courseId, type, unreadOnly, after, size + 1);
		List<NotificationRow> page = rows.size() > size ? rows.subList(0, size) : rows;
		String next = rows.size() > size ? encode(page.getLast().position()) : null;
		return new Inbox(now, page.stream().map(NotificationView::of).toList(), this.records.unread(userId, now), next);
	}

	@Transactional(readOnly = true)
	UnreadCount unreadCount(long userId) {
		LocalDateTime now = this.records.now();
		return new UnreadCount(now, this.records.unread(userId, now));
	}

	/** 읽음 표시. 몇 번 보내도 결과가 같다. 처음 읽은 시각을 지킨다. */
	ReadResult markRead(Actor actor, long notificationId) {
		return this.commands.execute((tx) -> {
			LocalDateTime now = this.records.now();
			this.records.markRead(actor.userId(), notificationId, now);
			NotificationRow row = this.records.find(actor.userId(), notificationId, now)
				.orElseThrow(ApiException::notFound);
			this.audit.success(actor.userId(), actor.sessionId(), "notification.read", "notification", notificationId,
					row.courseId());
			return new ReadResult(NotificationView.of(row), this.records.unread(actor.userId(), now));
		});
	}

	/** 지금 볼 수 있는 안 읽은 알림을 모두 읽음으로 한다. 몇 번 보내도 결과가 같다. */
	ReadAllResult markAllRead(Actor actor) {
		return this.commands.execute((tx) -> {
			LocalDateTime now = this.records.now();
			int updated = this.records.markAllRead(actor.userId(), now);
			this.audit.record(actor.userId(), actor.sessionId(), "notification.read_all", null, null, null, AuditLog.OK);
			return new ReadAllResult(updated, this.records.unread(actor.userId(), now));
		});
	}

	void delete(Actor actor, long notificationId) {
		this.commands.executeWithoutResult((tx) -> {
			LocalDateTime now = this.records.now();
			NotificationRow row = this.records.find(actor.userId(), notificationId, now)
				.orElseThrow(ApiException::notFound);
			if (this.records.delete(actor.userId(), notificationId, now) == 0) {
				throw ApiException.notFound();
			}
			this.audit.success(actor.userId(), actor.sessionId(), "notification.delete", "notification",
					notificationId, row.courseId());
		});
	}

	/** 유형 문장 뒤에 대상 제목을 따옴표로 붙인다. 제어 문자는 공백으로 바꾸고, 길면 제목을 글자 단위로 자른다. */
	static String message(NotificationKind kind, String subject) {
		String lead = kind.lead() + " \"";
		String title = Objects.requireNonNull(subject).codePoints()
			.map((cp) -> Character.isISOControl(cp) ? ' ' : cp)
			.collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
			.toString()
			.strip();
		int room = MAX_MESSAGE - lead.codePointCount(0, lead.length()) - 1;
		if (title.codePointCount(0, title.length()) > room) {
			title = title.substring(0, title.offsetByCodePoints(0, room - 1)) + "…";
		}
		return lead + title + "\"";
	}

	private static Long activityOf(NotificationKind kind, long targetId) {
		return kind.target() == NotificationKind.Target.ACTIVITY ? targetId : null;
	}

	private static NotificationKind kind(String kind) {
		if (kind == null || kind.isEmpty()) {
			return null;
		}
		try {
			return NotificationKind.valueOf(kind);
		}
		catch (IllegalArgumentException ex) {
			throw ApiException.invalid("kind가 올바르지 않습니다.");
		}
	}

	/** 커서는 마지막 항목의 (생성 일시, 알림 ID)다. 다음 쪽의 시작 위치로만 쓴다. */
	private static String encode(Position position) {
		String raw = position.createdAt() + "~" + position.notificationId();
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

	/**
	 * @param kind {@link NotificationKind} 이름
	 * @param courseName 과목 알림이 아니면(쪽지 등) null
	 * @param targetType 연결 대상 종류: ACTIVITY|ARTICLE|MESSAGE. 화면은 kind, courseId, targetId로 원문 경로를 만든다
	 */
	record NotificationView(long notificationId, String kind, String message, Long courseId, String courseName,
			String courseCode, String section, String targetType, long targetId, boolean read,
			LocalDateTime createdAt) {

		static NotificationView of(NotificationRow row) {
			return new NotificationView(row.notificationId(), row.kind(), row.message(), row.courseId(),
					row.courseName(), row.courseCode(), row.section(), row.targetKind(), row.targetId(),
					row.readAt() != null, row.createdAt());
		}

	}

	/**
	 * @param unreadCount 필터와 관계없는 안 읽은 알림 전체 수(헤더 배지)
	 * @param nextCursor 다음 쪽이 있으면 그 커서, 없으면 null
	 */
	record Inbox(LocalDateTime serverTime, List<NotificationView> items, int unreadCount, String nextCursor) {
	}

	record UnreadCount(LocalDateTime serverTime, int unreadCount) {
	}

	record ReadResult(NotificationView notification, int unreadCount) {
	}

	/**
	 * @param updated 이번에 읽음으로 바뀐 수. 다시 보내면 0
	 */
	record ReadAllResult(int updated, int unreadCount) {
	}

}
