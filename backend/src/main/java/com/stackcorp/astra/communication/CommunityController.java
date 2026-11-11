package com.stackcorp.astra.communication;

import com.stackcorp.astra.communication.BoardPostService.ReadState;
import com.stackcorp.astra.communication.CommunityService.InquiryPage;
import com.stackcorp.astra.communication.CommunityService.InquiryView;
import com.stackcorp.astra.communication.CommunityService.MaterialPage;
import com.stackcorp.astra.communication.CommunityService.MaterialView;
import com.stackcorp.astra.identity.api.Actor;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 알림마당 자료실(EP-0151~0153, 화면 34)과 1:1 문의 조회(EP-0160~0162, 화면 31). 로그인한 사용자 누구나 부르며 범위는
 * {@link CommunityService}가 정한다. 1:1 문의 작성(EP-0157~0159)은 운영 답변 화면과 함께 만든다.
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

	/** EP-0160. 본인 문의만. status는 all, answered, unanswered. */
	@GetMapping("/api/community/qna")
	ResponseEntity<InquiryPage> inquiries(@AuthenticationPrincipal Actor actor,
			@RequestParam(required = false) String status, @RequestParam(required = false) String q,
			@RequestParam(required = false) Long cursor, @RequestParam(defaultValue = "20") int size) {
		return ok(this.community.inquiries(actor, status, q, cursor, size));
	}

	/** EP-0162. 본인 문의와 그 답변. */
	@GetMapping("/api/community/qna/{postId}")
	ResponseEntity<InquiryView> inquiry(@AuthenticationPrincipal Actor actor, @PathVariable long postId) {
		return ok(this.community.inquiry(actor, postId));
	}

	private static <T> ResponseEntity<T> ok(T body) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
	}

}
