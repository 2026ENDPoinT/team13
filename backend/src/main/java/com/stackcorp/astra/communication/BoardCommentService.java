package com.stackcorp.astra.communication;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.stackcorp.astra.communication.Authors.Author;
import com.stackcorp.astra.communication.Authors.Known;
import com.stackcorp.astra.communication.BoardAccess.Viewer;
import com.stackcorp.astra.communication.CommentRepository.CommentRow;
import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 열린게시판 댓글(화면 48 5.4~5.5의 공통 댓글 모듈, EP-0023~0026). 글을 볼 수 있는 구성원이 쓴다. 답글은 한 단계만 단다. 작성자만 고치고,
 * 작성자와 담당자가 지운다. 본문은 일반 텍스트이고 원본처럼 1800 byte(127보다 큰 글자는 2 byte로 센다)까지다. 수정도 같은 규칙이다.
 */
@Service
class BoardCommentService {

	static final int MAX_BYTES = 1800;

	static final int MAX_PAGE_SIZE = 100;

	private final CommentRepository comments;

	private final BoardPostRepository posts;

	private final BoardAccess access;

	private final Authors authors;

	private final RateLimiter rateLimiter;

	private final Idempotency idempotency;

	private final AuditLog audit;

	BoardCommentService(CommentRepository comments, BoardPostRepository posts, BoardAccess access, Authors authors,
			RateLimiter rateLimiter, Idempotency idempotency, AuditLog audit) {
		this.comments = comments;
		this.posts = posts;
		this.access = access;
		this.authors = authors;
		this.rateLimiter = rateLimiter;
		this.idempotency = idempotency;
		this.audit = audit;
	}

	/** 먼저 쓴 댓글부터. 다음 묶음은 nextCursor를 cursor로 보내 받는다. */
	@Transactional(readOnly = true)
	CommentPage list(long userId, long courseId, long postId, Long cursor, int size) {
		Viewer viewer = this.access.viewer(userId, courseId);
		if (size < 1 || size > MAX_PAGE_SIZE || (cursor != null && cursor < 1)) {
			throw ApiException.invalid("size는 1 이상 " + MAX_PAGE_SIZE + " 이하, cursor는 1 이상이어야 합니다.");
		}
		this.posts.find(viewer, BoardKind.OPEN, postId).orElseThrow(ApiException::notFound);
		List<CommentRow> rows = this.comments.list(postId, cursor, size + 1);
		Long next = rows.size() > size ? rows.get(size - 1).commentId() : null;
		return new CommentPage(views(viewer, rows.subList(0, Math.min(size, rows.size()))), next);
	}

	/** 같은 Idempotency-Key로 다시 보내면 처음 만든 댓글을 돌려준다. */
	CommentView create(Actor actor, long courseId, long postId, CommentRequest body, String idempotencyKey) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		this.posts.find(viewer, BoardKind.OPEN, postId).orElseThrow(ApiException::notFound);
		NewComment comment = new NewComment(postId, validated(body.body()), body.parentCommentId());
		if (comment.parentCommentId() != null && comment.parentCommentId() < 1) {
			throw ApiException.invalid("parentCommentId가 올바르지 않습니다.");
		}
		this.rateLimiter.check("board-comment-create", actor.userId(), 60, Duration.ofMinutes(10));
		return this.idempotency.execute(actor.userId(), "board_comment.create", idempotencyKey,
				List.of(courseId, comment), CommentView.class, () -> {
					this.posts.lockVisible(viewer, BoardKind.OPEN, postId).orElseThrow(ApiException::notFound);
					if (comment.parentCommentId() != null && this.comments.find(postId, comment.parentCommentId())
						.filter((p) -> p.parentCommentId() == null)
						.isEmpty()) {
						throw ApiException.invalid("답글을 달 댓글이 없습니다.");
					}
					long commentId = this.comments.insert(postId, comment.parentCommentId(), actor.userId(),
							comment.body());
					this.audit.success(actor.userId(), actor.sessionId(), "board_comment.create", "comment", commentId,
							courseId);
					return views(viewer, List.of(this.comments.find(postId, commentId).orElseThrow())).getFirst();
				});
	}

	/**
	 * 작성자의 본문 수정(EP-0026). 본문의 version이 다르면 409 version_conflict. 담당자도 남의 댓글은 고치지 못한다. 답글 관계는
	 * 바꾸지 않는다.
	 */
	@Transactional
	CommentView update(Actor actor, long courseId, long postId, long commentId, CommentUpdate body) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		this.posts.find(viewer, BoardKind.OPEN, postId).orElseThrow(ApiException::notFound);
		CommentRow row = this.comments.find(postId, commentId).orElseThrow(ApiException::notFound);
		if (row.authorId() != actor.userId()) {
			throw ApiException.notFound();
		}
		if (body.version() == null || body.version() < 1) {
			throw ApiException.invalid("version이 필요합니다.");
		}
		String text = validated(body.body());
		this.rateLimiter.check("board-comment-update", actor.userId(), 60, Duration.ofMinutes(10));
		// 글 행을 먼저 잠근다. 같은 글의 댓글 작성·삭제와 같은 순서다.
		this.posts.lockVisible(viewer, BoardKind.OPEN, postId).orElseThrow(ApiException::notFound);
		if (this.comments.update(postId, commentId, actor.userId(), body.version(), text) == 0) {
			this.comments.find(postId, commentId).orElseThrow(ApiException::notFound);
			throw BoardPostService.versionConflict();
		}
		this.audit.success(actor.userId(), actor.sessionId(), "board_comment.update", "comment", commentId, courseId);
		return views(viewer, List.of(this.comments.find(postId, commentId).orElseThrow())).getFirst();
	}

	/** 작성자 또는 담당자의 소프트 삭제. 담당자가 남의 댓글을 지우면 관리 삭제로 감사 기록을 남긴다. */
	@Transactional
	void delete(Actor actor, long courseId, long postId, long commentId) {
		Viewer viewer = this.access.viewer(actor.userId(), courseId);
		this.posts.lockVisible(viewer, BoardKind.OPEN, postId).orElseThrow(ApiException::notFound);
		CommentRow row = this.comments.find(postId, commentId).orElseThrow(ApiException::notFound);
		boolean own = row.authorId() == actor.userId();
		if (!own && !viewer.manager()) {
			throw ApiException.notFound();
		}
		if (!this.comments.delete(postId, commentId)) {
			throw ApiException.notFound();
		}
		this.audit.success(actor.userId(), actor.sessionId(), own ? "board_comment.delete" : "board_comment.moderate",
				"comment", commentId, courseId);
	}

	private List<CommentView> views(Viewer viewer, List<CommentRow> rows) {
		Known known = this.authors.of(viewer.userId(),
				rows.stream().filter((r) -> !r.deleted()).map(CommentRow::authorId).toList());
		return rows.stream().map((row) -> CommentView.of(row, viewer, known.author(row.authorId()))).toList();
	}

	private static String validated(String body) {
		String text = body == null || body.isBlank() ? null : body.strip();
		if (text == null) {
			throw ApiException.invalid("body를 입력해 주세요.");
		}
		if (PlainText.hasControl(text, true) || legacyBytes(text) > MAX_BYTES) {
			throw ApiException.invalid("댓글은 " + MAX_BYTES + " byte까지 쓸 수 있습니다.");
		}
		return text;
	}

	/** 원본 getLength와 같은 셈. UTF-16 문자마다 127보다 크면 2, 아니면 1이다. */
	static int legacyBytes(String text) {
		int bytes = 0;
		for (int i = 0; i < text.length(); i++) {
			bytes += text.charAt(i) > 127 ? 2 : 1;
		}
		return bytes;
	}

	/** @param parentCommentId 댓글 답글이면 답할 댓글. 답글에 답글은 달 수 없다 */
	record CommentRequest(String body, Long parentCommentId) {
	}

	/** @param version 마지막으로 읽은 댓글의 version */
	record CommentUpdate(String body, Integer version) {
	}

	/** 멱등 지문을 만드는 검사된 입력. */
	record NewComment(long postId, String body, Long parentCommentId) {
	}

	/**
	 * @param authorId 작성자 사용자 ID. 지운 댓글이거나 계정이 없으면 빠진다. 아바타는 {@code GET /api/users/{authorId}/avatar}
	 * @param authorHasPhoto 요청한 사람이 작성자의 아바타 사진을 받을 수 있으면 true. 지운 댓글이면 빠진다
	 * @param edited 작성 뒤 본문을 고친 적이 있으면 true(version이 1보다 크다). updatedAt은 고치기 전에는 null이다
	 * @param deleted 지운 댓글. 살아 있는 답글이 있어 자리만 남는다. 본문, 작성자, version, edited는 빠진다
	 * @param canEdit 작성자 본인이면 true
	 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CommentView(long commentId, Long parentCommentId, Long authorId, String authorName, Boolean authorHasPhoto,
			Boolean mine, String body, Integer version, Boolean edited, LocalDateTime createdAt,
			LocalDateTime updatedAt, boolean deleted, Boolean canEdit, Boolean canDelete) {

		static CommentView of(CommentRow row, Viewer viewer, Author author) {
			if (row.deleted()) {
				return new CommentView(row.commentId(), row.parentCommentId(), null, null, null, null, null, null, null,
						row.createdAt(), null, true, null, null);
			}
			boolean mine = row.authorId() == viewer.userId();
			return new CommentView(row.commentId(), row.parentCommentId(), author.id(), author.name(),
					author.hasPhoto(), mine, row.body(), row.version(), row.version() > 1, row.createdAt(),
					row.updatedAt(), false, mine, mine || viewer.manager());
		}

	}

	@JsonInclude(JsonInclude.Include.NON_NULL)
	record CommentPage(List<CommentView> items, Long nextCursor) {
	}

}
