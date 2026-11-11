package com.stackcorp.astra.files;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * worker가 미리보기 변환 대기열을 처리한다(ADR-0033). 변환 하나를 SKIP LOCKED와 lease로 잡으므로 worker가 둘이어도 같은 변환을 함께
 * 하지 않는다. worker가 변환 중에 멈추면 lease가 끝난 뒤 정해진 시도 수까지 다시 잡히고, 그 뒤에는 interrupted로 닫힌다.
 * 로그에는 변환 작업 ID, 쪽 수, 시도 수, 오류 코드만 남긴다.
 */
@Component
@Profile("worker")
class ConversionJobRunner {

	private static final Logger log = LoggerFactory.getLogger(ConversionJobRunner.class);

	private final DocumentRenditionService renditions;

	ConversionJobRunner(DocumentRenditionService renditions) {
		this.renditions = renditions;
	}

	@Scheduled(fixedDelayString = "${astra.files.conversion.poll-interval:5s}",
			initialDelayString = "${astra.files.conversion.poll-interval:5s}")
	void poll() {
		while (this.renditions.convertNext()) {
			// 대기 변환이 없을 때까지 이어서 실행한다.
		}
	}

	@Scheduled(fixedDelayString = "${astra.files.conversion.recovery-interval:1m}",
			initialDelayString = "${astra.files.conversion.recovery-interval:1m}")
	void recover() {
		int recovered = this.renditions.recoverExpired();
		if (recovered > 0) {
			log.warn("conversions with expired lease recovered count={}", recovered);
		}
	}

}
