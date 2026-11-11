package com.stackcorp.astra.learning;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * worker 프로필에서 lease가 지난 학습 session을 닫는다(EP-0058 미도착, 창 닫기, 네트워크 단절). session마다 조건부 UPDATE라 worker가 여럿이어도 한 번만 닫힌다.
 */
@Component
@Profile("worker")
class LearningSessionSweeper {

	private static final Logger log = LoggerFactory.getLogger(LearningSessionSweeper.class);

	private final LearningSessionService sessions;

	LearningSessionSweeper(LearningSessionService sessions) {
		this.sessions = sessions;
	}

	@Scheduled(fixedDelayString = "${astra.learning.lease-sweep-interval:30s}",
			initialDelayString = "${astra.learning.lease-sweep-interval:30s}")
	void sweep() {
		int closed = this.sessions.expireLeases();
		if (closed > 0) {
			log.info("learning sessions closed by expired lease count={}", closed);
		}
	}

}
