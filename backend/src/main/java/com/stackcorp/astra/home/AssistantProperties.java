package com.stackcorp.astra.home;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 화면 AI 도우미 설정(ADR-0044). 모델 제공자 주소, key, 외부 AI 스위치는 {@code astra.llm.*}를 그대로 쓴다.
 *
 * @param enabled 이 기능 전체를 끄는 스위치. false면 규칙 답도 하지 않고 503 {@code llm_disabled}다
 * @param timeout 모델 답을 기다리는 시간(동시 요청 자리를 기다리는 시간 포함)
 * @param requestRateLimit 한 사용자가 rateWindow 안에 보낼 수 있는 요청 수. 규칙 답과 모델 답 모두 센다
 * @param rateLimit 한 사용자가 rateWindow 안에 모델을 부를 수 있는 수
 * @param totalRateLimit 모든 사용자를 합해 rateWindow 안에 모델을 부를 수 있는 수. 모델 제공자의 요청 한도를 지킨다
 * @param maxOutputTokens 모델 호출 하나의 출력 token 상한
 */
@ConfigurationProperties("astra.assistant")
record AssistantProperties(@DefaultValue("true") boolean enabled, @DefaultValue("20s") Duration timeout,
		@DefaultValue("30") int requestRateLimit, @DefaultValue("10") int rateLimit,
		@DefaultValue("60") int totalRateLimit, @DefaultValue("1m") Duration rateWindow,
		@DefaultValue("600") int maxOutputTokens) {

	AssistantProperties {
		if (timeout.isNegative() || timeout.isZero() || requestRateLimit < 1 || rateLimit < 1 || totalRateLimit < 1
				|| rateWindow.isNegative() || rateWindow.isZero() || maxOutputTokens < 1) {
			throw new IllegalArgumentException("astra.assistant.* 값이 범위를 벗어났습니다.");
		}
	}

}
