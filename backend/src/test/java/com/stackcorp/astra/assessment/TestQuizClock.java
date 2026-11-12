package com.stackcorp.astra.assessment;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 테스트가 움직이는 퀴즈 시계. 고정하지 않으면 DB 시계를 그대로 쓴다. 고정한 테스트는 끝날 때 {@link #release()}한다.
 * 공개, 응시 마감, 결과 공개의 경계를 ms 단위로 정확히 보기 위해 쓴다.
 */
@Primary
public class TestQuizClock extends QuizClock {

	private final AtomicReference<LocalDateTime> fixed = new AtomicReference<>();

	TestQuizClock(JdbcClient jdbc) {
		super(jdbc);
	}

	/** 지금 DB 시각(초 단위)으로 고정하고 그 시각을 돌려준다. */
	LocalDateTime freeze() {
		LocalDateTime now = super.now().truncatedTo(ChronoUnit.SECONDS);
		this.fixed.set(now);
		return now;
	}

	void set(LocalDateTime time) {
		this.fixed.set(time);
	}

	LocalDateTime advance(Duration duration) {
		return this.fixed.updateAndGet((t) -> t.plus(duration));
	}

	void release() {
		this.fixed.set(null);
	}

	@Override
	LocalDateTime now() {
		LocalDateTime time = this.fixed.get();
		return time != null ? time : super.now();
	}

}
