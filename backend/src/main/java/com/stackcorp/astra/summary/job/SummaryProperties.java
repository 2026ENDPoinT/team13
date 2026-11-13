package com.stackcorp.astra.summary.job;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param pollInterval worker가 대기 작업을 찾는 간격
 * @param lease 실행 lease 길이. 이 시간 동안 갱신이 없으면 작업을 interrupted로 닫는다
 * @param leaseRenewal lease 갱신 간격. lease보다 짧아야 한다
 * @param workerId lease 소유자 이름. 비우면 host 이름과 process ID로 만든다
 * @param studyDocumentDailyLimit 한 사용자가 하루(기관 시간대)에 요청할 수 있는 학습실 문서 요약 수(ADR-0040 14번)
 */
@ConfigurationProperties("astra.summary")
public record SummaryProperties(@DefaultValue("5s") Duration pollInterval, @DefaultValue("120s") Duration lease,
		@DefaultValue("30s") Duration leaseRenewal, String workerId, @DefaultValue("20") int studyDocumentDailyLimit) {

	public SummaryProperties {
		if (leaseRenewal.compareTo(lease) >= 0) {
			throw new IllegalArgumentException("astra.summary.lease-renewal은 astra.summary.lease보다 짧아야 합니다.");
		}
		if (studyDocumentDailyLimit < 1) {
			throw new IllegalArgumentException("astra.summary.study-document-daily-limit은 1 이상이어야 합니다.");
		}
	}

}
