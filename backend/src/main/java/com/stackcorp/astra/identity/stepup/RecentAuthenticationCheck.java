package com.stackcorp.astra.identity.stepup;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.identity.api.Actor;
import com.stackcorp.astra.identity.api.RecentAuthentication;
import com.stackcorp.astra.shared.ApiException;

/**
 * 재인증 확인(ADR-0029). 이 로그인 session에 {@code maxAge} 안의 재인증 기록이 있어야 한다. 없으면 403
 * {@code step_up_required}다. 사용자는 이미 그 대상의 주인이므로 존재를 숨기는 404가 아니다.
 */
@Service
@EnableConfigurationProperties(StepUpProperties.class)
class RecentAuthenticationCheck implements RecentAuthentication {

	private final StepUps stepUps;

	private final StepUpProperties properties;

	RecentAuthenticationCheck(StepUps stepUps, StepUpProperties properties) {
		this.stepUps = stepUps;
		this.properties = properties;
	}

	@Override
	public void requireRecentAuth(Actor actor, Duration maxAge) {
		Duration window = maxAge.compareTo(this.properties.recentAuth()) < 0 ? maxAge : this.properties.recentAuth();
		if (!this.stepUps.recent(actor.sessionId(), StepUps.RECENT_AUTH, window)) {
			throw new ApiException(HttpStatus.FORBIDDEN, STEP_UP_REQUIRED, "본인 확인을 위해 다시 로그인해 주세요.");
		}
	}

}
