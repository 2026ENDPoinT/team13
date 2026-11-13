package com.stackcorp.astra.communication;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.stackcorp.astra.catalog.api.CourseMemberships;
import com.stackcorp.astra.catalog.api.CourseMemberships.CourseRole;
import com.stackcorp.astra.catalog.api.SchoolCourseNotices;
import com.stackcorp.astra.communication.Authors.Author;
import com.stackcorp.astra.communication.NoticeRepository.NoticeInput;
import com.stackcorp.astra.communication.NoticeRepository.NoticeItemRow;
import com.stackcorp.astra.communication.NoticeRepository.NoticeRow;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 강의실 공지의 권한과 명령. 과목은 경로로 받고 공지가 그 과목의 공지 게시판에 있는지 함께 확인한다(SC-03).
 * 담당자는 임시·예약 공지를 포함해 모두 보고 쓰며, 학생은 게시 일시가 지난 공지만 본다. 시각은 DB 시계로 정한다(SC-06).
 * 과목 구성원이 아니거나 볼 수 없는 공지, 학생의 쓰기 요청은 존재를 드러내지 않도록 404로 응답한다.
 * 조회는 아무것도 바꾸지 않는다. 읽음과 확인은 별도 명령이고 (공지, 사용자) unique 행이라 몇 번 보내도 결과가 같다(SC-04, SC-05, CHG-08).
 * 등록, 수정, 삭제는 감사 기록을 남긴다. 제목과 본문은 남기지 않는다(SC-09).
 */
@Service
class NoticeService {

	static final int MAX_TITLE = 300;

	static final int MAX_BODY = 20_000;

	static final int MAX_PAGE_SIZE = 50;

	static final String CREATE = "notice.create";

	private final NoticeRepository notices;

	private final CourseBoards boards;

	private final PostReactions reactions;

	private final CourseMemberships memberships;

	private final Authors authors;

	private final RateLimiter rateLimiter;

	private final TransactionTemplate transaction;

	private final NoticeNotifications alerts;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final SchoolCourseNotices schoolNotices;

	NoticeService(NoticeRepository notices, CourseBoards boards, PostReactions reactions, CourseMemberships memberships,
			Authors authors, RateLimiter rateLimiter, TransactionTemplate transaction, NoticeNotifications alerts,
			Idempotency idempotency, AuditLog audit, SchoolCourseNotices schoolNotices) {
		this.notices = notices;
		this.boards = boards;
		this.reactions = reactions;
		this.memberships = memberships;
		this.authors = authors;
		this.rateLimiter = rateLimiter;
		this.transaction = transaction;
		this.alerts = alerts;
		this.idempotency = idempotency;
		this.audit = audit;
		this.schoolNotices = schoolNotices;
	}

	/**
	 * 학생에게는 학교 강의실 공지({@link SchoolCourseNotices}, ADR-0043)를 서비스 공지 뒤에 학교 목록 순서로 붙인다. 쪽은 두 목록을 이은
	 * 순서로 자르고 total은 둘의 합이다. 학교 목록을 읽을 수 있어 transaction 밖에서 돈다(학교 요청 동안 DB 연결을 잡지 않는다).
	 */
	NoticePage list(Actor actor, long courseId, String query, int page, int size) {
		long userId = actor.userId();
		boolean manager = role(userId, courseId) == CourseRole.INSTRUCTOR;
		String titleQuery = PlainText.query(query);
		if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		List<SchoolCourseNotices.SchoolNoticeItem> school = manager ? List.of()
				: this.schoolNotices.list(actor, courseId)
					.stream()
					.filter((n) -> titleQuery == null
							|| n.title().toLowerCase(Locale.ROOT).contains(titleQuery.toLowerCase(Locale.ROOT)))
					.toList();
		LocalDateTime now = this.notices.now();
		long nativeTotal = this.notices.count(courseId, manager, now, titleQuery);
		long offset = (long) (page - 1) * size;
		List<NoticeListItem> items = new ArrayList<>(this.notices
			.page(courseId, userId, manager, now, titleQuery, (int) Math.min(offset, Integer.MAX_VALUE), size)
			.stream()
			.map((row) -> NoticeItem.of(row, now))
			.toList());
		long schoolFrom = Math.max(0, offset - nativeTotal);
		for (long i = schoolFrom; i < school.size() && items.size() < size; i++) {
			items.add(SchoolNoticeItem.of(school.get((int) i)));
		}
		return new NoticePage(items, nativeTotal + school.size(), page, size);
	}

	@Transactional(readOnly = true)
	NoticeView get(long userId, long courseId, long noticeId) {
		boolean manager = role(userId, courseId) == CourseRole.INSTRUCTOR;
		LocalDateTime now = this.notices.now();
		return view(userId, visible(userId, courseId, noticeId, manager, now), now);
	}

	/**
	 * 공지를 만든다. Idempotency-Key는 공용 멱등 원장(ADR-0012)이 받는다. 같은 key에 같은 요청이면 처음 응답을 돌려주고 새로 만들지
	 * 않는다. 같은 key에 다른 본문이나 다른 과목이면 409 {@code idempotency_conflict}이고 아무것도 만들지 않는다(CHG-08).
	 */
	NoticeView create(Actor actor, long courseId, NoticeRequest body, String idempotencyKey) {
		long userId = actor.userId();
		requireManager(userId, courseId);
		NoticeInput input = validated(body);
		if (body.version() != null) {
			throw ApiException.invalid("새 공지에는 version을 줄 수 없습니다.");
		}
		this.rateLimiter.check("notice-write", userId, 60, Duration.ofMinutes(10));
		// 게시판은 먼저 따로 commit한다. 같은 transaction에서 만들면, 다른 요청이 먼저 만든 게시판 행이 이 transaction의 snapshot에
		// 보이지 않아 방금 쓴 공지를 다시 읽지 못한다.
		long boardId = this.transaction.execute((tx) -> this.boards.boardFor(courseId, BoardKind.NOTICE));
		return this.idempotency.execute(userId, CREATE, idempotencyKey, List.of(courseId, input), NoticeView.class,
				() -> {
					LocalDateTime now = this.notices.now();
					long noticeId = this.notices.insert(boardId, userId, input, now);
					NoticeRow row = visible(userId, courseId, noticeId, true, now);
					this.alerts.sync(courseId, noticeId, row.title(), row.publishedAt());
					this.audit.success(userId, actor.sessionId(), CREATE, "post", noticeId, courseId);
					return view(userId, row, now);
				});
	}

	@Transactional
	NoticeView update(Actor actor, long courseId, long noticeId, NoticeRequest body) {
		long userId = actor.userId();
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
		this.audit.success(userId, actor.sessionId(), "notice.update", "post", noticeId, courseId);
		return view(userId, row, now);
	}

	@Transactional
	void delete(Actor actor, long courseId, long noticeId) {
		requireManager(actor.userId(), courseId);
		if (this.notices.delete(courseId, noticeId) == 0) {
			throw ApiException.notFound();
		}
		this.alerts.withdraw(noticeId);
		this.audit.success(actor.userId(), actor.sessionId(), "notice.delete", "post", noticeId, courseId);
	}

	@Transactional
	NoticeState markRead(long userId, long courseId, long noticeId) {
		NoticeRow row = visibleTo(userId, courseId, noticeId);
		this.reactions.markRead(noticeId, userId);
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

	private NoticeView view(long viewerId, NoticeRow row, LocalDateTime now) {
		return NoticeView.of(row, this.authors.one(viewerId, row.authorId()), now);
	}

	private CourseRole role(long userId, long courseId) {
		return this.memberships.roleOf(userId, courseId).orElseThrow(ApiException::notFound);
	}

	private void requireManager(long userId, long courseId) {
		if (role(userId, courseId) != CourseRole.INSTRUCTOR) {
			throw ApiException.notFound();
		}
	}

	private static NoticeInput validated(NoticeRequest body) {
		String title = PlainText.title(body.title(), MAX_TITLE);
		String text = PlainText.optionalBody(body.body(), MAX_BODY);
		boolean published = body.published() == null || body.published();
		if (!published && body.publishAt() != null) {
			throw ApiException.invalid("임시 공지에는 publishAt을 줄 수 없습니다.");
		}
		return new NoticeInput(title, text, PlainText.excerpt(text), Boolean.TRUE.equals(body.pinned()), published,
				body.publishAt());
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

	/**
	 * @param published 학생에게 지금 보이는지. 게시 일시가 없거나 미래면 false
	 * @param authorId 작성자 사용자 ID. 계정이 없으면 null. 아바타는 {@code GET /api/users/{authorId}/avatar}
	 * @param authorHasPhoto 요청한 사람이 작성자의 아바타 사진을 받을 수 있으면 true
	 * @param readCount 담당자에게만. 작성자를 뺀 열람자 수. 학생에게는 null
	 * @param confirmCount 담당자에게만. 작성자를 뺀 확인자 수. 학생에게는 null
	 */
	record NoticeView(long noticeId, String title, String body, boolean pinned, LocalDateTime publishedAt,
			boolean published, Long authorId, String authorName, boolean authorHasPhoto, int viewCount,
			LocalDateTime createdAt, LocalDateTime updatedAt, int version, boolean read, boolean confirmed,
			Integer readCount, Integer confirmCount) {

		static NoticeView of(NoticeRow row, Author author, LocalDateTime now) {
			return new NoticeView(row.noticeId(), row.title(), row.body(), row.pinned(), row.publishedAt(),
					visibleAt(row.publishedAt(), now), author.id(), author.name(), author.hasPhoto(), row.viewCount(),
					row.createdAt(), row.updatedAt(), row.version(), row.read(), row.confirmed(), row.readCount(),
					row.confirmCount());
		}

	}

	/** 공지 목록의 항목. 서비스 공지와 학교 공지(학생에게만, 서비스 공지 뒤)다. */
	sealed interface NoticeListItem permits NoticeItem, SchoolNoticeItem {
	}

	record NoticeItem(long noticeId, String title, String excerpt, boolean pinned, LocalDateTime publishedAt,
			boolean published, int viewCount, boolean read, boolean confirmed, Integer readCount,
			Integer confirmCount) implements NoticeListItem {

		static NoticeItem of(NoticeItemRow row, LocalDateTime now) {
			return new NoticeItem(row.noticeId(), row.title(), row.excerpt(), row.pinned(), row.publishedAt(),
					visibleAt(row.publishedAt(), now), row.viewCount(), row.read(), row.confirmed(), row.readCount(),
					row.confirmCount());
		}

	}

	/**
	 * 학교 강의실 공지(ADR-0043). 서비스 공지({@link NoticeItem})와 같은 이름의 값에 {@code origin}, {@code expired},
	 * {@code schoolUrl}을 더한다. 상세는 {@code GET /api/courses/{courseId}/notices/school-<id>}이고 읽음·확인 명령은 없다.
	 * @param noticeId {@code "school-<id>"}
	 * @param read 이 학생이 학교에서 읽었는지(학교 목록의 안 읽음 표시. 이 학생의 학교 목록을 읽은 적이 없으면 true)
	 * @param viewCount 학교 목록의 조회수. 모르면 0
	 */
	record SchoolNoticeItem(String noticeId, String origin, String title, String excerpt, boolean pinned,
			LocalDateTime publishedAt, boolean published, int viewCount, boolean read, boolean confirmed,
			Integer readCount, Integer confirmCount, boolean expired, String schoolUrl) implements NoticeListItem {

		static SchoolNoticeItem of(SchoolCourseNotices.SchoolNoticeItem notice) {
			return new SchoolNoticeItem("school-" + notice.schoolNoticeId(), "school", notice.title(),
					notice.excerpt(), false, notice.postedAt(), true,
					notice.viewCount() == null ? 0 : notice.viewCount(), notice.read(), false, null, null,
					notice.expired(), notice.schoolUrl());
		}

	}

	record NoticePage(List<NoticeListItem> items, long total, int page, int size) {
	}

	record NoticeState(long noticeId, boolean read, boolean confirmed) {
	}

	private static boolean visibleAt(LocalDateTime publishedAt, LocalDateTime now) {
		return publishedAt != null && !publishedAt.isAfter(now);
	}

}
