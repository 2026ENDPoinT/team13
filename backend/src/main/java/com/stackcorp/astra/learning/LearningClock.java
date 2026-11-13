package com.stackcorp.astra.learning;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 학습 session, 적립, 출석 판정에 쓰는 서버 시각. open_at, close_at과 같은 DB 시계를 ms 단위로 읽는다(SC-06).
 * api가 여러 대여도 한 시계를 쓰고, client 시계는 어디에도 쓰지 않는다. 잠금을 잡은 뒤에 읽어야 적립 구간이 겹치지 않는다(ADR-0014).
 */
@Component
class LearningClock {

	private final JdbcClient jdbc;

	LearningClock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

}
