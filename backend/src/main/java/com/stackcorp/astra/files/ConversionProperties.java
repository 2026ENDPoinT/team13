package com.stackcorp.astra.files;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 문서 변환 대기열 설정(ADR-0033).
 *
 * @param pollInterval worker가 대기 중인 변환을 찾는 간격
 * @param recoveryInterval lease가 끝난 변환을 정리하는 간격
 * @param lease 변환 하나의 lease. converter 제한 시간(astra.converter.timeout)보다 길어야 한다. 이 시간이 지나면 다른 worker가 다시
 * 잡는다
 * @param maxAttempts 한 변환의 시도 수 상한. converter에 닿지 못했거나 worker가 멈춰 끝나지 못한 시도도 센다
 * @param retryDelay converter에 닿지 못했을 때 다시 시도하기까지 기다리는 시간
 * @param workerId lease 소유자 이름. 비우면 host 이름과 process ID로 만든다
 */
@ConfigurationProperties("astra.files.conversion")
record ConversionProperties(@DefaultValue("5s") Duration pollInterval, @DefaultValue("1m") Duration recoveryInterval,
		@DefaultValue("15m") Duration lease, @DefaultValue("3") int maxAttempts, @DefaultValue("1m") Duration retryDelay,
		String workerId) {

	ConversionProperties {
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("astra.files.conversion.max-attempts는 1 이상이어야 합니다.");
		}
		if (lease.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("astra.files.conversion.lease는 1초 이상이어야 합니다.");
		}
	}

}
