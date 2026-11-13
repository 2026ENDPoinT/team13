package com.stackcorp.astra.communication;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.catalog.api.Notifications.DirectNotification;
import com.stackcorp.astra.communication.BoardPostRepository.NewText;
import com.stackcorp.astra.communication.CommunityRepository.AudienceCount;
import com.stackcorp.astra.communication.CommunityRepository.Group;
import com.stackcorp.astra.communication.CommunityRepository.InquiryCounts;
import com.stackcorp.astra.communication.CommunityRepository.InquiryRow;
import com.stackcorp.astra.communication.CommunityRepository.MaterialRow;
import com.stackcorp.astra.communication.CommunityRepository.ReplyRow;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.identity.api.UserRole;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 알림마당 자료실과 1:1 문의(EP-0151~0153, EP-0158~0162, 화면 31·32·34, ADR-0035).
 * <ul>
 * <li>자료실의 대상(공통, 교수, 학생)은 과목이 없으므로 session 사용자의 기관 역할로 고른다. 학생은 공통과 학생 대상, 교수·조교는 공통과 교수
 * 대상, 직원은 모두 본다. 필터(audience)는 그 안에서만 좁힌다(EP-0151 SCH_TARGET). 알림마당 운영자는 모든 대상을 보고 자료를 올리고
 * 고치고 지운다.</li>
 * <li>1:1 문의는 로그인한 사용자가 쓰고, 쓴 사람과 알림마당 운영자만 본다. 원본의 MY_QNA 같은 값은 받지 않는다(EP-0160, COMM-09). 답은
 * 운영자가 단다. 남의 문의는 404다. 원본 화면에 문의 수정·삭제가 없어 만들지 않았다.</li>
 * </ul>
 * 운영자 판정은 요청마다 {@link CommunityOperators}로 하고, 운영자의 명령은 모두 감사 기록을 남긴다. 첨부는 과목 밖 용도로 올리고
 * {@code GET /api/files/{fileId}/content}로 내려받는다. 권한은 {@link CommunityAttachmentAccess}가 글의 권한으로 판정한다.
 */
@Service
class CommunityService {

	static final int MAX_PAGE_SIZE = 50;

	static final int MAX_TITLE = 300;

	static final int MAX_BODY = 20_000;

	private static final int MAX_REPLIES = 100;

	private static final String REPLY_PREFIX = "re : ";

	private static final Set<String> COMMON = Set.of("ALL", "COMMON");

	private static final Set<String> EVERY_AUDIENCE = Set.of("ALL", "COMMON", "PROFESSOR", "STUDENT");

	private final CommunityRepository community;

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

	private final Notifications notifications;

	CommunityService(CommunityRepository community, BoardPostRepository posts, CommunityBoards boards,
			CommunityOperators operators, PostReactions reactions, BoardFiles files, UserNames userNames,
			RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit, TransactionTemplate transaction,
			Notifications notifications) {
		this.community = community;
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
		this.notifications = notifications;
	}

	// ---- 자료실

	/**
	 * 자료 목록. 최근 자료 먼저다.
	 * @param audience all, common, professor, student
	 */
	@Transactional(readOnly = true)
	MaterialPage materials(Actor actor, String audience, String query, Long cursor, int size) {
		boolean operator = this.operators.is(actor);
		Set<String> allowed = audiences(actor, operator);
		Set<String> selected = selected(allowed, audience);
		String title = PlainText.query(query);
		requirePage(cursor, size);
		long common = 0;
		long professor = 0;
		long student = 0;
		for (AudienceCount count : this.community.materialCounts(allowed, title)) {
			switch (count.audience()) {
				case "PROFESSOR" -> professor += count.count();
				case "STUDENT" -> student += count.count();
				default -> common += count.count();
			}
		}
		List<MaterialRow> rows = selected.isEmpty() ? List.of()
				: this.community.materials(actor.userId(), selected, title, cursor, size + 1);
		Long next = rows.size() > size ? rows.get(size - 1).postId() : null;
		return new MaterialPage(rows.subList(0, Math.min(size, rows.size())).stream().map(MaterialItem::of).toList(),
				new MaterialCounts(common + professor + student, common, professor, student), next, operator);
	}

	@Transactional(readOnly = true)
	MaterialView material(Actor actor, long postId) {
		boolean operator = this.operators.is(actor);
		return materialView(actor, operator, postId);
	}

	/** 읽음으로 표시한다. 몇 번 보내도 한 행이고 조회수는 처음 한 번만 오른다(EP-0153). */
	@Transactional
	BoardPostService.ReadState readMaterial(Actor actor, long postId) {
		int views = this.community.lockMaterial(audiences(actor, this.operators.is(actor)), postId)
			.orElseThrow(ApiException::notFound);
		return new BoardPostService.ReadState(postId, true,
				views + (this.reactions.markRead(postId, actor.userId()) ? 1 : 0));
	}

	/**
	 * 운영자가 자료를 올린다. 지금 게시한다. 같은 Idempotency-Key로 다시 보내면 처음 만든 자료를 돌려준다. 운영자가 아니면 404다.
	 */
	MaterialView createMaterial(Actor actor, MaterialRequest body, String idempotencyKey) {
		requireOperator(actor);
		if (body.version() != null || body.attachments() != null) {
			throw ApiException.invalid("새 자료에는 version이나 attachments를 줄 수 없습니다. 첨부는 uploadIds로 보냅니다.");
		}
		NewMaterial material = new NewMaterial(PlainText.title(body.title(), MAX_TITLE),
				PlainText.optionalBody(body.body(), MAX_BODY), storedAudience(body.audience()),
				BoardFiles.validated(body.uploadIds(), BoardFiles.MAX_MATERIAL_UPLOADS, "uploadIds"));
		this.rateLimiter.check("community-material-create", actor.userId(), 30, Duration.ofMinutes(10));
		long boardId = this.transaction.execute((tx) -> this.boards.boardFor(CommunityBoards.MATERIAL));
		return this.idempotency.execute(actor.userId(), "community_material.create", idempotencyKey, material,
				MaterialView.class, () -> {
					Long group = this.files.attach(BoardFiles.COMMUNITY_MATERIAL, actor.userId(), null,
							material.uploadIds());
					long postId = this.community.insertMaterial(boardId, actor.userId(), material.title(),
							material.body(), material.audience(), group);
					this.audit.success(actor.userId(), actor.sessionId(), "community_material.create", "post", postId,
							null);
					return materialView(actor, true, postId);
				});
	}

	/**
	 * 운영자의 자료 전체 수정. version이 다르면 409 version_conflict. attachments가 있으면 첨부를 그 최종 집합으로 바꾸고 없으면 그대로
	 * 둔다. 빠진 파일은 바로 누구도 읽지 못한다.
	 */
	@Transactional
	MaterialView updateMaterial(Actor actor, long postId, MaterialRequest body) {
		requireOperator(actor);
		if (body.version() == null || body.version() < 1) {
			throw ApiException.invalid("version이 필요합니다.");
		}
		if (body.uploadIds() != null && !body.uploadIds().isEmpty()) {
			throw ApiException.invalid("수정할 때는 uploadIds 대신 attachments(keep, add)로 첨부를 보냅니다.");
		}
		String title = PlainText.title(body.title(), MAX_TITLE);
		String text = PlainText.optionalBody(body.body(), MAX_BODY);
		String audience = storedAudience(body.audience());
		BoardFiles.Change attachments = BoardFiles.validated(body.attachments(), BoardFiles.MAX_MATERIAL_UPLOADS);
		this.rateLimiter.check("community-material-update", actor.userId(), 60, Duration.ofMinutes(10));
		if (this.community.updateMaterial(postId, body.version(), title, text, audience) == 0) {
			this.community.lockManagedMaterial(postId).orElseThrow(ApiException::notFound);
			throw BoardPostService.versionConflict();
		}
		if (attachments != null) {
			Long group = this.files.change(BoardFiles.COMMUNITY_MATERIAL, actor.userId(), null,
					this.posts.attachmentGroup(postId), attachments);
			this.posts.setAttachmentGroup(postId, group);
		}
		this.audit.success(actor.userId(), actor.sessionId(), "community_material.update", "post", postId, null);
		return materialView(actor, true, postId);
	}

	/** 운영자의 자료 삭제. 글은 소프트 삭제하고 첨부는 끊는다. 그 순간부터 누구도 자료와 파일에 닿지 못한다(ADR-0011). */
	@Transactional
	void deleteMaterial(Actor actor, long postId) {
		requireOperator(actor);
		Group group = this.community.lockManagedMaterial(postId).orElseThrow(ApiException::notFound);
		this.community.deleteMaterial(postId);
		this.files.release(group.attachmentGroupId());
		this.audit.success(actor.userId(), actor.sessionId(), "community_material.delete", "post", postId, null);
	}

	// ---- 1:1 문의

	/**
	 * 1:1 문의 목록. 최근 문의 먼저다. 보통은 본인 문의만이고, 운영자가 all=true로 부르면 모든 사람의 문의와 작성자 이름을 준다.
	 * 운영자가 아닌 사람의 all=true는 404다.
	 * @param status all, answered, unanswered
	 */
	@Transactional(readOnly = true)
	InquiryPage inquiries(Actor actor, String status, String query, Long cursor, int size, boolean all) {
		boolean operator = this.operators.is(actor);
		if (all && !operator) {
			throw ApiException.notFound();
		}
		Boolean answered = switch (status == null || status.isBlank() ? "all" : status) {
			case "all" -> null;
			case "answered" -> true;
			case "unanswered" -> false;
			default -> throw ApiException.invalid("status는 all, answered, unanswered 중 하나여야 합니다.");
		};
		String title = PlainText.query(query);
		requirePage(cursor, size);
		Long author = all ? null : actor.userId();
		InquiryCounts counts = this.community.inquiryCounts(author, title);
		List<InquiryRow> rows = this.community.inquiries(author, answered, title, cursor, size + 1);
		List<InquiryRow> page = rows.subList(0, Math.min(size, rows.size()));
		Long next = rows.size() > size ? rows.get(size - 1).postId() : null;
		Map<Long, String> names = all
				? this.userNames.namesOf(page.stream().map(InquiryRow::authorId).distinct().toList()) : Map.of();
		List<InquiryItem> items = page.stream()
			.map((r) -> InquiryItem.of(r, all ? names.get(r.authorId()) : null))
			.toList();
		return new InquiryPage(items, counts, next, operator);
	}

	/** 문의와 답변. 문의 작성자와 운영자만 본다. 조회는 아무것도 바꾸지 않는다. */
	@Transactional(readOnly = true)
	InquiryView inquiry(Actor actor, long postId) {
		boolean operator = this.operators.is(actor);
		return inquiryView(actor, operator, postId);
	}

	/**
	 * 1:1 문의를 쓴다(EP-0158). 제목은 필수, 본문은 비어도 된다(화면 32 5.2). 같은 Idempotency-Key로 다시 보내면 처음 만든 문의를
	 * 돌려준다. break-glass 계정은 쓰지 못한다.
	 */
	InquiryView createInquiry(Actor actor, InquiryRequest body, String idempotencyKey) {
		if (actor.breakGlass()) {
			throw ApiException.notFound();
		}
		NewInquiry inquiry = new NewInquiry(PlainText.title(body.title(), MAX_TITLE),
				PlainText.optionalBody(body.body(), MAX_BODY), BoardFiles.validated(body.uploadIds()));
		this.rateLimiter.check("community-inquiry-create", actor.userId(), 10, Duration.ofMinutes(10));
		long boardId = this.transaction.execute((tx) -> this.boards.boardFor(CommunityBoards.INQUIRY));
		return this.idempotency.execute(actor.userId(), "community_inquiry.create", idempotencyKey, inquiry,
				InquiryView.class, () -> {
					Long group = this.files.attach(BoardFiles.INQUIRY, actor.userId(), null, inquiry.uploadIds());
					long postId = this.posts.insert(boardId, actor.userId(), "QUESTION", null,
							new NewText(inquiry.title(), inquiry.body(), false, false), group);
					this.audit.success(actor.userId(), actor.sessionId(), "community_inquiry.create", "post", postId,
							null);
					return inquiryView(actor, this.operators.is(actor), postId);
				});
	}

	/**
	 * 운영자의 답변. 문의 행을 먼저 잠그고 답변 수와 답변 일시를 같은 transaction에서 바꾼다. 문의 작성자에게 알린다. 같은
	 * Idempotency-Key로 다시 보내면 처음 만든 답변을 돌려준다. 운영자가 아니면 404다.
	 */
	ReplyView answer(Actor actor, long postId, ReplyRequest body, String idempotencyKey) {
		requireOperator(actor);
		NewReply reply = new NewReply(postId, PlainText.requiredBody(body.body(), MAX_BODY),
				BoardFiles.validated(body.uploadIds()));
		this.rateLimiter.check("community-inquiry-answer", actor.userId(), 60, Duration.ofMinutes(10));
		return this.idempotency.execute(actor.userId(), "community_inquiry.answer", idempotencyKey, reply,
				ReplyView.class, () -> {
					InquiryRow question = this.community.lockInquiry(postId).orElseThrow(ApiException::notFound);
					String title = REPLY_PREFIX + question.title();
					Long group = this.files.attach(BoardFiles.INQUIRY, actor.userId(), null, reply.uploadIds());
					long replyId = this.posts.insert(question.boardId(), actor.userId(), "ANSWER", postId,
							new NewText(title, reply.body(), false, false), group);
					this.community.markAnswered(postId);
					if (question.authorId() != actor.userId()) {
						// 알림마당 문의는 과목이 없다. 대상은 문의 ID다(ADR-0027).
						this.notifications.send(new DirectNotification(NotificationKind.QUESTION_ANSWERED,
								List.of(question.authorId()), null, postId, question.title()));
					}
					this.audit.success(actor.userId(), actor.sessionId(), "community_inquiry.answer", "post", replyId,
							null);
					return inquiryView(actor, true, postId).replies()
						.stream()
						.filter((r) -> r.replyId() == replyId)
						.findFirst()
						.orElseThrow();
				});
	}

	// ---- 판정

	/**
	 * 요청한 사람이 볼 수 있는 알림마당 자료·공지 대상(posts.audience 저장 값). 운영자는 모두, 그 밖에는 기관 역할로 정한다.
	 */
	static Set<String> audiences(Actor actor, boolean operator) {
		return operator ? EVERY_AUDIENCE : audiences(actor.role());
	}

	/** 기관 역할이 볼 수 있는 대상. 학생은 공통·학생, 교수·조교는 공통·교수, 직원은 모두다. */
	static Set<String> audiences(UserRole role) {
		return switch (role) {
			case STUDENT -> Set.of("ALL", "COMMON", "STUDENT");
			case PROFESSOR, TA -> Set.of("ALL", "COMMON", "PROFESSOR");
			case STAFF -> EVERY_AUDIENCE;
		};
	}

	private void requireOperator(Actor actor) {
		if (!this.operators.is(actor)) {
			throw ApiException.notFound();
		}
	}

	private MaterialView materialView(Actor actor, boolean operator, long postId) {
		MaterialRow row = this.community.material(actor.userId(), audiences(actor, operator), postId)
			.orElseThrow(ApiException::notFound);
		return MaterialView.of(row, this.files.of(row.attachmentGroupId()), operator);
	}

	private InquiryView inquiryView(Actor actor, boolean operator, long postId) {
		InquiryRow row = this.community.inquiry(operator ? null : actor.userId(), postId)
			.orElseThrow(ApiException::notFound);
		List<ReplyRow> replies = this.community.replies(postId, MAX_REPLIES);
		List<Long> people = replies.stream().map(ReplyRow::authorId).collect(Collectors.toList());
		people.add(row.authorId());
		Map<Long, String> names = this.userNames.namesOf(people.stream().distinct().toList());
		List<Long> groups = replies.stream().map(ReplyRow::attachmentGroupId).filter(Objects::nonNull)
			.collect(Collectors.toList());
		if (row.attachmentGroupId() != null) {
			groups.add(row.attachmentGroupId());
		}
		Map<Long, List<AttachedFile>> attached = this.files.list(groups);
		return new InquiryView(row.postId(), row.title(), row.body(), names.get(row.authorId()),
				row.authorId() == actor.userId(), row.answered(), row.answeredAt(), row.createdAt(),
				files(attached, row.attachmentGroupId()),
				replies.stream()
					.map((r) -> new ReplyView(r.replyId(), names.get(r.authorId()), r.body(),
							files(attached, r.attachmentGroupId()), r.createdAt()))
					.toList(),
				operator);
	}

	private static List<AttachedFile> files(Map<Long, List<AttachedFile>> attached, Long group) {
		return group == null ? List.of() : attached.getOrDefault(group, List.of());
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

	/** 운영자가 고른 자료 대상. 저장 값은 COMMON, PROFESSOR, STUDENT다. */
	private static String storedAudience(String audience) {
		return switch (audience == null ? "" : audience) {
			case "common" -> "COMMON";
			case "professor" -> "PROFESSOR";
			case "student" -> "STUDENT";
			default -> throw ApiException.invalid("audience는 common, professor, student 중 하나여야 합니다.");
		};
	}

	private static void requirePage(Long cursor, int size) {
		if (size < 1 || size > MAX_PAGE_SIZE || (cursor != null && cursor < 1)) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE_SIZE + " 이하, cursor는 1 이상이어야 합니다.");
		}
	}

	private static String audienceName(String stored) {
		return COMMON.contains(stored) ? "common" : stored.toLowerCase(Locale.ROOT);
	}

	/**
	 * 운영자의 자료 작성·수정. 작성자는 받지 않는다(SC-02). 제목과 본문은 일반 텍스트다.
	 * @param audience common, professor, student
	 * @param uploadIds 새 자료에만. 본인이 용도 community_material로 올린 업로드(10개까지)
	 * @param attachments 수정할 때만. 첨부의 최종 집합. 없으면 첨부를 바꾸지 않는다
	 * @param version 수정할 때만. 마지막으로 읽은 자료의 version
	 */
	record MaterialRequest(String title, String body, String audience, List<Long> uploadIds,
			BoardFiles.AttachmentSet attachments, Integer version) {
	}

	/** 멱등 지문을 만드는 검사된 입력. */
	record NewMaterial(String title, String body, String audience, List<Long> uploadIds) {
	}

	/** @param uploadIds 본인이 용도 inquiry_attachment로 올린 업로드(5개까지) */
	record InquiryRequest(String title, String body, List<Long> uploadIds) {
	}

	record NewInquiry(String title, String body, List<Long> uploadIds) {
	}

	/** @param uploadIds 본인이 용도 inquiry_attachment로 올린 업로드(5개까지) */
	record ReplyRequest(String body, List<Long> uploadIds) {
	}

	record NewReply(long inquiryId, String body, List<Long> uploadIds) {
	}

	/** @param audience common, professor, student */
	record MaterialItem(long postId, String title, String excerpt, String audience, int viewCount,
			LocalDateTime publishedAt, boolean read) {

		static MaterialItem of(MaterialRow row) {
			return new MaterialItem(row.postId(), row.title(), row.excerpt(), audienceName(row.audience()),
					row.viewCount(), row.publishedAt(), row.read());
		}

	}

	/**
	 * @param attachments 내려받기는 {@code GET /api/files/{fileId}/content}
	 * @param canManage 알림마당 운영자면 true. 수정·삭제할 수 있다
	 */
	record MaterialView(long postId, String title, String body, String audience, int viewCount,
			LocalDateTime publishedAt, LocalDateTime updatedAt, boolean read, List<AttachedFile> attachments,
			int version, boolean canManage) {

		static MaterialView of(MaterialRow row, List<AttachedFile> attachments, boolean operator) {
			return new MaterialView(row.postId(), row.title(), row.body(), audienceName(row.audience()),
					row.viewCount(), row.publishedAt(), row.updatedAt(), row.read(), attachments, row.version(),
					operator);
		}

	}

	/** 요청한 사람이 볼 수 있는 대상만 센다. 볼 수 없는 대상은 0이다. */
	record MaterialCounts(long total, long common, long professor, long student) {
	}

	/** @param canManage 알림마당 운영자면 true. 자료를 올리고 고치고 지울 수 있다 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record MaterialPage(List<MaterialItem> items, MaterialCounts counts, Long nextCursor, boolean canManage) {
	}

	/** @param authorName 운영자가 all=true로 본 목록에만 있다 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record InquiryItem(long postId, String title, String authorName, boolean answered, LocalDateTime answeredAt,
			int answerCount, LocalDateTime createdAt) {

		static InquiryItem of(InquiryRow row, String authorName) {
			return new InquiryItem(row.postId(), row.title(), authorName, row.answered(), row.answeredAt(),
					row.answerCount(), row.createdAt());
		}

	}

	/** @param canManage 알림마당 운영자면 true. all=true로 모든 문의를 보고 답할 수 있다 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record InquiryPage(List<InquiryItem> items, InquiryCounts counts, Long nextCursor, boolean canManage) {
	}

	/**
	 * @param mine 요청한 사람이 쓴 문의인지
	 * @param attachments 내려받기는 {@code GET /api/files/{fileId}/content}
	 * @param canAnswer 알림마당 운영자면 true
	 */
	record InquiryView(long postId, String title, String body, String authorName, boolean mine, boolean answered,
			LocalDateTime answeredAt, LocalDateTime createdAt, List<AttachedFile> attachments, List<ReplyView> replies,
			boolean canAnswer) {
	}

	record ReplyView(long replyId, String authorName, String body, List<AttachedFile> attachments,
			LocalDateTime createdAt) {
	}

}
