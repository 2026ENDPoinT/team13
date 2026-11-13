package com.stackcorp.astra.files.scan;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 악성코드 검사 설정(ADR-0018). 검사기 연결 설정은 astra.scan.clamd에 있다.
 *
 * @param required 검사기가 설정되지 않았으면 시작하지 않는다. 학교 자료를 받는 배포에서 켠다
 * @param pollInterval worker가 검사 대기 내용을 찾는 간격
 * @param batchSize 한 번에 검사하는 내용 수 상한
 * @param lease 검사 하나의 lease 길이. 검사기 제한 시간보다 길어야 한다. 이 시간이 지나면 다른 worker가 다시 잡는다
 * @param retryBackoff 검사기에 닿지 못했을 때 첫 재시도까지의 간격. 시도마다 두 배로 늘린다
 * @param maxRetryBackoff 재시도 간격의 상한
 * @param workerId lease 소유자 이름. 비우면 host 이름과 process ID로 만든다
 */
@ConfigurationProperties("astra.scan")
record ScanProperties(@DefaultValue("false") boolean required, @DefaultValue("10s") Duration pollInterval,
		@DefaultValue("20") int batchSize, @DefaultValue("10m") Duration lease,
		@DefaultValue("30s") Duration retryBackoff, @DefaultValue("1h") Duration maxRetryBackoff, String workerId) {

	ScanProperties {
		if (batchSize < 1) {
			throw new IllegalArgumentException("astra.scan.batch-size는 1 이상이어야 합니다.");
		}
		if (retryBackoff.compareTo(Duration.ofSeconds(1)) < 0 || maxRetryBackoff.compareTo(retryBackoff) < 0) {
			throw new IllegalArgumentException(
					"astra.scan.retry-backoff는 1초 이상이고 astra.scan.max-retry-backoff는 그보다 길어야 합니다.");
		}
		if (lease.compareTo(Duration.ofSeconds(1)) < 0) {
			throw new IllegalArgumentException("astra.scan.lease는 1초 이상이어야 합니다.");
		}
	}

	/** @param attempt 1부터 센 시도 횟수 */
	Duration backoff(int attempt) {
		Duration delay = this.retryBackoff;
		for (int i = 1; i < attempt && delay.compareTo(this.maxRetryBackoff) < 0; i++) {
			delay = delay.multipliedBy(2);
		}
		return delay.compareTo(this.maxRetryBackoff) < 0 ? delay : this.maxRetryBackoff;
	}

}
