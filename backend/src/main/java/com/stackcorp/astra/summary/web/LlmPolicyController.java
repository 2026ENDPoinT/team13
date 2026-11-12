package com.stackcorp.astra.summary.web;

import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.summary.api.CourseStaff;
import com.stackcorp.astra.summary.chat.StudyChatProperties;
import com.stackcorp.astra.summary.policy.Consent;
import com.stackcorp.astra.summary.policy.ConsentService;
import com.stackcorp.astra.summary.policy.LlmGate;
import com.stackcorp.astra.summary.policy.ProcessingScope;
import com.stackcorp.astra.summary.policy.ProviderProfile;
import com.stackcorp.astra.summary.policy.UsageLedger;
import com.stackcorp.astra.summary.policy.UsageLedger.Total;

/**
 * 외부 LLM 처리 조건, 교수자 동의, 과목 사용량(ADR-0032). 처리 조건은 로그인한 누구나 본다. 사용량, 내 동의 목록, 철회는 그 과목
 * 담당자가 하고, 동의는 과목의 결정권자(담당자 명단의 교수와 비정규 담당자)만 한다. 해당하지 않으면 404다. 동의는 본인의 것만
 * 기록하고 철회한다. 철회는 처리를 좁히기만 하므로 조교도 예전에 남긴 자기 동의를 거둘 수 있다.
 */
@RestController
class LlmPolicyController {

	private final ConsentService consents;

	private final LlmGate gate;

	private final CourseStaff staff;

	private final UsageLedger usage;

	private final StudyChatProperties chat;

	LlmPolicyController(ConsentService consents, LlmGate gate, CourseStaff staff, UsageLedger usage,
			StudyChatProperties chat) {
		this.consents = consents;
		this.gate = gate;
		this.staff = staff;
		this.usage = usage;
		this.chat = chat;
	}

	/** 이 배포의 제공자 profile. 교수자는 이 값과 profileDigest를 보고 동의한다. */
	@GetMapping("/api/llm/policy")
	ResponseEntity<PolicyView> policy() {
		ProviderProfile p = this.gate.profile();
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new PolicyView(this.gate.enabled(), p.providerName(), p.providerOperator(), p.processingRegion(),
					p.retentionDays(), p.trainingUse(), p.crossBorderTransfer(), p.noticeVersion(), p.digest(),
					p.acknowledgementRequired(), Arrays.stream(ProcessingScope.values()).map(ProcessingScope::value).toList()));
	}

	/**
	 * 내가 이 과목에 남긴 동의. 범위마다 하나이고, 동의하지 않은 범위는 granted=false다. 결정권자가 아닌 사람(조교 등)의 동의 행은
	 * granted=true, current=false로 보인다. 문을 열지 않으므로 효력은 없고, 철회는 할 수 있다.
	 */
	@GetMapping("/api/courses/{courseId}/llm-consents")
	ResponseEntity<List<ConsentView>> list(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		requireInstructor(actor, courseId);
		boolean decider = this.staff.isDecider(actor.userId(), courseId);
		Map<ProcessingScope, Consent> active = new EnumMap<>(ProcessingScope.class);
		this.consents.active(courseId, actor.userId()).forEach((c) -> active.put(c.scope(), c));
		List<ConsentView> views = Arrays.stream(ProcessingScope.values())
			.map((s) -> active.containsKey(s) ? ConsentView.of(active.get(s), decider) : ConsentView.none(s))
			.toList();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(views);
	}

	/**
	 * 동의한다. noticeVersion과 profileDigest는 화면에 보여 준 {@code GET /api/llm/policy}의 값이다. 같은 profile로 다시 보내면
	 * 이미 한 동의를 돌려준다.
	 */
	@PutMapping("/api/courses/{courseId}/llm-consents/{scope}")
	ResponseEntity<ConsentView> grant(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable String scope, @RequestBody GrantRequest body) {
		requireDecider(actor, courseId);
		ProcessingScope target = scope(scope);
		if (body == null || body.noticeVersion() == null || body.profileDigest() == null) {
			throw ApiException.invalid("noticeVersion과 profileDigest 값이 필요합니다.");
		}
		Consent consent = this.consents.grant(actor.userId(), actor.sessionId(), courseId, target, body.noticeVersion(),
				body.profileDigest(), Boolean.TRUE.equals(body.acknowledged()));
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ConsentView.of(consent, true));
	}

	/**
	 * 철회한다. 그 교수자의 동의에 기대던 대기 작업과 질문은 취소되고, 실행 중인 작업은 다음 모델 호출 전에 멈춘다. 게시한 요약은 남는다.
	 * 동의가 없어도 204다. 본인의 동의만 거두고 처리를 좁히기만 하므로 결정권자가 아닌 담당자(조교 등)도 한다.
	 */
	@DeleteMapping("/api/courses/{courseId}/llm-consents/{scope}")
	ResponseEntity<Void> revoke(@AuthenticationPrincipal Actor actor, @PathVariable long courseId,
			@PathVariable String scope) {
		requireInstructor(actor, courseId);
		this.consents.revoke(actor.userId(), actor.sessionId(), courseId, scope(scope));
		return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build();
	}

	/** 과목의 누적 LLM 사용량과 오늘의 학습 대화 사용량. 본문과 사용자는 없다. */
	@GetMapping("/api/courses/{courseId}/llm-usage")
	ResponseEntity<UsageView> usage(@AuthenticationPrincipal Actor actor, @PathVariable long courseId) {
		requireInstructor(actor, courseId);
		Map<ProcessingScope, Total> totals = this.usage.totals(courseId);
		return ResponseEntity.ok()
			.cacheControl(CacheControl.noStore())
			.body(new UsageView(totals.get(ProcessingScope.PAGE_SUMMARY), totals.get(ProcessingScope.STUDY_CHAT),
					this.usage.todayTokens(courseId, ProcessingScope.STUDY_CHAT), this.chat.dailyTokenBudget()));
	}

	private void requireInstructor(Actor actor, long courseId) {
		if (!this.staff.isInstructor(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
	}

	/** 수강 신청 처리처럼 조교는 하지 않는다. 거부는 담당이 아닐 때와 같은 404다. */
	private void requireDecider(Actor actor, long courseId) {
		if (!this.staff.isDecider(actor.userId(), courseId)) {
			throw ApiException.notFound();
		}
	}

	private static ProcessingScope scope(String value) {
		return ProcessingScope.of(value).orElseThrow(ApiException::notFound);
	}

	/**
	 * @param acknowledged 국외 이전·학습 이용을 확인했는지. profile의 acknowledgementRequired가 true면 true여야 한다
	 */
	record GrantRequest(String noticeVersion, String profileDigest, Boolean acknowledged) {
	}

	/**
	 * @param retentionDays 0이면 보관하지 않음, null이면 제공자가 밝히지 않음
	 * @param scopes 동의 범위 값
	 */
	record PolicyView(boolean enabled, String providerName, String providerOperator, String processingRegion,
			Integer retentionDays, boolean trainingUse, boolean crossBorderTransfer, String noticeVersion,
			String profileDigest, boolean acknowledgementRequired, List<String> scopes) {
	}

	/**
	 * @param current 이 동의가 지금 문을 여는지. 동의한 profile이 지금 배포의 profile과 같고, 동의한 사람이 지금도 과목의
	 * 결정권자여야 한다. false면 효력이 없다. 결정권자는 다시 동의하고, 결정권자가 아닌 사람은 철회만 할 수 있다
	 */
	record ConsentView(String scope, boolean granted, boolean current, String noticeVersion, String profileDigest,
			boolean acknowledged, Instant grantedAt) {

		/** @param decider 동의한 사람이 지금 과목의 결정권자인지 */
		static ConsentView of(Consent c, boolean decider) {
			return new ConsentView(c.scope().value(), true, decider && c.current(), c.noticeVersion(),
					c.profileSha256(), c.riskAcknowledged(), c.grantedAt());
		}

		static ConsentView none(ProcessingScope scope) {
			return new ConsentView(scope.value(), false, false, null, null, false, null);
		}

	}

	/**
	 * @param studyChatTodayTokens 오늘(DB 날짜) 학습 대화가 쓴 입력·출력 token 합
	 */
	record UsageView(Total pageSummary, Total studyChat, long studyChatTodayTokens, long studyChatDailyTokenBudget) {
	}

}
