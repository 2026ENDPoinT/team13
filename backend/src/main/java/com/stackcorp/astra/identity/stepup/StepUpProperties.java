package com.stackcorp.astra.identity.stepup;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 재인증과 학습 인증의 시간(ADR-0029).
 *
 * @param recentAuth 재인증 한 번이 민감한 연산을 허용하는 최대 시간. 연산마다 더 짧게 요구할 수 있다
 * @param learningGrant 학습 인증 grant의 유효 시간. 로그인 session의 절대 만료를 넘지 않는다(SC-06-A)
 * @param challenge 학습 인증 challenge를 시작한 뒤 확인할 수 있는 시간
 * @param challengeAttempts challenge 하나의 확인 시도 한도. 넘으면 그 challenge는 잠긴다
 * @param lockoutAttempts 아래 시간 안에 사용자의 challenge 확인 실패가 이만큼이면 새 challenge를 만들지 못한다
 * @param lockoutWindow 사용자 단위 잠금을 세는 시간
 */
@ConfigurationProperties("astra.step-up")
public record StepUpProperties(@DefaultValue("10m") Duration recentAuth, @DefaultValue("2h") Duration learningGrant,
		@DefaultValue("5m") Duration challenge, @DefaultValue("5") int challengeAttempts,
		@DefaultValue("10") int lockoutAttempts, @DefaultValue("15m") Duration lockoutWindow) {

	public StepUpProperties {
		if (recentAuth.compareTo(Duration.ofMinutes(1)) < 0 || recentAuth.compareTo(Duration.ofHours(1)) > 0) {
			throw new IllegalArgumentException("astra.step-up.recent-auth는 1분에서 1시간 사이여야 합니다.");
		}
		if (learningGrant.compareTo(Duration.ofMinutes(5)) < 0 || learningGrant.compareTo(Duration.ofHours(12)) > 0) {
			throw new IllegalArgumentException("astra.step-up.learning-grant는 5분에서 12시간 사이여야 합니다.");
		}
		if (challenge.compareTo(Duration.ofMinutes(1)) < 0 || challenge.compareTo(Duration.ofMinutes(15)) > 0) {
			throw new IllegalArgumentException("astra.step-up.challenge는 1분에서 15분 사이여야 합니다.");
		}
		if (challengeAttempts < 1 || challengeAttempts > 10 || lockoutAttempts < challengeAttempts
				|| lockoutWindow.isNegative() || lockoutWindow.isZero()) {
			throw new IllegalArgumentException("astra.step-up의 시도 한도와 잠금 시간이 올바르지 않습니다.");
		}
	}

}
