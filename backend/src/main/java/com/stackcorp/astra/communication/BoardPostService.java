package com.stackcorp.astra.communication;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.communication.BoardAccess.Viewer;
import com.stackcorp.astra.communication.BoardPostRepository.Filter;
import com.stackcorp.astra.communication.BoardPostRepository.Locked;
import com.stackcorp.astra.communication.BoardPostRepository.NewText;
import com.stackcorp.astra.communication.BoardPostRepository.PostRow;
import com.stackcorp.astra.communication.BoardPostRepository.QnaStatus;
import com.stackcorp.astra.communication.BoardPostRepository.StatusCounts;
import com.stackcorp.astra.files.api.Attachments.AttachedFile;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.UserNames;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 강의실 질의응답의 질문과 열린게시판 글(ADR-0024). 과목은 경로로 받고 글이 그 과목의 그 게시판에 있는지 함께 확인한다(SC-03).
 * <ul>
 * <li>질문은 수강생이 쓴다. 열린게시판 글은 구성원 누구나 쓴다.</li>
 * <li>글은 작성자만 고친다. 질문은 답글이 달리기 전까지만 작성자가 고치고 지운다.</li>
 * <li>담당자는 그 과목의 어떤 글이든 지울 수 있다(관리 삭제). 감사 기록에 따로 남긴다.</li>
 * <li>비공개 질문은 작성자와 담당자만 본다. 다른 학생에게는 목록, 건수, 검색, 상세, 첨부 어디에도 없다.</li>
 * <li>익명 질문의 작성자 이름은 작성자와 담당자만 본다. 내부 작성자는 그대로 저장한다(SC-07).</li>
 * </ul>
 * 권한이 없거나 볼 수 없는 글은 존재를 드러내지 않도록 404다. 조회는 아무것도 바꾸지 않는다. 읽음과 추천은 (글, 사용자) unique 행이다(SC-04,
 * SC-05).
 */
@Service
class BoardPostService {

	static final int MAX_TITLE = 300;

	static final int MAX_BODY = 20_000;

	static final int MAX_PAGE_SIZE = 50;

	private final BoardPostRepository posts;

	private final CourseBoards boards;

	private final PostReactions reactions;

	private final BoardAccess access;

	private final BoardFiles files;

	private final UserNames userNames;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	private final TransactionTemplate transaction;

	private final Notifications notifications;

	BoardPostService(BoardPostRepository posts, CourseBoards boards, PostReactions reactions, BoardAccess access,
			BoardFiles files, UserNames userNames, RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit,
			TransactionTemplate transaction, Notifications notifications) {
		this.posts = posts;
		this.boards = boards;
		this.reactions = reactions;
		this.access = access;
		this.files = files;
		this.userNames = userNames;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
		this.transaction = transaction;
		this.notifications = notifications;
	}

	/**
	 * 질의응답 목록(EP-0070). 쪽 번호 목록이고 최근 질문 먼저다. 첫 쪽의 응답에 담긴 snapshot을 다음 쪽 요청에 다시 보내면 그 사이에 올라온 질문
	 * 때문에 쪽이 밀리지 않는다.
	 * @param status all, faq, solved, unsolved
	 * @param answered 내가 답글을 단 질문만(나의 답변)
	 */
	@Transactional(readOnly = true)
	QuestionPage questions(long userId, long courseId, String query, String status, boolean mine, boolean answered,
			int page, int size, Long snapshot) {
		Viewer viewer = this.access.viewer(userId, courseId);
		QnaStatus qnaStatus = qnaStatus(status);
		String title = PlainText.query(query);
		if (page < 1 || size < 1 || size > MAX_PAGE_SIZE) {
			throw ApiException.invalid("page는 1 이상, size는 1 이상 " + MAX_PAGE_SIZE + " 이하여야 합니다.");
		}
		if (snapshot != null && snapshot < 0) {
			throw ApiException.invalid("snapshot이 올바르지 않습니다.");
		}
		long upTo = snapshot != null ? snapshot : this.posts.maxId(viewer, BoardKind.QNA);
		Filter filter = new Filter(title, qnaStatus, mine, answered, upTo);
		StatusCounts counts = this.posts.counts(viewer, BoardKind.QNA, filter);
		List<PostRow> rows = this.posts.page(viewer, BoardKind.QNA, filter,
				(int) Math.min((long) (page - 1) * size, Integer.MAX_VALUE), size);
		return new QuestionPage(items(viewer, BoardKind.QNA, rows), qnaStatus.of(counts), counts, page, size, upTo);
	}

	/**
	 * 열린게시판 목록(EP-0019). 최근 글 먼저다. 다음 묶음은 응답의 nextCursor를 cursor로 보내 받는다. 새 글이 올라와도 이미 받은 글이 다시
	 * 오거나 빠지지 않는다.
	 */
	@Transactional(readOnly = true)
	PostPage openPosts(long userId, long courseId, String query, Long cursor, int size) {
		Viewer viewer = this.access.viewer(userId, courseId);
		String title = PlainText.query(query);
		if (size < 1 || size > MAX_PAGE_SIZE || (cursor != null && cursor < 1)) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE_SIZE + " 이하, cursor는 1 이상이어야 합니다.");
		}
		Filter filter = new Filter(title, null, false, false, null);
		long total = this.posts.count(viewer, BoardKind.OPEN, filter);
		List<PostRow> rows = this.posts.before(viewer, BoardKind.OPEN, filter, cursor, size + 1);
		Long next = rows.size() > size ? rows.get(size - 1).postId() : null;
		return new PostPage(items(viewer, BoardKind.OPEN, rows.subList(0, Math.min(size, rows.size()))), total, next);
	}

	@Transactional(readOnly = true)
	PostView get(long userId, long courseId, BoardKind kind, long postId) {
		Viewer viewer = this.access.viewer(userId, courseId);
		return view(viewer, kind, visible(viewer, kind, postId));
	}

	/**
	 * 글을 쓰고 첨부를 확정한다. 같은 Idempotency-Key로 다시 보내면 처음 만든 글을 돌려준다(SC-05, CHG-08).
	 */
	PostView create(Actor actor, long courseId, BoardKind kind, PostRequest body, String idempotencyKey) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		if (kind == BoardKind.QNA && viewer.manager()) {
			// 질문은 수강생이 한다(화면 49, 50). 담당자는 답글을 단다.
			throw ApiException.notFound();
		}
		if (body.version() != null || body.attachments() != null) {
			throw ApiException.invalid("새 글에는 version이나 attachments를 줄 수 없습니다. 첨부는 uploadIds로 보냅니다.");
		}
		NewPost post = new NewPost(text(kind, body), BoardFiles.validated(body.uploadIds()));
		this.rateLimiter.check(operation(kind, "create"), actor.userId(), 20, Duration.ofMinutes(10));
		long boardId = this.transaction.execute((tx) -> this.boards.boardFor(courseId, kind));
		return this.idempotency.execute(actor.userId(), audited(kind, "create"), idempotencyKey,
				List.of(courseId, kind.name(), post), PostView.class, () -> {
					Long group = this.files.attach(actor.userId(), courseId, post.uploadIds());
					long postId = this.posts.insert(boardId, actor.userId(), kind.postKind, null, post.text(), group);
					this.audit.success(actor.userId(), actor.sessionId(), audited(kind, "create"), "post", postId,
							courseId);
					return view(viewer, kind, visible(viewer, kind, postId));
				});
	}

	/**
	 * 작성자의 전체 수정. 본문의 version이 저장된 값과 다르면 409 version_conflict, 답글이 달린 질문이면 409 already_answered.
	 * attachments가 있으면 첨부를 그 최종 집합으로 바꾸고, 없으면 그대로 둔다. 쓸 수 없는 업로드나 이 글의 것이 아닌 파일이 있으면 409
	 * attachment_not_ready이고 글도 바꾸지 않는다. 버전 확인이 먼저라 버전이 다르면 첨부도 그대로다.
	 */
	@Transactional
	PostView update(Actor actor, long courseId, BoardKind kind, long postId, PostRequest body) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		PostRow row = visible(viewer, kind, postId);
		if (row.authorId() != actor.userId()) {
			throw ApiException.notFound();
		}
		if (body.version() == null || body.version() < 1) {
			throw ApiException.invalid("version이 필요합니다.");
		}
		if (body.uploadIds() != null && !body.uploadIds().isEmpty()) {
			throw ApiException.invalid("수정할 때는 uploadIds 대신 attachments(keep, add)로 첨부를 보냅니다.");
		}
		NewText text = text(kind, body);
		BoardFiles.Change attachments = BoardFiles.validated(body.attachments(), BoardFiles.MAX_UPLOADS);
		this.rateLimiter.check(operation(kind, "update"), actor.userId(), 60, Duration.ofMinutes(10));
		if (this.posts.update(postId, actor.userId(), body.version(), text, kind.answers) == 0) {
			BoardPostRepository.State state = this.posts.current(postId).orElseThrow(ApiException::notFound);
			if (kind.answers && state.answerCount() > 0) {
				throw alreadyAnswered();
			}
			throw versionConflict();
		}
		if (attachments != null) {
			// 조건부 UPDATE가 글 행을 잠갔다. 같은 글의 다른 수정은 이 transaction이 끝날 때까지 기다린다.
			Long group = this.files.change(BoardFiles.PURPOSE, actor.userId(), courseId,
					this.posts.attachmentGroup(postId), attachments);
			this.posts.setAttachmentGroup(postId, group);
		}
		this.audit.success(actor.userId(), actor.sessionId(), audited(kind, "update"), "post", postId, courseId);
		return view(viewer, kind, visible(viewer, kind, postId));
	}

	/**
	 * 작성자 또는 담당자의 소프트 삭제. 학생 작성자는 답글이 달린 질문을 지우지 못한다(409 already_answered). 담당자가 남의 글을 지우면
	 * 관리 삭제(moderate)로 감사 기록을 남긴다.
	 */
	@Transactional
	void delete(Actor actor, long courseId, BoardKind kind, long postId) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		PostRow row = visible(viewer, kind, postId);
		boolean own = row.authorId() == actor.userId();
		if (!own && !viewer.manager()) {
			throw ApiException.notFound();
		}
		boolean unanswered = kind.answers && !viewer.manager();
		if (this.posts.delete(postId, unanswered) == 0) {
			if (this.posts.current(postId).isEmpty()) {
				throw ApiException.notFound();
			}
			throw alreadyAnswered();
		}
		if (kind.answers) {
			this.notifications.withdraw(NotificationKind.QUESTION_ANSWERED, postId);
		}
		this.audit.success(actor.userId(), actor.sessionId(), audited(kind, own ? "delete" : "moderate"), "post", postId,
				courseId);
	}

	/**
	 * 추천과 추천 취소. 목표 상태를 정하는 명령이라 몇 번 보내도 결과가 같다. 응답의 추천 수는 서버 값이다(EP-0061, EP-0062, COMM-10).
	 * 다른 사람에게 보이는 추천 수가 바뀐 요청만 감사 기록을 남긴다. 이미 그 상태였던 재전송은 남기지 않는다.
	 */
	@Transactional
	LikeState like(Actor actor, long courseId, BoardKind kind, long postId, boolean liked) {
		long userId = actor.userId();
		Viewer viewer = this.access.viewer(userId, courseId);
		this.rateLimiter.check("board-like", userId, 120, Duration.ofMinutes(10));
		Locked post = this.posts.lockVisible(viewer, kind, postId).orElseThrow(ApiException::notFound);
		int count = post.likeCount();
		boolean changed = liked ? this.reactions.like(postId, userId) : this.reactions.unlike(postId, userId);
		if (changed) {
			count = liked ? count + 1 : Math.max(0, count - 1);
			this.audit.success(userId, actor.sessionId(), audited(kind, liked ? "like" : "unlike"), "post", postId,
					courseId);
		}
		return new LikeState(postId, liked, count);
	}

	/** 읽음으로 표시한다. 몇 번 보내도 한 행이고 조회수는 처음 한 번만 오른다(EP-0022, EP-0074, COMM-11). */
	@Transactional
	ReadState read(long userId, long courseId, BoardKind kind, long postId) {
		Viewer viewer = this.access.viewer(userId, courseId);
		Locked post = this.posts.lockVisible(viewer, kind, postId).orElseThrow(ApiException::notFound);
		int views = post.viewCount() + (this.reactions.markRead(postId, userId) ? 1 : 0);
		return new ReadState(postId, true, views);
	}

	/**
	 * 해결 상태와 FAQ 지정(EP-0072). 목표 상태를 받는다. 해결 상태는 질문 작성자와 담당자가, FAQ는 담당자만 바꾼다. 버전은 올리지 않는다.
	 */
	@Transactional
	StatusView status(Actor actor, long courseId, long postId, StatusRequest body) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		PostRow row = visible(viewer, BoardKind.QNA, postId);
		if (body.solved() == null && body.faq() == null) {
			throw ApiException.invalid("solved나 faq 중 하나는 있어야 합니다.");
		}
		boolean asker = row.authorId() == actor.userId();
		if ((body.faq() != null && !viewer.manager()) || (body.solved() != null && !asker && !viewer.manager())) {
			throw ApiException.notFound();
		}
		this.posts.setStatus(postId, body.solved(), body.faq());
		this.audit.success(actor.userId(), actor.sessionId(), "qna_question.status", "post", postId, courseId);
		return new StatusView(postId, body.solved() != null ? body.solved() : row.solved(),
				body.faq() != null ? body.faq() : row.faq());
	}

	private PostRow visible(Viewer viewer, BoardKind kind, long postId) {
		return this.posts.find(viewer, kind, postId).orElseThrow(ApiException::notFound);
	}

	private List<PostItem> items(Viewer viewer, BoardKind kind, List<PostRow> rows) {
		Map<Long, String> names = this.userNames.namesOf(rows.stream()
			.filter((r) -> viewer.seesAuthor(r.authorId(), r.anonymous()))
			.map(PostRow::authorId)
			.distinct()
			.toList());
		return rows.stream().map((row) -> PostItem.of(row, kind, viewer, names)).toList();
	}

	private PostView view(Viewer viewer, BoardKind kind, PostRow row) {
		String author = viewer.seesAuthor(row.authorId(), row.anonymous())
				? this.userNames.namesOf(List.of(row.authorId())).get(row.authorId()) : null;
		return PostView.of(row, kind, viewer, author, this.files.of(row.attachmentGroupId()));
	}

	private static NewText text(BoardKind kind, PostRequest body) {
		if ((!kind.anonymous && Boolean.TRUE.equals(body.anonymous()))
				|| (!kind.secret && Boolean.TRUE.equals(body.secret()))) {
			throw ApiException.invalid("이 게시판에서는 익명이나 비공개 글을 쓸 수 없습니다.");
		}
		return new NewText(PlainText.title(body.title(), MAX_TITLE), PlainText.optionalBody(body.body(), MAX_BODY),
				Boolean.TRUE.equals(body.anonymous()), Boolean.TRUE.equals(body.secret()));
	}

	private static QnaStatus qnaStatus(String status) {
		if (status == null || status.isBlank()) {
			return QnaStatus.ALL;
		}
		for (QnaStatus candidate : QnaStatus.values()) {
			if (candidate.name().toLowerCase(Locale.ROOT).equals(status)) {
				return candidate;
			}
		}
		throw ApiException.invalid("status는 all, faq, solved, unsolved 중 하나여야 합니다.");
	}

	static ApiException alreadyAnswered() {
		return new ApiException(HttpStatus.CONFLICT, "already_answered", "답글이 달린 질문은 고치거나 지울 수 없습니다.");
	}

	static ApiException versionConflict() {
		return new ApiException(HttpStatus.CONFLICT, "version_conflict",
				"다른 곳에서 먼저 수정했습니다. 최신 내용을 다시 불러온 뒤 수정해 주세요.");
	}

	/** 감사 기록과 멱등 원장의 연산 이름. */
	private static String audited(BoardKind kind, String verb) {
		return (kind == BoardKind.QNA ? "qna_question." : "board_post.") + verb;
	}

	/** 요청 제한의 연산 이름. */
	private static String operation(BoardKind kind, String verb) {
		return (kind == BoardKind.QNA ? "qna-question-" : "board-post-") + verb;
	}

	/**
	 * 요청 본문. 작성자와 과목은 받지 않는다. session과 경로에서 정한다(SC-02). 제목과 본문은 일반 텍스트다.
	 * @param anonymous 질의응답만. 다른 학생에게 작성자 이름을 숨긴다. 담당자에게는 보인다
	 * @param secret 질의응답만. 비공개 질문. 작성자와 담당자만 본다
	 * @param uploadIds 새 글에만. 본인이 용도 post_attachment로 올린 업로드
	 * @param attachments 수정할 때만. 첨부의 최종 집합. 없으면 첨부를 바꾸지 않는다
	 * @param version 수정할 때만. 마지막으로 읽은 글의 version
	 */
	record PostRequest(String title, String body, Boolean anonymous, Boolean secret, List<Long> uploadIds,
			BoardFiles.AttachmentSet attachments, Integer version) {
	}

	/** 멱등 지문을 만드는 검사된 입력. */
	record NewPost(NewText text, List<Long> uploadIds) {
	}

	/** @param solved 해결이면 true. null이면 그대로 @param faq 담당자만. null이면 그대로 */
	record StatusRequest(Boolean solved, Boolean faq) {
	}

	record StatusView(long postId, boolean solved, boolean faq) {
	}

	record LikeState(long postId, boolean liked, int likeCount) {
	}

	record ReadState(long postId, boolean read, int viewCount) {
	}

	/**
	 * 목록 항목. 질의응답에만 있는 값(anonymous, secret, solved, faq, answerCount)과 열린게시판에만 있는 값(commentCount)은 다른
	 * 게시판에서 빠진다.
	 * @param authorName 볼 수 없는 익명 작성자면 빠진다
	 * @param mine 요청한 사람이 쓴 글인지
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PostItem(long postId, String title, String excerpt, String authorName, boolean mine, Boolean anonymous,
			Boolean secret, Boolean solved, Boolean faq, int viewCount, int likeCount, Integer answerCount,
			Integer commentCount, LocalDateTime createdAt, boolean read, boolean liked) {

		static PostItem of(PostRow row, BoardKind kind, Viewer viewer, Map<Long, String> names) {
			String author = viewer.seesAuthor(row.authorId(), row.anonymous()) ? names.get(row.authorId()) : null;
			boolean qna = kind.answers;
			return new PostItem(row.postId(), row.title(), row.excerpt(), author, row.authorId() == viewer.userId(),
					qna ? row.anonymous() : null, qna ? row.secret() : null, qna ? row.solved() : null,
					qna ? row.faq() : null, row.viewCount(), row.likeCount(), qna ? row.answerCount() : null,
					kind.comments ? row.commentCount() : null, row.createdAt(), row.read(), row.liked());
		}

	}

	/**
	 * 글 상세. 요청한 사람이 할 수 있는 일(canEdit 등)을 함께 준다.
	 * @param attachments 내려받기는 {@code GET /api/courses/{courseId}/files/{fileId}/content}
	 * @param edited 작성 뒤 고친 적이 있으면 true(version이 1보다 크다). updatedAt은 조회수·추천 같은 집계가 바뀌어도 움직일 수 있다
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PostView(long postId, String title, String body, String authorName, boolean mine, Boolean anonymous,
			Boolean secret, Boolean solved, Boolean faq, int viewCount, int likeCount, Integer answerCount,
			Integer commentCount, List<AttachedFile> attachments, int version, boolean edited, LocalDateTime createdAt,
			LocalDateTime updatedAt, boolean read, boolean liked, boolean canEdit, boolean canDelete,
			Boolean canSetSolved, Boolean canSetFaq) {

		static PostView of(PostRow row, BoardKind kind, Viewer viewer, String authorName, List<AttachedFile> files) {
			boolean mine = row.authorId() == viewer.userId();
			boolean qna = kind.answers;
			boolean open = !qna || row.answerCount() == 0;
			return new PostView(row.postId(), row.title(), row.body(), authorName, mine, qna ? row.anonymous() : null,
					qna ? row.secret() : null, qna ? row.solved() : null, qna ? row.faq() : null, row.viewCount(),
					row.likeCount(), qna ? row.answerCount() : null, kind.comments ? row.commentCount() : null, files,
					row.version(), row.version() > 1, row.createdAt(), row.updatedAt(), row.read(), row.liked(),
					mine && open,
					viewer.manager() || (mine && open), qna ? mine || viewer.manager() : null,
					qna ? viewer.manager() : null);
		}

	}

	/**
	 * @param total 지금 상태 필터에 맞는 질문 수
	 * @param counts 상태 필터 탭의 수(전체, FAQ, 해결, 미해결). 검색어와 나의 질문·나의 답변 조건은 반영한다
	 * @param snapshot 다음 쪽을 요청할 때 그대로 보낼 기준점
	 */
	record QuestionPage(List<PostItem> items, long total, StatusCounts counts, int page, int size, long snapshot) {
	}

	/** @param nextCursor 다음 묶음이 없으면 빠진다 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record PostPage(List<PostItem> items, long total, Long nextCursor) {
	}

}
