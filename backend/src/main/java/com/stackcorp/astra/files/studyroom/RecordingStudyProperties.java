package com.stackcorp.astra.files.studyroom;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 학습실 녹음 전사·요약의 한도와 worker 설정(ADR-0041). 제공자 연결 값은 integration의 astra.deepgram.*, astra.llm.*에 있다.
 *
 * @param maxRecordingMinutes 녹음 하나의 길이 상한(올릴 때 보낸 durationMs). 넘으면 전사하지 않는다
 * @param dailyMinutes 사용자 한 사람이 하루(DB 날짜)에 전사할 수 있는 오디오 분
 * @param monthlyMinutes 사용자 한 사람이 한 달(달력 월)에 전사할 수 있는 오디오 분
 * @param maxConcurrent 모든 worker를 합쳐 동시에 처리하는 전사 수와 요약 수 상한(종류마다)
 * @param summaryChunkChars 요약 호출 한 번에 보내는 전사 글자 수. 넘으면 나눠 요약한다
 * @param pollInterval worker가 대기 작업을 찾는 간격
 * @param lease 실행 lease 길이
 * @param leaseRenewal lease 갱신 간격이자 만료 lease 회수 간격. lease보다 짧아야 한다
 * @param maxAttempts 일시 오류일 때 실행을 시작하는 최대 횟수(첫 실행 포함)
 * @param retryBackoff 첫 재시도까지 기다리는 시간. 재시도마다 4배로 늘고 1시간을 넘지 않는다
 * @param workerId lease 소유자 이름. 비우면 host 이름과 process ID로 만든다
 */
@ConfigurationProperties("astra.recording-study")
record RecordingStudyProperties(@DefaultValue("180") int maxRecordingMinutes, @DefaultValue("300") int dailyMinutes,
		@DefaultValue("1800") int monthlyMinutes, @DefaultValue("4") int maxConcurrent,
		@DefaultValue("24000") int summaryChunkChars, @DefaultValue("10s") Duration pollInterval,
		@DefaultValue("20m") Duration lease, @DefaultValue("1m") Duration leaseRenewal,
		@DefaultValue("4") int maxAttempts, @DefaultValue("1m") Duration retryBackoff, String workerId) {

	RecordingStudyProperties {
		if (maxRecordingMinutes < 1 || dailyMinutes < 1 || monthlyMinutes < 1 || maxConcurrent < 1
				|| maxAttempts < 1) {
			throw new IllegalArgumentException("astra.recording-study.max-recording-minutes, daily-minutes, "
					+ "monthly-minutes, max-concurrent, max-attempts는 1 이상이어야 합니다.");
		}
		if (dailyMinutes > monthlyMinutes) {
			throw new IllegalArgumentException("astra.recording-study.daily-minutes는 monthly-minutes보다 클 수 없습니다.");
		}
		if (summaryChunkChars < 2000) {
			throw new IllegalArgumentException("astra.recording-study.summary-chunk-chars는 2000 이상이어야 합니다.");
		}
		if (leaseRenewal.compareTo(lease) >= 0 || leaseRenewal.isNegative() || leaseRenewal.isZero()) {
			throw new IllegalArgumentException("astra.recording-study.lease-renewal은 0보다 크고 lease보다 짧아야 합니다.");
		}
	}

	/** n번째 실행이 일시 오류로 끝났을 때 다음 실행까지 기다리는 시간. */
	Duration backoff(int attempt) {
		Duration delay = this.retryBackoff.multipliedBy((long) Math.pow(4, Math.max(0, Math.min(attempt - 1, 10))));
		return delay.compareTo(Duration.ofHours(1)) > 0 ? Duration.ofHours(1) : delay;
	}

}
