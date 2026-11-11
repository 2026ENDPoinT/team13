package com.stackcorp.astra.files;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 영상 변환 worker(ADR-0016). transcoder 주소와 제한은 integration의 astra.transcoder.*에 있다.
 *
 * @param pollInterval worker가 대기 작업과 삭제된 원본을 찾는 간격
 * @param lease 실행 lease 길이. 이 시간 동안 갱신이 없으면 작업을 다시 대기로 돌리거나 실패로 닫는다
 * @param leaseRenewal lease 갱신 간격. lease보다 짧아야 한다
 * @param maxAttempts 일시 오류일 때 실행을 시작하는 최대 횟수(첫 실행 포함)
 * @param retryBackoff 첫 재시도까지 기다리는 시간. 재시도마다 4배로 늘고 1시간을 넘지 않는다
 * @param workerId lease 소유자 이름. 비우면 host 이름과 process ID로 만든다
 */
@ConfigurationProperties("astra.transcoder.worker")
record VideoWorkerProperties(@DefaultValue("10s") Duration pollInterval, @DefaultValue("10m") Duration lease,
		@DefaultValue("1m") Duration leaseRenewal, @DefaultValue("3") int maxAttempts,
		@DefaultValue("1m") Duration retryBackoff, String workerId) {

	VideoWorkerProperties {
		if (leaseRenewal.compareTo(lease) >= 0) {
			throw new IllegalArgumentException("astra.transcoder.worker.lease-renewal은 lease보다 짧아야 합니다.");
		}
		if (maxAttempts < 1) {
			throw new IllegalArgumentException("astra.transcoder.worker.max-attempts는 1 이상이어야 합니다.");
		}
	}

	/** n번째 실행이 일시 오류로 끝났을 때 다음 실행까지 기다리는 시간. */
	Duration backoff(int attempt) {
		Duration delay = this.retryBackoff.multipliedBy((long) Math.pow(4, Math.max(0, Math.min(attempt - 1, 10))));
		return delay.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : delay;
	}

}
