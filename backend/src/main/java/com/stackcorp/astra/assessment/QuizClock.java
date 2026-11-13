package com.stackcorp.astra.assessment;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 퀴즈의 공개, 응시 기간, 응시 마감, 결과 공개 판정에 쓰는 서버 시각. 활동의 open_at, close_at과 같은 DB 시계를 ms 단위로 읽는다(SC-06).
 * client 시각은 어디에도 쓰지 않는다. 응시 행을 잠근 뒤에 읽어야 마감 판정과 저장이 같은 순간을 본다.
 */
@Component
class QuizClock {

	private final JdbcClient jdbc;

	QuizClock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	LocalDateTime now() {
		return this.jdbc.sql("SELECT CURRENT_TIMESTAMP(3)").query(LocalDateTime.class).single();
	}

}
