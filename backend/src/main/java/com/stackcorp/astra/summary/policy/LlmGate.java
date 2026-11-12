package com.stackcorp.astra.summary.policy;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.summary.api.CourseStaff;

/**
 * 과목 자료의 내용을 외부 LLM에 보내기 전에 거치는 문(ADR-0032). 다음을 모두 만족해야 열린다.
 * <ul>
 * <li>배포 스위치 {@code astra.llm.enabled}가 켜져 있다.</li>
 * <li>책임 교수자가 그 과목, 그 범위에 지금 배포의 제공자 profile로 동의했고 철회하지 않았다.</li>
 * <li>그 교수자가 지금도 과목 담당이다.</li>
 * </ul>
 * 책임 교수자는 쪽별 요약이면 생성을 요청한 교수자, 학습 대화면 그 파일의 요약을 게시한 교수자다. api는 명령을 받을 때, worker는
 * 모델을 부르기 전마다 다시 확인한다.
 */
@Component
public class LlmGate {

	/** 닫힌 이유: 배포 스위치가 꺼졌다. */
	public static final String DISABLED = "disabled";

	/** 닫힌 이유: 유효한 동의가 없다. */
	public static final String CONSENT = "consent";

	private final LlmPolicyProperties properties;

	private final ConsentRepository consents;

	private final CourseStaff staff;

	LlmGate(LlmPolicyProperties properties, ConsentRepository consents, CourseStaff staff) {
		this.properties = properties;
		this.consents = consents;
		this.staff = staff;
	}

	public boolean enabled() {
		return this.properties.enabled();
	}

	public ProviderProfile profile() {
		return this.properties.policy();
	}

	/**
	 * @throws ApiException 503 {@code llm_disabled}
	 */
	public void requireEnabled() {
		if (!enabled()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "llm_disabled", "이 서비스에서는 외부 AI 기능을 쓰지 않습니다.");
		}
	}

	/**
	 * @throws ApiException 503 {@code llm_disabled}, 409 {@code llm_consent_required}
	 */
	public void require(long courseId, Long responsibleUserId, ProcessingScope scope) {
		requireEnabled();
		if (closed(courseId, responsibleUserId, scope).isPresent()) {
			throw new ApiException(HttpStatus.CONFLICT, "llm_consent_required",
					"담당 교수자가 이 기능의 외부 AI 처리에 동의하지 않았습니다.");
		}
	}

	/** @return 닫혔으면 그 이유({@link #DISABLED} 또는 {@link #CONSENT}) */
	public Optional<String> closed(Long courseId, Long responsibleUserId, ProcessingScope scope) {
		if (!enabled()) {
			return Optional.of(DISABLED);
		}
		if (courseId == null || responsibleUserId == null
				|| !this.consents.hasCurrent(courseId, responsibleUserId, scope, profile().digest())
				|| !this.staff.isInstructor(responsibleUserId, courseId)) {
			return Optional.of(CONSENT);
		}
		return Optional.empty();
	}

}
