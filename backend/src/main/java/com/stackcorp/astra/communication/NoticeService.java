package com.stackcorp.astra.communication;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Pattern;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.communication.NoticeRepository.NoticeInput;
import com.stackcorp.astra.communication.NoticeRepository.NoticeItemRow;
import com.stackcorp.astra.communication.NoticeRepository.NoticeRow;
import com.stackcorp.astra.communication.NoticeRepository.Replay;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 강의실 공지의 권한과 명령. 과목은 경로로 받고 공지가 그 과목의 공지 게시판에 있는지 함께 확인한다(SC-03).
 * 담당자는 임시·예약 공지를 포함해 모두 보고 쓰며, 학생은 게시 일시가 지난 공지만 본다. 시각은 DB 시계로 정한다(SC-06).
 * 과목 구성원이 아니거나 볼 수 없는 공지, 학생의 쓰기 요청은 존재를 드러내지 않도록 404로 응답한다.
 * 조회는 아무것도 바꾸지 않는다. 읽음과 확인은 별도 명령이고 (공지, 사용자) unique 행이라 몇 번 보내도 결과가 같다(SC-04, SC-05, CHG-08).
 */
@Service
class NoticeService {

	static final int MAX_TITLE = 300;

	static final int MAX_BODY = 20_000;

	static final int MAX_QUERY = 100;

	static final int MAX_PAGE_SIZE = 50;

	private static final int EXCERPT = 150;

	private static final Pattern CLIENT_KEY = Pattern.compile("[A-Za-z0-9._-]{1,64}");

	private final NoticeRepository notices;

	private final CourseMemberships memberships;

	private final UserNames userNames;

	private final RateLimiter rateLimiter;

	private final TransactionTemplate transaction;

	private final NoticeNotifications alerts;

	NoticeService(NoticeRepository notices, CourseMemberships memberships, UserNames userNames, RateLimiter rateLimiter,
			TransactionTemplate transaction, NoticeNotifications alerts) {
		this.notices = notices;
		this.memberships = memberships;
		this.userNames = userNames;
		this.rateLimiter = rateLimiter;
		this.transaction = transaction;
		this.alerts = alerts;
	}

	@Transactional(readOnly = true)
	NoticePage list(long userId, long courseId, String query, int page, int size) {
		boolean manager = role(userId, courseId) == CourseRole.INSTRUCTOR;
		String titleQuery = validatedQuery(query);
		if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		LocalDateTime now = this.notices.now();
		long total = this.notices.count(courseId, manager, now, titleQuery);
		List<NoticeItem> items = this.notices
			.page(courseId, userId, manager, now, titleQuery, (int) Math.min((long) (page - 1) * size, Integer.MAX_VALUE),
					size)
			.stream()
			.map((row) -> NoticeItem.of(row, now))
			.toList();
		return new NoticePage(items, total, page, size);
	}

	@Transactional(readOnly = true)
	NoticeView get(long userId, long courseId, long noticeId) {
		boolean manager = role(userId, courseId) == CourseRole.INSTRUCTOR;
		LocalDateTime now = this.notices.now();
		return view(visible(userId, courseId, noticeId, manager, now), now);
	}

	/**
	 * @param clientKey Idempotency-Key. 같은 key로 다시 보내면 새로 만들지 않고 처음 만든 공지를 돌려준다(CHG-08)
	 */
	Created create(long userId, long courseId, NoticeRequest body, String clientKey) {
		requireManager(userId, courseId);
		NoticeInput input = validated(body);
		if (body.version() != null) {
			throw ApiException.invalid("새 공지에는 version을 줄 수 없습니다.");
		}
		if (clientKey != null && !CLIENT_KEY.matcher(clientKey).matches()) {
			throw ApiException.invalid("Idempotency-Key 형식이 올바르지 않습니다.");
		}
		if (clientKey != null) {
			var replay = this.notices.byClientKey(courseId, clientKey);
			if (replay.isPresent()) {
				return replayed(userId, courseId, replay.get());
			}
		}
		this.rateLimiter.check("notice-write", userId, 60, Duration.ofMinutes(10));
		try {
			// 게시판은 먼저 따로 commit한다. 같은 transaction에서 만들면, 다른 요청이 먼저 만든 게시판 행이 이 transaction의 snapshot에
			// 보이지 않아 방금 쓴 공지를 다시 읽지 못한다.
			long boardId = this.transaction.execute((tx) -> this.notices.boardFor(courseId));
			return this.transaction.execute((tx) -> {
				LocalDateTime now = this.notices.now();
				long noticeId = this.notices.insert(boardId, userId, input, now, clientKey);
				NoticeRow row = visible(userId, courseId, noticeId, true, now);
				this.alerts.sync(courseId, noticeId, row.title(), row.publishedAt());
				return new Created(view(row, now), true);
			});
		}
		catch (DuplicateKeyException ex) {
			// 같은 key의 요청이 동시에 들어왔다. 먼저 끝난 쪽의 공지를 돌려준다.
			if (clientKey == null) {
				throw ex;
			}
			return replayed(userId, courseId, this.notices.byClientKey(courseId, clientKey).orElseThrow(() -> ex));
		}
	}

	@Transactional
	NoticeView update(long userId, long courseId, long noticeId, NoticeRequest body) {
		requireManager(userId, courseId);
		NoticeInput input = validated(body);
		if (body.version() == null || body.version() < 1) {
			throw ApiException.invalid("version이 필요합니다.");
		}
		this.rateLimiter.check("notice-write", userId, 60, Duration.ofMinutes(10));
		LocalDateTime now = this.notices.now();
		if (this.notices.update(courseId, noticeId, input, body.version(), now) == 0) {
			if (this.notices.exists(courseId, noticeId)) {
				throw new ApiException(HttpStatus.CONFLICT, "version_conflict",
						"다른 사용자가 먼저 수정했습니다. 최신 내용을 다시 불러온 뒤 수정해 주세요.");
			}
			throw ApiException.notFound();
		}
		NoticeRow row = visible(userId, courseId, noticeId, true, now);
		this.alerts.sync(courseId, noticeId, row.title(), row.publishedAt());
		return view(row, now);
	}

	@Transactional
	void delete(long userId, long courseId, long noticeId) {
		requireManager(userId, courseId);
		if (this.notices.delete(courseId, noticeId) == 0) {
			throw ApiException.notFound();
		}
		this.alerts.withdraw(noticeId);
	}

	@Transactional
	NoticeState markRead(long userId, long courseId, long noticeId) {
		NoticeRow row = visibleTo(userId, courseId, noticeId);
		this.notices.markRead(noticeId, userId);
		return new NoticeState(noticeId, true, row.confirmed());
	}

	@Transactional
	NoticeState confirm(long userId, long courseId, long noticeId) {
		NoticeRow row = visibleTo(userId, courseId, noticeId);
		this.notices.confirm(noticeId, userId);
		return new NoticeState(noticeId, row.read(), true);
	}

	private NoticeRow visibleTo(long userId, long courseId, long noticeId) {
		boolean manager = role(userId, courseId) == CourseRole.INSTRUCTOR;
		return visible(userId, courseId, noticeId, manager, this.notices.now());
	}

	private NoticeRow visible(long userId, long courseId, long noticeId, boolean manager, LocalDateTime now) {
		return this.notices.find(courseId, noticeId, userId, manager, now).orElseThrow(ApiException::notFound);
	}

	private Created replayed(long userId, long courseId, Replay replay) {
		if (replay.authorId() != userId || replay.deleted()) {
			throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "같은 Idempotency-Key를 다른 요청에 썼습니다.");
		}
		LocalDateTime now = this.notices.now();
		return new Created(view(visible(userId, courseId, replay.noticeId(), true, now), now), false);
	}

	private NoticeView view(NoticeRow row, LocalDateTime now) {
		String author = this.userNames.namesOf(List.of(row.authorId())).get(row.authorId());
		return NoticeView.of(row, author, now);
	}

	private CourseRole role(long userId, long courseId) {
		return this.memberships.roleOf(userId, courseId).orElseThrow(ApiException::notFound);
	}

	private void requireManager(long userId, long courseId) {
		if (role(userId, courseId) != CourseRole.INSTRUCTOR) {
			throw ApiException.notFound();
		}
	}

	private static String validatedQuery(String query) {
		if (query == null || query.isBlank()) {
			return null;
		}
		String q = query.strip();
		if (q.length() > MAX_QUERY || hasControl(q, false)) {
			throw ApiException.invalid("q는 " + MAX_QUERY + "자 이하여야 합니다.");
		}
		return q;
	}

	private static NoticeInput validated(NoticeRequest body) {
		String title = body.title() == null ? "" : body.title().strip();
		if (title.isEmpty() || title.length() > MAX_TITLE || hasControl(title, false)) {
			throw ApiException.invalid("title은 1자 이상 " + MAX_TITLE + "자 이하여야 합니다.");
		}
		String text = body.body() == null || body.body().isBlank() ? null : body.body();
		if (text != null && (text.length() > MAX_BODY || hasControl(text, true))) {
			throw ApiException.invalid("body는 " + MAX_BODY + "자 이하여야 합니다.");
		}
		boolean published = body.published() == null || body.published();
		if (!published && body.publishAt() != null) {
			throw ApiException.invalid("임시 공지에는 publishAt을 줄 수 없습니다.");
		}
		return new NoticeInput(title, text, excerpt(text), Boolean.TRUE.equals(body.pinned()), published,
				body.publishAt());
	}

	/** 목록 미리보기. 줄바꿈을 공백으로 바꾼 본문 앞 150자다. 서로게이트 쌍을 자르지 않는다. */
	private static String excerpt(String text) {
		if (text == null) {
			return null;
		}
		String flat = text.replaceAll("\\s+", " ").strip();
		if (flat.codePointCount(0, flat.length()) <= EXCERPT) {
			return flat;
		}
		return flat.substring(0, flat.offsetByCodePoints(0, EXCERPT));
	}

	private static boolean hasControl(String text, boolean allowLineBreaks) {
		return text.codePoints()
			.anyMatch((cp) -> Character.isISOControl(cp) && !(allowLineBreaks && (cp == '\n' || cp == '\r' || cp == '\t')));
	}

	/**
	 * 요청 본문. 작성자와 과목은 받지 않는다. session과 경로에서 정한다(SC-02). 본문은 일반 텍스트다.
	 * @param published false면 임시 공지. 생략하면 true
	 * @param publishAt 게시 일시. 미래면 예약 공지. 생략하면 지금(수정할 때 이미 게시된 공지면 원래 게시 일시)
	 * @param version 수정할 때만. 마지막으로 읽은 공지의 version
	 */
	record NoticeRequest(String title, String body, Boolean pinned, Boolean published, LocalDateTime publishAt,
			Integer version) {
	}

	record Created(NoticeView notice, boolean isNew) {
	}

	/**
	 * @param published 학생에게 지금 보이는지. 게시 일시가 없거나 미래면 false
	 * @param readCount 담당자에게만. 작성자를 뺀 열람자 수. 학생에게는 null
	 * @param confirmCount 담당자에게만. 작성자를 뺀 확인자 수. 학생에게는 null
	 */
	record NoticeView(long noticeId, String title, String body, boolean pinned, LocalDateTime publishedAt,
			boolean published, String authorName, int viewCount, LocalDateTime createdAt, LocalDateTime updatedAt,
			int version, boolean read, boolean confirmed, Integer readCount, Integer confirmCount) {

		static NoticeView of(NoticeRow row, String authorName, LocalDateTime now) {
			return new NoticeView(row.noticeId(), row.title(), row.body(), row.pinned(), row.publishedAt(),
					visibleAt(row.publishedAt(), now), authorName, row.viewCount(), row.createdAt(), row.updatedAt(),
					row.version(), row.read(), row.confirmed(), row.readCount(), row.confirmCount());
		}

	}

	record NoticeItem(long noticeId, String title, String excerpt, boolean pinned, LocalDateTime publishedAt,
			boolean published, int viewCount, boolean read, boolean confirmed, Integer readCount, Integer confirmCount) {

		static NoticeItem of(NoticeItemRow row, LocalDateTime now) {
			return new NoticeItem(row.noticeId(), row.title(), row.excerpt(), row.pinned(), row.publishedAt(),
					visibleAt(row.publishedAt(), now), row.viewCount(), row.read(), row.confirmed(), row.readCount(),
					row.confirmCount());
		}

	}

	record NoticePage(List<NoticeItem> items, long total, int page, int size) {
	}

	record NoticeState(long noticeId, boolean read, boolean confirmed) {
	}

	private static boolean visibleAt(LocalDateTime publishedAt, LocalDateTime now) {
		return publishedAt != null && !publishedAt.isAfter(now);
	}

}
