package com.stackcorp.astra.communication;

import com.stackcorp.astra.communication.NoticeService.NoticePage;
import com.stackcorp.astra.communication.NoticeService.NoticeRequest;
import com.stackcorp.astra.communication.NoticeService.NoticeState;
import com.stackcorp.astra.communication.NoticeService.NoticeView;
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
 * 강의실 공지(EP-0044, EP-0045, EP-0046, EP-0047, EP-0095의 대체 계약). 담당자가 쓰고 고치고 지우며, 과목 구성원이 목록과 상세를 본다.
 * 원본처럼 상세 조회가 열람 기록을 남기지 않는다. 읽음과 확인은 각각 POST 명령이다(SC-04). 열람·확인 수는 담당자에게만 준다(EP-0006).
 * 권한과 시각 판정은 {@link NoticeService}에 있다.
 */
@RestController
class NoticeController {

	private final NoticeService notices;

	NoticeController(NoticeService notices) {
		this.notices = notices;
	}

	/**
	 * 고정 공지 먼저, 그다음 게시 일시 역순. q는 제목에 들어 있는 글자를 찾는다. 학생에게는 그 뒤에 학교 강의실 공지(ADR-0043,
	 * {@code noticeId: "school-<id>"}, {@code origin: "school"})가 학교 목록 순서로 붙는다. 학교 공지 상세는
	 * {@code GET /api/courses/{courseId}/notices/school-<id>}(learning)이다.
	 */
	@GetMapping("/api/courses/{courseId}/notices")
	ResponseEntity<NoticePage> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "8") int size) {
		return ok(this.notices.list(actor, courseId, q, page, size));
	}

	@GetMapping("/api/courses/{courseId}/notices/{noticeId}")
	ResponseEntity<NoticeView> one(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long noticeId) {
		return ok(this.notices.get(actor.userId(), courseId, noticeId));
	}

	/**
	 * 201. 같은 Idempotency-Key로 같은 요청을 다시 보내면 처음 응답을 그대로 돌려받고 공지를 더 만들지 않는다. 같은 key에 다른
	 * 요청이면 409 idempotency_conflict다(SC-05, ADR-0012).
	 */
	@PostMapping("/api/courses/{courseId}/notices")
	ResponseEntity<NoticeView> create(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@RequestBody NoticeRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(this.notices.create(actor, courseId, body, idempotencyKey));
	}

	/** 전체 교체. 본문의 version이 저장된 값과 다르면 409(version_conflict). */
	@PutMapping("/api/courses/{courseId}/notices/{noticeId}")
	ResponseEntity<NoticeView> update(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long noticeId, @RequestBody NoticeRequest body) {
		return ok(this.notices.update(actor, courseId, noticeId, body));
	}

	@DeleteMapping("/api/courses/{courseId}/notices/{noticeId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long noticeId) {
		this.notices.delete(actor, courseId, noticeId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** 읽음으로 표시한다. 몇 번 보내도 결과가 같고 조회수는 처음 한 번만 오른다(EP-0047). */
	@PostMapping("/api/courses/{courseId}/notices/{noticeId}/read")
	ResponseEntity<NoticeState> read(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long noticeId) {
		return ok(this.notices.markRead(actor.userId(), courseId, noticeId));
	}

	/** "공지를 확인했습니다." 원본의 토글 대신 확인 상태로 만드는 명령이다. 몇 번 보내도 결과가 같다(EP-0044). */
	@PostMapping("/api/courses/{courseId}/notices/{noticeId}/confirm")
	ResponseEntity<NoticeState> confirm(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable long noticeId) {
		return ok(this.notices.confirm(actor.userId(), courseId, noticeId));
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

}
