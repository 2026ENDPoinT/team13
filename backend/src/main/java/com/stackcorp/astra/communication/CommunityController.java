package com.stackcorp.astra.communication;

import com.stackcorp.astra.communication.BoardPostService.ReadState;
import com.stackcorp.astra.communication.CommunityService.InquiryPage;
import com.stackcorp.astra.communication.CommunityService.InquiryRequest;
import com.stackcorp.astra.communication.CommunityService.InquiryView;
import com.stackcorp.astra.communication.CommunityService.MaterialPage;
import com.stackcorp.astra.communication.CommunityService.MaterialRequest;
import com.stackcorp.astra.communication.CommunityService.MaterialView;
import com.stackcorp.astra.communication.CommunityService.ReplyRequest;
import com.stackcorp.astra.communication.CommunityService.ReplyView;
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
 * 알림마당 자료실(EP-0151~0153, 화면 34)과 1:1 문의(EP-0158~0162, 화면 31·32). 로그인한 사용자 누구나 부르며 범위는
 * {@link CommunityService}가 정한다. 자료 쓰기와 문의 답변은 알림마당 운영자만 한다(ADR-0035). 원본의 HTML 폼 경로(EP-0152, EP-0159,
 * EP-0161)는 화면이 이 JSON으로 그리므로 따로 두지 않는다. 추가 질문(EP-0157)은 원본에서 쓰이지 않아(dormant) 만들지 않았다.
 */
@RestController
class CommunityController {

	private final CommunityService community;

	CommunityController(CommunityService community) {
		this.community = community;
	}

	/** EP-0151. audience는 all, common, professor, student. */
	@GetMapping("/api/community/materials")
	ResponseEntity<MaterialPage> materials(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) String audience, @RequestParam(required = false) String q,
			@RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int size) {
		return ok(this.community.materials(actor, audience, q, cursor, size));
	}

	/** EP-0153. 열람 기록을 남기지 않는다. */
	@GetMapping("/api/community/materials/{postId}")
	ResponseEntity<MaterialView> material(@AuthenticationPrincipal Actor actor, @PathVariable long postId) {
		return ok(this.community.material(actor, postId));
	}

	@PostMapping("/api/community/materials/{postId}/read")
	ResponseEntity<ReadState> readMaterial(@AuthenticationPrincipal Actor actor, @PathVariable long postId) {
		return ok(this.community.readMaterial(actor, postId));
	}

	/** 운영자의 자료 등록. 201. Idempotency-Key는 선택이다. */
	@PostMapping("/api/community/materials")
	ResponseEntity<MaterialView> createMaterial(@AuthenticationPrincipal Actor actor, @RequestBody MaterialRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.community.createMaterial(actor, body, idempotencyKey));
	}

	/** 운영자의 자료 전체 수정. 본문의 version이 다르면 409 version_conflict. */
	@PutMapping("/api/community/materials/{postId}")
	ResponseEntity<MaterialView> updateMaterial(@AuthenticationPrincipal Actor actor, @PathVariable long postId,
			@RequestBody MaterialRequest body) {
		return ok(this.community.updateMaterial(actor, postId, body));
	}

	/** 운영자의 자료 삭제. 204 */
	@DeleteMapping("/api/community/materials/{postId}")
	ResponseEntity<Void> deleteMaterial(@AuthenticationPrincipal Actor actor, @PathVariable long postId) {
		this.community.deleteMaterial(actor, postId);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** EP-0160. 본인 문의만. 운영자는 all=true로 모든 문의를 본다. status는 all, answered, unanswered. */
	@GetMapping("/api/community/qna")
	ResponseEntity<InquiryPage> inquiries(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int size,
			@RequestParam(defaultValue = "false") boolean all) {
		return ok(this.community.inquiries(actor, status, q, cursor, size, all));
	}

	/** EP-0162. 문의 작성자와 운영자. 문의와 그 답변. */
	@GetMapping("/api/community/qna/{postId}")
	ResponseEntity<InquiryView> inquiry(@AuthenticationPrincipal Actor actor, @PathVariable long postId) {
		return ok(this.community.inquiry(actor, postId));
	}

	/** EP-0158. 1:1 문의 작성. 201. Idempotency-Key는 선택이다. */
	@PostMapping("/api/community/qna")
	ResponseEntity<InquiryView> createInquiry(@AuthenticationPrincipal Actor actor, @RequestBody InquiryRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.community.createInquiry(actor, body, idempotencyKey));
	}

	/** 운영자의 답변(원본 ANS_QNA_DV의 답변 쪽). 201. Idempotency-Key는 선택이다. */
	@PostMapping("/api/community/qna/{postId}/replies")
	ResponseEntity<ReplyView> answer(@AuthenticationPrincipal Actor actor, @PathVariable long postId,
			@RequestBody ReplyRequest body,
			@RequestHeader(name = Idempotency.HEADER, required = false) String idempotencyKey) {
		return created(this.community.answer(actor, postId, body, idempotencyKey));
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

	private static <T> ResponseEntity<T> created(T body) {
		return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(body);
	}

}
