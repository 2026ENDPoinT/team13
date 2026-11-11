package com.stackcorp.astra.learning;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 스마트출석(PIN/QR)의 보안 설정(ADR-0030). 과목마다 바꾸는 출석 정책(지각 기준, 회차 시간)은 {@link AttendancePolicy}에 있다.
 *
 * @param pinLength PIN 자릿수. 4~8
 * @param maxFailures 회차마다 한 학생이 틀릴 수 있는 횟수. 넘으면 그 회차에서 잠기고 맞는 PIN·QR도 받지 않는다
 * @param qrRotation QR token을 바꾸는 주기. 5초~5분
 * @param qrGrace 바뀐 뒤에도 바로 앞 token을 받는 시간. 화면을 찍는 동안 바뀐 token을 살린다. 교체 주기보다 짧아야 한다
 * @param pollInterval 화면이 회차 상태를 다시 물을 간격(응답의 pollAfterSec). WebSocket 대신 이 간격으로 조회한다
 * @param roundFailureAlert 한 회차의 틀린 시도가 이 수에 닿으면 경고 로그를 한 번 남긴다(회차 단위 무차별 대입 감시)
 */
@ConfigurationProperties("astra.attendance")
record SmartAttendanceProperties(@DefaultValue("6") int pinLength, @DefaultValue("5") int maxFailures,
		@DefaultValue("15s") Duration qrRotation, @DefaultValue("5s") Duration qrGrace,
		@DefaultValue("3s") Duration pollInterval, @DefaultValue("50") int roundFailureAlert) {

	SmartAttendanceProperties {
		if (pinLength < 4 || pinLength > 8) {
			throw new IllegalArgumentException("astra.attendance.pin-length는 4 이상 8 이하여야 합니다.");
		}
		if (maxFailures < 1 || maxFailures > 20) {
			throw new IllegalArgumentException("astra.attendance.max-failures는 1 이상 20 이하여야 합니다.");
		}
		if (qrRotation.compareTo(Duration.ofSeconds(5)) < 0 || qrRotation.compareTo(Duration.ofMinutes(5)) > 0
				|| qrRotation.toMillisPart() != 0) {
			throw new IllegalArgumentException("astra.attendance.qr-rotation은 5초 이상 5분 이하의 초 단위 값이어야 합니다.");
		}
		if (qrGrace.isNegative() || qrGrace.compareTo(qrRotation) >= 0) {
			throw new IllegalArgumentException("astra.attendance.qr-grace는 0 이상이고 qr-rotation보다 짧아야 합니다.");
		}
		if (pollInterval.compareTo(Duration.ofSeconds(1)) < 0 || pollInterval.compareTo(Duration.ofMinutes(1)) > 0) {
			throw new IllegalArgumentException("astra.attendance.poll-interval은 1초 이상 1분 이하여야 합니다.");
		}
		if (roundFailureAlert < 1) {
			throw new IllegalArgumentException("astra.attendance.round-failure-alert는 1 이상이어야 합니다.");
		}
	}

}
