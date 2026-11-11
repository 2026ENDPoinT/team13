package com.stackcorp.astra.communication;

import com.stackcorp.astra.communication.BoardCommentService.CommentPage;
import com.stackcorp.astra.communication.BoardCommentService.CommentRequest;
import com.stackcorp.astra.communication.BoardCommentService.CommentView;
import com.stackcorp.astra.communication.BoardPostService.LikeState;
import com.stackcorp.astra.communication.BoardPostService.PostPage;
import com.stackcorp.astra.communication.BoardPostService.PostRequest;
import com.stackcorp.astra.communication.BoardPostService.PostView;
import com.stackcorp.astra.communication.BoardPostService.ReadState;
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
 * 강의실 열린게시판(EP-0016~0022의 대체 계약, 화면 53·54). 과목마다 하나다. 게시판 그룹 번호(ARTL_GRP_ID) 대신 과목 경로로 고른다.
 * 권한 판정은 {@link BoardPostService}와 {@link BoardCommentService}에 있다. 상세 조회는 열람 기록을 남기지 않는다. 읽음은 POST
 * 명령이다(SC-04).
 */
@RestController
class OpenBoardController {

	private final BoardPostService posts;

	private final BoardCommentService comments;

	OpenBoardController(BoardPostService posts, BoardCommentService comments) {
		this.posts = posts;
		this.comments = comments;
	}

	/** EP-0019. q는 제목 검색. 다음 묶음은 nextCursor를 cursor로 보낸다. */
	@GetMapping("/api/courses/{courseId}/open-board/posts")
	ResponseEntity<PostPage> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(required = false) String q, @RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "10") int size) {
		return ok(this.posts.openPosts(actor.userId(), courseId, q, cursor, size));
	}

	/** EP-0022. */
	@GetMapping("/api/courses/{courseId}/open-board/posts/{postId}")
	ResponseEntity<PostView> one(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long postId) {
		return ok(this.posts.get(actor.userId(), courseId, BoardKind.OPEN, postId));
	}

	/** EP-0017. 201. Idempotency-Key는 선택이다. 같은 key로 다시 보내면 처음 만든 글을 돌려준다. */
	@PostMapping("/api/courses/{courseId}/open-board/posts")
	ResponseEntity<PostView> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody PostRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.posts.create(actor, courseId, BoardKind.OPEN, body, idempotencyKey));
	}

	/** EP-0021의 수정 제출. 전체 교체. */
	@PutMapping("/api/courses/{courseId}/open-board/posts/{postId}")
	ResponseEntity<PostView> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long postId, @RequestBody PostRequest body) {
		return ok(this.posts.update(actor, courseId, BoardKind.OPEN, postId, body));
	}

	/** EP-0016. */
	@DeleteMapping("/api/courses/{courseId}/open-board/posts/{postId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long postId) {
		this.posts.delete(actor, courseId, BoardKind.OPEN, postId);
		return noContent();
	}

	@PutMapping("/api/courses/{courseId}/open-board/posts/{postId}/like")
	ResponseEntity<LikeState> like(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long postId) {
		return ok(this.posts.like(actor.userId(), courseId, BoardKind.OPEN, postId, true));
	}

	@DeleteMapping("/api/courses/{courseId}/open-board/posts/{postId}/like")
	ResponseEntity<LikeState> unlike(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long postId) {
		return ok(this.posts.like(actor.userId(), courseId, BoardKind.OPEN, postId, false));
	}

	@PostMapping("/api/courses/{courseId}/open-board/posts/{postId}/read")
	ResponseEntity<ReadState> read(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long postId) {
		return ok(this.posts.read(actor.userId(), courseId, BoardKind.OPEN, postId));
	}

	@GetMapping("/api/courses/{courseId}/open-board/posts/{postId}/comments")
	ResponseEntity<CommentPage> comments(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long postId, @RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "50") int size) {
		return ok(this.comments.list(actor.userId(), courseId, postId, cursor, size));
	}

	/** 201. Idempotency-Key는 선택이다. */
	@PostMapping("/api/courses/{courseId}/open-board/posts/{postId}/comments")
	ResponseEntity<CommentView> comment(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long postId, @RequestBody CommentRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.comments.create(actor, courseId, postId, body, idempotencyKey));
	}

	@DeleteMapping("/api/courses/{courseId}/open-board/posts/{postId}/comments/{commentId}")
	ResponseEntity<Void> deleteComment(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long postId, @PathVariable long commentId) {
		this.comments.delete(actor, courseId, postId, commentId);
		return noContent();
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	private static <T> ResponseEntity<T> created(T body) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
	}

	private static ResponseEntity<Void> noContent() {
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

}
