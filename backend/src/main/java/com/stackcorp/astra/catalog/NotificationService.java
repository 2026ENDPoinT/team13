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
import com.stackcorp.astra.identity.api.UserAudiences;
import com.stackcorp.astra.identity.api.UserAudiences.UserAudience;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;

import org.springframework.beans.factory.annotation.Value;
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
 * 과목 없는 전체 알림({@link #publishSite}, ADR-0036)은 만든 쪽이 사건만 남기고, worker가 대상 사용자를 묶음으로 나눠 보낸다.
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

	private final UserAudiences audiences;

	private final AuditLog audit;

	private final TransactionTemplate commands;

	private final int siteBatch;

	/**
	 * @param siteBatch 과목 없는 알림을 한 transaction에 보내는 사람 수
	 */
	NotificationService(NotificationRecords records, CourseRosters rosters, UserAudiences audiences, AuditLog audit,
			PlatformTransactionManager transactionManager,
			@Value("${astra.notification.site-batch:500}") int siteBatch) {
		this.records = records;
		this.rosters = rosters;
		this.audiences = audiences;
		this.audit = audit;
		// 읽음·삭제는 사용자 범위의 여러 행을 바꾼다. 같은 사용자의 행에 알림을 만드는 transaction과 범위 잠금으로 엉키지 않게 한다.
		this.commands = new TransactionTemplate(transactionManager);
		this.commands.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
		this.siteBatch = siteBatch;
	}

	// ---- 생성 ----

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void publish(CourseNotification notification) {
		NotificationKind kind = Objects.requireNonNull(notification.kind());
		if (kind == NotificationKind.SITE_NOTICE) {
			throw new IllegalArgumentException("site kind needs publishSite");
		}
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
				this.records.restart(event.eventId());
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
	public void publishSite(SiteNotification notification) {
		NotificationKind kind = Objects.requireNonNull(notification.kind());
		if (kind != NotificationKind.SITE_NOTICE) {
			throw new IllegalArgumentException("not a site kind: " + kind);
		}
		UserAudience audience = Objects.requireNonNull(notification.audience());
		String message = message(kind, notification.subject());
		LocalDateTime now = this.records.now();
		LocalDateTime visibleAt = notification.visibleAt() == null ? now : notification.visibleAt();
		Event event = this.records.upsertSiteEvent(kind, notification.targetId(), audience, message, visibleAt);
		if (event.siteAudience() != audience || visibleAt.isAfter(now)) {
			// 받는 대상이 바뀌었거나 아직 보이지 않는다. 보낸 알림을 거두고 처음부터(그 시각에) 다시 보낸다.
			if (event.started()) {
				this.records.deleteAll(kind, notification.targetId());
				this.records.restart(event.eventId());
			}
			if (event.siteAudience() != audience) {
				this.records.setSiteAudience(event.eventId(), audience);
			}
			return;
		}
		// 보낸 알림은 문장만 바꾼다. 보내기는 worker가 묶음마다 한다. 남은 묶음은 사건의 새 문장으로 만든다.
		if (event.started()) {
			this.records.retitle(kind, notification.targetId(), message);
		}
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
	 * 과목 사건의 받는 사람을 지금 정해 알림 행을 만들고 보낸 것으로 표시한다. 사건 행을 잠근 transaction 안에서 부른다.
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

	/**
	 * worker가 잠근 사건을 한 걸음 보낸다. 과목 사건은 한 번에 모두, 과목 없는 사건은 지난 위치 다음의 대상 사용자 한 묶음을 보내고
	 * 위치를 남긴다. 사건 행을 잠근 transaction 안에서 부른다. 같은 사람에게 두 번 가도 (유형, 대상, 사용자) 유일 키가 막는다.
	 * @return 이 사건을 다 보냈는지
	 */
	boolean deliverStep(Event event) {
		if (!event.site()) {
			deliver(event);
			return true;
		}
		long after = event.deliveredUserId() == null ? 0 : event.deliveredUserId();
		List<Long> users = this.audiences.members(event.siteAudience(), after, this.siteBatch);
		if (!users.isEmpty()) {
			this.records.insert(users, event.kind(), null, null, event.targetId(), event.message());
			this.records.advance(event.eventId(), users.getLast());
		}
		if (users.size() < this.siteBatch) {
			this.records.markDelivered(event.eventId());
			return true;
		}
		return false;
	}

	// ---- 알림함 ----

	@Override
	public int unread(long userId, LocalDateTime now) {
		return this.records.unread(userId, now);
	}

	/**
	 * 쿼리는 쪽 크기와 관계없이 넷(시계, 쪽, 필터에 맞는 수, 안 읽은 수)이고 모두 같은 DB 시각과 같은 보이는 조건을 쓴다.
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
		return new Inbox(now, page.stream().map(NotificationView::of).toList(),
				this.records.count(userId, now, courseId, type, unreadOnly), this.records.unread(userId, now), next);
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
	 * @param targetType 연결 대상 종류: ACTIVITY|ASSIGNMENT|ARTICLE|MESSAGE. 화면은 targetType, courseId, targetId로 원문 경로를 만든다
	 * ({@link NotificationKind.Target})
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
	 * @param total 고른 조건(과목, 유형, 안 읽음)에 맞는 알림 전체 수. 커서와 관계없다
	 * @param unreadCount 필터와 관계없는 안 읽은 알림 전체 수(헤더 배지)
	 * @param nextCursor 다음 쪽이 있으면 그 커서, 없으면 null
	 */
	record Inbox(LocalDateTime serverTime, List<NotificationView> items, int total, int unreadCount,
			String nextCursor) {
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
