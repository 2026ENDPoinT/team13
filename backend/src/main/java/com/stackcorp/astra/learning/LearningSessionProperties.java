package com.stackcorp.astra.learning;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param heartbeatInterval 서버가 정해 player에 알려 주는 하트비트 주기. 영상 cookie 유효 시간보다 짧아야 한다.
 * 적립 간격의 하한(주기의 1/2), 상한(주기의 2배), session lease(주기의 3배)는 이 값에서 정한다(ADR-0014)
 * @param leaseSweepInterval worker가 lease가 지난 session을 닫는 간격
 */
@ConfigurationProperties("astra.learning")
record LearningSessionProperties(@DefaultValue("60s") Duration heartbeatInterval,
		@DefaultValue("30s") Duration leaseSweepInterval) {

	LearningSessionProperties {
		if (heartbeatInterval.compareTo(Duration.ofSeconds(10)) < 0
				|| heartbeatInterval.compareTo(Duration.ofMinutes(5)) > 0 || heartbeatInterval.toMillisPart() != 0) {
			throw new IllegalArgumentException("astra.learning.heartbeat-interval은 10초 이상 5분 이하의 초 단위 값이어야 합니다.");
		}
	}

}
