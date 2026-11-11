package com.stackcorp.astra.communication;

import com.stackcorp.astra.communication.BoardPostService.LikeState;
import com.stackcorp.astra.communication.BoardPostService.PostRequest;
import com.stackcorp.astra.communication.BoardPostService.PostView;
import com.stackcorp.astra.communication.BoardPostService.QuestionPage;
import com.stackcorp.astra.communication.BoardPostService.ReadState;
import com.stackcorp.astra.communication.BoardPostService.StatusRequest;
import com.stackcorp.astra.communication.BoardPostService.StatusView;
import com.stackcorp.astra.communication.QnaAnswerService.AnswerPage;
import com.stackcorp.astra.communication.QnaAnswerService.AnswerRequest;
import com.stackcorp.astra.communication.QnaAnswerService.AnswerView;
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
 * 강의실 질의응답(EP-0061~0074의 대체 계약, 화면 49·50·51). 권한과 시각 판정은 {@link BoardPostService}와 {@link QnaAnswerService}에
 * 있다. 상세 조회는 열람 기록을 남기지 않는다. 읽음은 POST 명령이다(SC-04). 추천은 PUT(추천)과 DELETE(취소)로 목표 상태를 정한다.
 * 학습활동에 연결한 질문(EP-0059, EP-0060, T_MENU_ID)은 아직 받지 않는다.
 */
@RestController
class QnaController {

	private final BoardPostService posts;

	private final QnaAnswerService answers;

	QnaController(BoardPostService posts, QnaAnswerService answers) {
		this.posts = posts;
		this.answers = answers;
	}

	/** EP-0070. status는 all, faq, solved, unsolved. answered는 "나의 답변", mine은 "나의 질문". */
	@GetMapping("/api/courses/{courseId}/qna")
	ResponseEntity<QuestionPage> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(required = false) String q, @RequestParam(required = false) String status,
			@RequestParam(defaultValue = "false") boolean mine, @RequestParam(defaultValue = "false") boolean answered,
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "8") int size,
			@RequestParam(required = false) Long snapshot) {
		return ok(this.posts.questions(actor.userId(), courseId, q, status, mine, answered, page, size, snapshot));
	}

	/** EP-0074. */
	@GetMapping("/api/courses/{courseId}/qna/{questionId}")
	ResponseEntity<PostView> one(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId) {
		return ok(this.posts.get(actor.userId(), courseId, BoardKind.QNA, questionId));
	}

	/** EP-0068. 201. Idempotency-Key는 선택이다. 같은 key로 다시 보내면 처음 만든 질문을 돌려준다. */
	@PostMapping("/api/courses/{courseId}/qna")
	ResponseEntity<PostView> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody PostRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.posts.create(actor, courseId, BoardKind.QNA, body, idempotencyKey));
	}

	/** EP-0073의 수정 제출. 전체 교체. */
	@PutMapping("/api/courses/{courseId}/qna/{questionId}")
	ResponseEntity<PostView> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId, @RequestBody PostRequest body) {
		return ok(this.posts.update(actor, courseId, BoardKind.QNA, questionId, body));
	}

	/** EP-0067. */
	@DeleteMapping("/api/courses/{courseId}/qna/{questionId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId) {
		this.posts.delete(actor, courseId, BoardKind.QNA, questionId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** EP-0072. 목표 상태를 받는다. */
	@PutMapping("/api/courses/{courseId}/qna/{questionId}/status")
	ResponseEntity<StatusView> status(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId, @RequestBody StatusRequest body) {
		return ok(this.posts.status(actor, courseId, questionId, body));
	}

	/** EP-0062. */
	@PutMapping("/api/courses/{courseId}/qna/{questionId}/like")
	ResponseEntity<LikeState> like(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId) {
		return ok(this.posts.like(actor.userId(), courseId, BoardKind.QNA, questionId, true));
	}

	/** EP-0061. */
	@DeleteMapping("/api/courses/{courseId}/qna/{questionId}/like")
	ResponseEntity<LikeState> unlike(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId) {
		return ok(this.posts.like(actor.userId(), courseId, BoardKind.QNA, questionId, false));
	}

	@PostMapping("/api/courses/{courseId}/qna/{questionId}/read")
	ResponseEntity<ReadState> read(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId) {
		return ok(this.posts.read(actor.userId(), courseId, BoardKind.QNA, questionId));
	}

	/** EP-0065. */
	@GetMapping("/api/courses/{courseId}/qna/{questionId}/answers")
	ResponseEntity<AnswerPage> answers(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId, @RequestParam(required = false) Long cursor,
			@RequestParam(defaultValue = "20") int size) {
		return ok(this.answers.list(actor.userId(), courseId, questionId, cursor, size));
	}

	/** EP-0064. 201. Idempotency-Key는 선택이다. */
	@PostMapping("/api/courses/{courseId}/qna/{questionId}/answers")
	ResponseEntity<AnswerView> answer(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId, @RequestBody AnswerRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.answers.create(actor, courseId, questionId, body, idempotencyKey));
	}

	/** EP-0066의 수정 제출. */
	@PutMapping("/api/courses/{courseId}/qna/{questionId}/answers/{answerId}")
	ResponseEntity<AnswerView> updateAnswer(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId, @PathVariable long answerId, @RequestBody AnswerRequest body) {
		return ok(this.answers.update(actor, courseId, questionId, answerId, body));
	}

	/** EP-0063. */
	@DeleteMapping("/api/courses/{courseId}/qna/{questionId}/answers/{answerId}")
	ResponseEntity<Void> deleteAnswer(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long questionId, @PathVariable long answerId) {
		this.answers.delete(actor, courseId, questionId, answerId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	private static <T> ResponseEntity<T> created(T body) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
	}

}
