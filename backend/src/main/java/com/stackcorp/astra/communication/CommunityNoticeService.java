package com.stackcorp.astra.communication;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.stackcorp.astra.communication.CommunityNoticeRepository.NoticeInput;
import com.stackcorp.astra.communication.CommunityNoticeRepository.NoticeRow;
import com.stackcorp.astra.communication.CommunityRepository.AudienceCount;
import com.stackcorp.astra.communication.CommunityRepository.Group;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림마당 공지(EP-0154~0156, EP-0196, 화면 30·15, ADR-0035). 대상(공통, 교수, 학생)은 과목이 없으므로 기관 역할로 고른다. 규칙은
 * 자료실과 같다({@link CommunityService#audiences}). 학생은 공통과 학생 대상, 교수·조교는 공통과 교수 대상, 직원과 알림마당 운영자는 모두
 * 본다. 게시 일시가 DB 시계로 지난 공지만 보이고(SC-06), 운영자만 예약 공지를 본다.
 * <p>
 * 쓰기(고정, 대상, 게시 일시, 첨부)는 알림마당 운영자만 하고 모두 감사 기록을 남긴다. 조회는 아무것도 바꾸지 않으며 읽음은 POST 명령이고
 * 조회수는 처음 읽을 때만 오른다(SC-04). 첨부는 courseId 없이 용도 community_notice로 올리고 {@code GET /api/files/{fileId}/content}로
 * 받는다. 알림은 {@link SiteNoticeNotifications}가 맡는다.
 */
@Service
class CommunityNoticeService {

	static final int MAX_PAGE_SIZE = 50;

	static final int MAX_TITLE = 300;

	static final int MAX_BODY = 20_000;

	private static final Set<String> COMMON = Set.of("ALL", "COMMON");

	private final CommunityNoticeRepository notices;

	private final BoardPostRepository posts;

	private final CommunityBoards boards;

	private final CommunityOperators operators;

	private final PostReactions reactions;

	private final BoardFiles files;

	private final UserNames userNames;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final TransactionTemplate transaction;

	private final SiteNoticeNotifications alerts;

	CommunityNoticeService(CommunityNoticeRepository notices, BoardPostRepository posts, CommunityBoards boards,
			CommunityOperators operators, PostReactions reactions, BoardFiles files, UserNames userNames,
			RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit, TransactionTemplate transaction,
			SiteNoticeNotifications alerts) {
		this.notices = notices;
		this.posts = posts;
		this.boards = boards;
		this.operators = operators;
		this.reactions = reactions;
		this.files = files;
		this.userNames = userNames;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
		this.transaction = transaction;
		this.alerts = alerts;
	}

	/**
	 * 공지 목록(EP-0154). 쪽 번호 목록이다(원본 start, display).
	 * @param audience all, common, professor, student
	 */
	@Transactional(readOnly = true)
	NoticePage list(Actor actor, String audience, String query, int page, int size) {
		boolean operator = this.operators.is(actor);
		Set<String> allowed = CommunityService.audiences(actor, operator);
		Set<String> selected = selected(allowed, audience);
		String title = PlainText.query(query);
		if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		LocalDateTime now = this.notices.now();
		long common = 0;
		long professor = 0;
		long student = 0;
		long filtered = 0;
		for (AudienceCount count : this.notices.counts(allowed, operator, now, title)) {
			switch (count.audience()) {
				case "PROFESSOR" -> professor += count.count();
				case "STUDENT" -> student += count.count();
				default -> common += count.count();
			}
			if (selected.contains(count.audience())) {
				filtered += count.count();
			}
		}
		List<NoticeRow> rows = selected.isEmpty() ? List.of()
				: this.notices.page(actor.userId(), selected, operator, now, title,
						(int) Math.min((long) (page - 1) * size, Integer.MAX_VALUE), size);
		return new NoticePage(rows.stream().map((r) -> NoticeItem.of(r, now)).toList(), filtered,
				new CommunityService.MaterialCounts(common + professor + student, common, professor, student), page,
				size, operator);
	}

	/** 공지 상세(EP-0156, EP-0196). 읽음을 남기지 않는다. */
	@Transactional(readOnly = true)
	NoticeView get(Actor actor, long postId) {
		boolean operator = this.operators.is(actor);
		return view(actor, operator, this.notices.now(), postId);
	}

	/** 읽음 표시. 몇 번 보내도 한 행이고 조회수는 처음 한 번만 오른다. 예약 공지는 404다. */
	@Transactional
	ReadState read(Actor actor, long postId) {
		int views = this.notices
			.lockPublished(CommunityService.audiences(actor, this.operators.is(actor)), this.notices.now(), postId)
			.orElseThrow(ApiException::notFound);
		return new ReadState(postId, true, views + (this.reactions.markRead(postId, actor.userId()) ? 1 : 0));
	}

	/** 운영자의 공지 등록. 같은 Idempotency-Key로 다시 보내면 처음 만든 공지를 돌려준다. 운영자가 아니면 404다. */
	NoticeView create(Actor actor, NoticeRequest body, String idempotencyKey) {
		requireOperator(actor);
		if (body.version() != null || body.attachments() != null) {
			throw ApiException.invalid("새 공지에는 version이나 attachments를 줄 수 없습니다. 첨부는 uploadIds로 보냅니다.");
		}
		NewNotice notice = new NewNotice(validated(body),
				BoardFiles.validated(body.uploadIds(), BoardFiles.MAX_MATERIAL_UPLOADS, "uploadIds"));
		this.rateLimiter.check("community-notice-write", actor.userId(), 60, Duration.ofMinutes(10));
		long boardId = this.transaction.execute((tx) -> this.boards.boardFor(CommunityBoards.NOTICE));
		return this.idempotency.execute(actor.userId(), "community_notice.create", idempotencyKey, notice,
				NoticeView.class, () -> {
					LocalDateTime now = this.notices.now();
					NoticeInput input = notice.input();
					Long group = this.files.attach(BoardFiles.COMMUNITY_NOTICE, actor.userId(), null,
							notice.uploadIds());
					LocalDateTime publishedAt = input.publishAt() != null ? input.publishAt() : now;
					long postId = this.notices.insert(boardId, actor.userId(), input, publishedAt, group);
					this.alerts.sync(postId, input.title(), input.audience(), publishedAt);
					this.audit.success(actor.userId(), actor.sessionId(), "community_notice.create", "post", postId,
							null);
					return view(actor, true, now, postId);
				});
	}

	/**
	 * 운영자의 공지 전체 수정. version이 다르면 409 version_conflict. attachments가 있으면 첨부를 그 최종 집합으로 바꾸고 없으면 그대로
	 * 둔다.
	 */
	@Transactional
	NoticeView update(Actor actor, long postId, NoticeRequest body) {
		requireOperator(actor);
		if (body.version() == null || body.version() < 1) {
			throw ApiException.invalid("version이 필요합니다.");
		}
		if (body.uploadIds() != null && !body.uploadIds().isEmpty()) {
			throw ApiException.invalid("수정할 때는 uploadIds 대신 attachments(keep, add)로 첨부를 보냅니다.");
		}
		NoticeInput input = validated(body);
		BoardFiles.Change attachments = BoardFiles.validated(body.attachments(), BoardFiles.MAX_MATERIAL_UPLOADS);
		this.rateLimiter.check("community-notice-write", actor.userId(), 60, Duration.ofMinutes(10));
		LocalDateTime now = this.notices.now();
		if (this.notices.update(postId, body.version(), input, now) == 0) {
			this.notices.lockManaged(postId).orElseThrow(ApiException::notFound);
			throw BoardPostService.versionConflict();
		}
		if (attachments != null) {
			Long group = this.files.change(BoardFiles.COMMUNITY_NOTICE, actor.userId(), null,
					this.posts.attachmentGroup(postId), attachments);
			this.posts.setAttachmentGroup(postId, group);
		}
		NoticeView view = view(actor, true, now, postId);
		this.alerts.sync(postId, view.title(), input.audience(), view.publishedAt());
		this.audit.success(actor.userId(), actor.sessionId(), "community_notice.update", "post", postId, null);
		return view;
	}

	/** 운영자의 공지 삭제. 글은 소프트 삭제하고 첨부는 끊는다. */
	@Transactional
	void delete(Actor actor, long postId) {
		requireOperator(actor);
		Group group = this.notices.lockManaged(postId).orElseThrow(ApiException::notFound);
		this.notices.delete(postId);
		this.files.release(group.attachmentGroupId());
		this.alerts.withdraw(postId);
		this.audit.success(actor.userId(), actor.sessionId(), "community_notice.delete", "post", postId, null);
	}

	private void requireOperator(Actor actor) {
		if (!this.operators.is(actor)) {
			throw ApiException.notFound();
		}
	}

	private NoticeView view(Actor actor, boolean operator, LocalDateTime now, long postId) {
		NoticeRow row = this.notices
			.find(actor.userId(), CommunityService.audiences(actor, operator), operator, now, postId)
			.orElseThrow(ApiException::notFound);
		String author = this.userNames.namesOf(List.of(row.authorId())).get(row.authorId());
		return NoticeView.of(row, author, this.files.of(row.attachmentGroupId()), now, operator);
	}

	private static NoticeInput validated(NoticeRequest body) {
		return new NoticeInput(PlainText.title(body.title(), MAX_TITLE), PlainText.optionalBody(body.body(), MAX_BODY),
				storedAudience(body.audience()), Boolean.TRUE.equals(body.pinned()), body.publishAt());
	}

	private static Set<String> selected(Set<String> allowed, String audience) {
		Set<String> wanted = switch (audience == null || audience.isBlank() ? "all" : audience) {
			case "all" -> allowed;
			case "common" -> COMMON;
			case "professor" -> Set.of("PROFESSOR");
			case "student" -> Set.of("STUDENT");
			default -> throw ApiException.invalid("audience는 all, common, professor, student 중 하나여야 합니다.");
		};
		return wanted.stream().filter(allowed::contains).collect(Collectors.toUnmodifiableSet());
	}

	private static String storedAudience(String audience) {
		return switch (audience == null ? "" : audience) {
			case "common" -> "COMMON";
			case "professor" -> "PROFESSOR";
			case "student" -> "STUDENT";
			default -> throw ApiException.invalid("audience는 common, professor, student 중 하나여야 합니다.");
		};
	}

	static String audienceName(String stored) {
		return COMMON.contains(stored) ? "common" : stored.toLowerCase(Locale.ROOT);
	}

	private static boolean isPublished(LocalDateTime publishedAt, LocalDateTime now) {
		return publishedAt != null && !publishedAt.isAfter(now);
	}

	/**
	 * 운영자의 공지 작성·수정. 작성자는 받지 않는다(SC-02). 제목과 본문은 일반 텍스트다.
	 * @param audience common, professor, student
	 * @param pinned 고정 공지. 목록 맨 위에 오고, 읽지 않았으면 메인 화면의 noticesForMe에 나온다
	 * @param publishAt 게시 일시. 미래면 예약 공지. 생략하면 지금(수정할 때 이미 게시된 공지면 원래 게시 일시)
	 * @param uploadIds 새 공지에만. 본인이 courseId 없이 용도 community_notice로 올린 업로드(10개까지)
	 * @param attachments 수정할 때만. 첨부의 최종 집합. 없으면 첨부를 바꾸지 않는다
	 * @param version 수정할 때만. 마지막으로 읽은 공지의 version
	 */
	record NoticeRequest(String title, String body, String audience, Boolean pinned, LocalDateTime publishAt,
			List<Long> uploadIds, BoardFiles.AttachmentSet attachments, Integer version) {
	}

	/** 멱등 지문을 만드는 검사된 입력. */
	record NewNotice(NoticeInput input, List<Long> uploadIds) {
	}

	/**
	 * @param audience common, professor, student
	 * @param published 지금 게시된 공지인지. 운영자가 보는 예약 공지면 false
	 */
	record NoticeItem(long noticeId, String title, String excerpt, String audience, boolean pinned,
			LocalDateTime publishedAt, boolean published, int viewCount, boolean read) {

		static NoticeItem of(NoticeRow row, LocalDateTime now) {
			return new NoticeItem(row.noticeId(), row.title(), row.excerpt(), audienceName(row.audience()), row.pinned(),
					row.publishedAt(), isPublished(row.publishedAt(), now), row.viewCount(), row.read());
		}

	}

	/**
	 * @param authorName 공지를 쓴 운영자(원본 REG_USER_NM)
	 * @param attachments 내려받기는 {@code GET /api/files/{fileId}/content}
	 * @param canManage 알림마당 운영자면 true
	 */
	record NoticeView(long noticeId, String title, String body, String audience, boolean pinned,
			LocalDateTime publishedAt, boolean published, String authorName, int viewCount, boolean read,
			List<AttachedFile> attachments, int version, LocalDateTime createdAt, LocalDateTime updatedAt,
			boolean canManage) {

		static NoticeView of(NoticeRow row, String authorName, List<AttachedFile> attachments, LocalDateTime now,
				boolean operator) {
			return new NoticeView(row.noticeId(), row.title(), row.body(), audienceName(row.audience()), row.pinned(),
					row.publishedAt(), isPublished(row.publishedAt(), now), authorName, row.viewCount(), row.read(),
					attachments, row.version(), row.createdAt(), row.updatedAt(), operator);
		}

	}

	/**
	 * @param total 지금 대상 필터와 검색어에 맞는 공지 수
	 * @param counts 대상 필터 탭의 수. 볼 수 있는 대상만 센다
	 * @param canManage 알림마당 운영자면 true. 등록·수정·삭제와 예약 공지 보기를 할 수 있다
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record NoticePage(List<NoticeItem> items, long total, CommunityService.MaterialCounts counts, int page,
			int size, boolean canManage) {
	}

	/** 공지 읽음 표시의 결과. 목록과 상세처럼 noticeId로 준다. */
	record ReadState(long noticeId, boolean read, int viewCount) {
	}

}
