package com.stackcorp.astra.files.avatar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * worker가 아바타 사진 대기열을 처리한다(ADR-0038). 사진 하나를 SKIP LOCKED와 lease로 잡으므로 worker가 둘이어도 같은 사진을 함께
 * 처리하지 않는다. lease가 끝난 처리는 정해진 시도 수까지 다시 잡히고 그 뒤에는 unavailable로 닫힌다. 로그에는 사진 ID, 시도 수,
 * 코드만 남긴다.
 */
@Component
@Profile("worker")
class AvatarImageWorker {

	private static final Logger log = LoggerFactory.getLogger(AvatarImageWorker.class);

	private final AvatarImages images;

	AvatarImageWorker(AvatarImages images) {
		this.images = images;
	}

	@Scheduled(fixedDelayString = "${astra.avatar.poll-interval:5s}", initialDelayString = "${astra.avatar.poll-interval:5s}")
	void poll() {
		while (this.images.processNext()) {
			// 대기 사진이 없을 때까지 이어서 처리한다.
		}
	}

	@Scheduled(fixedDelayString = "${astra.avatar.recovery-interval:1m}",
			initialDelayString = "${astra.avatar.recovery-interval:1m}")
	void recover() {
		int recovered = this.images.recoverExpired();
		if (recovered > 0) {
			log.warn("avatar images with expired lease recovered count={}", recovered);
		}
		this.images.sweep();
	}

}
