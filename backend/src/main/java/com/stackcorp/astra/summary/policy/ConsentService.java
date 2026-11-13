package com.stackcorp.astra.summary.policy;

import java.util.List;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;

import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.summary.policy.ConsentRepository.Row;

/**
 * 교수자의 외부 LLM 처리 동의와 철회(ADR-0032). 과목 담당 여부는 부르는 쪽이 먼저 확인한다.
 * <ul>
 * <li>동의 요청은 교수자가 본 고지 판과 profile 지문을 담는다. 지금 배포의 값과 다르면 409 {@code llm_policy_changed}다. 보여 준
 * 내용과 기록한 내용이 어긋나지 않게 하기 위해서다.</li>
 * <li>profile이 국외 이전이나 학습 이용을 밝히면 {@code acknowledged=true}가 있어야 한다. 없으면 422
 * {@code llm_acknowledgement_required}다.</li>
 * <li>같은 profile로 이미 동의했으면 그 동의를 돌려준다. 다른 profile의 동의가 남아 있으면 철회로 닫고 새로 기록한다.</li>
 * <li>철회는 행을 지우지 않고 철회 일시를 남긴다. 같은 transaction에서 {@link ConsentRevoked}를 알려 대기 중인 작업과 질문을
 * 취소한다. 이미 게시한 요약은 그대로 둔다. 게시 내리기는 교수자가 따로 한다.</li>
 * </ul>
 * 동의와 철회는 감사 기록에 남긴다. 동의 행 잠금을 먼저 잡고 작업 행을 바꾼다.
 */
@Service
public class ConsentService {

	private final ConsentRepository consents;

	private final LlmPolicyProperties properties;

	private final AuditLog audit;

	private final ApplicationEventPublisher events;

	private final ReadCommitted transaction;

	ConsentService(ConsentRepository consents, LlmPolicyProperties properties, AuditLog audit,
			ApplicationEventPublisher events, PlatformTransactionManager transactionManager) {
		this.consents = consents;
		this.properties = properties;
		this.audit = audit;
		this.events = events;
		this.transaction = new ReadCommitted(transactionManager);
	}

	public ProviderProfile profile() {
		return this.properties.policy();
	}

	/** 교수자가 그 과목에 남긴 유효한 동의. 범위 순서다. */
	public List<Consent> active(long courseId, long instructorUserId) {
		String current = profile().digest();
		return this.consents.active(courseId, instructorUserId).stream().map((r) -> r.toConsent(current)).toList();
	}

	public Consent grant(long actorUserId, long sessionId, long courseId, ProcessingScope scope, String noticeVersion,
			String profileSha256, boolean acknowledged) {
		ProviderProfile profile = profile();
		if (!profile.noticeVersion().equals(noticeVersion) || !profile.digest().equals(profileSha256)) {
			throw new ApiException(HttpStatus.CONFLICT, "llm_policy_changed", "처리 조건이 바뀌었습니다. 새 고지를 확인하고 다시 동의해 주세요.");
		}
		if (profile.acknowledgementRequired() && !acknowledged) {
			throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "llm_acknowledgement_required",
					"국외 이전과 제공자의 이용 조건을 확인해야 동의할 수 있습니다.");
		}
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute(() -> {
					Optional<Row> existing = this.consents.lockActive(courseId, actorUserId, scope);
					if (existing.isPresent() && existing.get().profileSha256().equals(profile.digest())) {
						return existing.get().toConsent(profile.digest());
					}
					existing.ifPresent((r) -> this.consents.revoke(r.id()));
					long id = this.consents.insert(courseId, actorUserId, scope, profile, acknowledged);
					this.audit.success(actorUserId, sessionId, "llm_consent.grant", "llm_consent", id, courseId);
					return this.consents.find(id).toConsent(profile.digest());
				});
			}
			catch (DuplicateKeyException ex) {
				// 같은 교수자의 동시 요청이 먼저 기록했다. 다시 읽으면 그 동의를 돌려준다.
				if (attempt >= 2) {
					throw ex;
				}
			}
		}
	}

	/** @return 유효한 동의가 있어 철회했으면 true */
	public boolean revoke(long actorUserId, long sessionId, long courseId, ProcessingScope scope) {
		return this.transaction.execute(() -> {
			Optional<Row> existing = this.consents.lockActive(courseId, actorUserId, scope);
			if (existing.isEmpty() || !this.consents.revoke(existing.get().id())) {
				return false;
			}
			this.events.publishEvent(new ConsentRevoked(courseId, actorUserId, scope));
			this.audit.success(actorUserId, sessionId, "llm_consent.revoke", "llm_consent", existing.get().id(),
					courseId);
			return true;
		});
	}

}
