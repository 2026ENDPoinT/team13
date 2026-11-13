package com.stackcorp.astra.learning;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.shared.schedule.SchedulerLock;

/**
 * worker 프로필에서 lease가 지난 학습 session을 닫는다(EP-0058 미도착, 창 닫기, 네트워크 단절). session마다 조건부 UPDATE라 worker가 여럿이어도 한 번만 닫힌다.
 * 주기 실행은 {@link SchedulerLock}으로 묶는다(ADR-0034). 후보를 잠그지 않고 읽으므로 두 worker가 함께 돌면 같은 후보마다 UPDATE를 두 번 보내기
 * 때문이다.
 */
@Component
@Profile("worker")
class LearningSessionSweeper {

	private static final Logger log = LoggerFactory.getLogger(LearningSessionSweeper.class);

	static final String LOCK = "learning.session_sweep";

	private static final Duration LOCK_AT_MOST = Duration.ofMinutes(5);

	private final LearningSessionService sessions;

	private final SchedulerLock lock;

	LearningSessionSweeper(LearningSessionService sessions, SchedulerLock lock) {
		this.sessions = sessions;
		this.lock = lock;
	}

	@Scheduled(fixedDelayString = "${astra.learning.lease-sweep-interval:30s}",
			initialDelayString = "${astra.learning.lease-sweep-interval:30s}")
	void sweep() {
		this.lock.runExclusively(LOCK, LOCK_AT_MOST, () -> {
			int closed = this.sessions.expireLeases();
			if (closed > 0) {
				log.info("learning sessions closed by expired lease count={}", closed);
			}
		});
	}

}
