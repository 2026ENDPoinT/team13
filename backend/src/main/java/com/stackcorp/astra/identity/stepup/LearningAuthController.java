package com.stackcorp.astra.identity.stepup;

import java.time.Duration;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.stepup.LearningAuth.Created;
import com.stackcorp.astra.identity.stepup.LearningAuth.Status;
import com.stackcorp.astra.identity.stepup.LearningAuth.Verified;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimiter;

/**
 * 온라인 학습 전 2차 본인인증(EP-0292~EP-0295의 대체 계약, SC-06-A, ADR-0029). 모든 경로는 요청한 로그인 session의 것만 다룬다.
 * <ul>
 * <li>상태 조회는 서버가 grant를 가졌는지만 알린다. client는 {@code granted:true}일 때만 학습을 시작한다. 학습 시작은 서버가 다시
 * 판정하므로 이 응답을 바꿔도 적립되지 않는다(SEC-04, IDENTITY-03).</li>
 * <li>발송 대상은 서버가 정한다. 요청은 수단 이름만 보낸다(IDENTITY-04).</li>
 * <li>challenge 시작과 확인에는 사용자 단위 요청 제한이 있다. 확인 실패는 challenge와 사용자 단위로 잠긴다(IDENTITY-05).</li>
 * </ul>
 * challenge 시작은 같은 session의 앞선 challenge를 취소하므로 다시 보내도 쓸 수 있는 challenge가 늘지 않는다. 그래서
 * {@code Idempotency-Key}를 받지 않는다.
 */
@RestController
class LearningAuthController {

	private final LearningAuth learningAuth;

	private final RateLimiter rateLimiter;

	LearningAuthController(LearningAuth learningAuth, RateLimiter rateLimiter) {
		this.learningAuth = learningAuth;
		this.rateLimiter = rateLimiter;
	}

	/** 지금 학습 인증 grant가 있는지와 쓸 수 있는 수단(EP-0295, EP-0294). */
	@GetMapping("/api/me/learning-auth")
	ResponseEntity<Status> status(@AuthenticationPrincipal Actor actor) {
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(this.learningAuth.status(actor));
	}

	/** challenge 시작(EP-0293). 201. */
	@PostMapping("/api/me/learning-auth/challenges")
	ResponseEntity<Created> create(@AuthenticationPrincipal Actor actor, @RequestBody CreateChallenge body) {
		if (body.channel() == null || body.channel().length() > 20) {
			throw ApiException.invalid("channel이 필요합니다.");
		}
		this.rateLimiter.check("learning-auth-challenge", actor.userId(), 10, Duration.ofMinutes(15));
		return ResponseEntity.status(HttpStatus.CREATED)
			.cacheControl(CacheControl.noStore())
			.body(this.learningAuth.create(actor, body.channel()));
	}

	/**
	 * 확인(EP-0292). 코드가 없는 수단({@code idp})은 본문을 비우거나 {@code {}}를 보낸다. 그 밖의 필드는 400이다. 근거가 없으면 400
	 * {@code verification_failed}이고 시도 수가 오른다.
	 */
	@PostMapping("/api/me/learning-auth/challenges/{challengeId}/verify")
	ResponseEntity<Verified> verify(@AuthenticationPrincipal Actor actor, @PathVariable String challengeId,
			@RequestBody(required = false) VerifyChallenge body) {
		this.rateLimiter.check("learning-auth-verify", actor.userId(), 30, Duration.ofMinutes(15));
		String code = body == null ? null : body.code();
		if (code != null && (code.isEmpty() || code.length() > 16)) {
			throw ApiException.invalid("code 형식이 올바르지 않습니다.");
		}
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(this.learningAuth.verify(actor, challengeId, code));
	}

	/** 이 session의 학습 인증을 끝낸다. 진행 중인 학습은 이 시각 뒤로 적립하지 않는다. 204. */
	@PostMapping("/api/me/learning-auth/revoke")
	ResponseEntity<Void> revoke(@AuthenticationPrincipal Actor actor) {
		this.learningAuth.revoke(actor);
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** @param channel 2차 인증 수단. 지금은 {@code idp}만 있다 */
	record CreateChallenge(String channel) {
	}

	/** @param code 코드가 있는 수단의 입력값 */
	record VerifyChallenge(String code) {

		// 요청 본문을 로그에 찍는 설정에서도 코드가 나가지 않게 한다(SC-07).
		@Override
		public String toString() {
			return "VerifyChallenge[]";
		}

	}

}
