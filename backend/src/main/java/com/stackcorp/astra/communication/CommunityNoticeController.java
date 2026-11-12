package com.stackcorp.astra.communication;

import com.stackcorp.astra.communication.CommunityNoticeService.NoticePage;
import com.stackcorp.astra.communication.CommunityNoticeService.NoticeRequest;
import com.stackcorp.astra.communication.CommunityNoticeService.NoticeView;
import com.stackcorp.astra.communication.CommunityNoticeService.ReadState;
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
 * 알림마당 공지(EP-0154~0156, 홈 공지 상세 EP-0196의 대체 계약, 화면 30·15). 로그인한 사용자 누구나 부르며 범위는
 * {@link CommunityNoticeService}가 정한다. 쓰기는 알림마당 운영자만 한다(ADR-0035). 원본의 폼 경로(EP-0155)는 두지 않는다.
 */
@RestController
class CommunityNoticeController {

	private final CommunityNoticeService notices;

	CommunityNoticeController(CommunityNoticeService notices) {
		this.notices = notices;
	}

	/** EP-0154. audience는 all, common, professor, student. 고정 공지 먼저, 게시 일시 역순. */
	@GetMapping("/api/community/notices")
	ResponseEntity<NoticePage> list(@AuthenticationPrincipal Actor actor, @RequestParam(required = false) String audience,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int size) {
		return ok(this.notices.list(actor, audience, q, page, size));
	}

	/** EP-0156, EP-0196. 열람 기록을 남기지 않는다. */
	@GetMapping("/api/community/notices/{noticeId}")
	ResponseEntity<NoticeView> get(@AuthenticationPrincipal Actor actor, @PathVariable long noticeId) {
		return ok(this.notices.get(actor, noticeId));
	}

	@PostMapping("/api/community/notices/{noticeId}/read")
	ResponseEntity<ReadState> read(@AuthenticationPrincipal Actor actor, @PathVariable long noticeId) {
		return ok(this.notices.read(actor, noticeId));
	}

	/** 운영자의 공지 등록. 201. Idempotency-Key는 선택이다. */
	@PostMapping("/api/community/notices")
	ResponseEntity<NoticeView> create(@AuthenticationPrincipal Actor actor, @RequestBody NoticeRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(this.notices.create(actor, body, idempotencyKey));
	}

	/** 운영자의 공지 전체 수정. 본문의 version이 다르면 409 version_conflict. */
	@PutMapping("/api/community/notices/{noticeId}")
	ResponseEntity<NoticeView> update(@AuthenticationPrincipal Actor actor, @PathVariable long noticeId,
			@RequestBody NoticeRequest body) {
		return ok(this.notices.update(actor, noticeId, body));
	}

	/** 운영자의 공지 삭제. 204 */
	@DeleteMapping("/api/community/notices/{noticeId}")
	ResponseEntity<Void> delete(@AuthenticationPrincipal Actor actor, @PathVariable long noticeId) {
		this.notices.delete(actor, noticeId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

}
