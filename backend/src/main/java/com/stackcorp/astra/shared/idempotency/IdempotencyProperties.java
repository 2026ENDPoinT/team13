package com.stackcorp.astra.shared.idempotency;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param retention 완료 receipt의 보존기한. 이 시간 안의 같은 key 재전송은 receipt를 돌려받는다
 * @param inFlightWait 같은 key의 요청이 처리 중일 때 기다리는 시간. 1초 단위로 올림한다
 * @param purgeInterval worker가 보존기한이 지난 항목을 지우는 간격
 */
@ConfigurationProperties("astra.idempotency")
public record IdempotencyProperties(@DefaultValue("24h") Duration retention,
		@DefaultValue("3s") Duration inFlightWait, @DefaultValue("15m") Duration purgeInterval) {

	public IdempotencyProperties {
		if (retention.toSeconds() < 1 || inFlightWait.isNegative() || inFlightWait.isZero()) {
			throw new IllegalArgumentException("astra.idempotency.retention과 in-flight-wait는 0보다 커야 합니다.");
		}
	}

	long inFlightWaitSeconds() {
		return Math.max(1, (this.inFlightWait.toMillis() + 999) / 1000);
	}

}
