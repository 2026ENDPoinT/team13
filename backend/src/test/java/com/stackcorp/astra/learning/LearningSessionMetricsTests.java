package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 학습 session의 하트비트 간격과 닫힌 session 수(SC-09, ADR-0014, ADR-0034). 시계는 테스트가 움직인다.
 */
class LearningSessionMetricsTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestLearningClock clock;

	@Autowired
	MeterRegistry meters;

	@Autowired
	LearningSessionSweeper sweeper;

	long course;

	long item;

	ApiClient student;

	@BeforeEach
	void lecture() {
		this.course = this.data.course();
		long stu = this.data.user("STUDENT");
		this.data.enroll(this.course, stu);
		long activity = this.data.activity(this.course, "lecture_weeks", true, null, null);
		this.item = this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path, duration_sec, required_sec)
				VALUES (:a, 1, '9주차 강의', 'video', :path, 600, 120)
				""", Map.of("a", activity, "path", "vod/metrics-" + UUID.randomUUID().toString().substring(0, 8) + "/main.m3u8"));
		this.student = new ApiClient(this.mvc, this.data.session(stu));
		this.clock.freeze();
	}

	@AfterEach
	void release() {
		this.clock.release();
	}

	@Test
	void measuresHeartbeatGapsAndCountsClosedSessionsByReason() throws Exception {
		Timer gap = this.meters.get("astra.learning.heartbeat.gap").timer();
		long beats = gap.count();
		double gapSeconds = gap.totalTime(TimeUnit.SECONDS);
		double superseded = closed("superseded");
		double unload = closed("unload");
		double expired = closed("lease_expired");

		long first = start();
		this.clock.advance(Duration.ofSeconds(75));
		beat(first, 1).andExpect(jsonPath("$.outcome").value("credited"));
		// 같은 순번의 재전송은 간격으로 세지 않는다.
		beat(first, 1).andExpect(jsonPath("$.outcome").value("duplicate"));
		assertThat(gap.count()).isEqualTo(beats + 1);
		assertThat(gap.totalTime(TimeUnit.SECONDS) - gapSeconds).isEqualTo(75);

		long second = start();
		assertThat(closed("superseded")).isEqualTo(superseded + 1);
		this.student.postJson(LearningSessionApiTests.end(this.course, second), "{\"reason\":\"unload\"}")
			.andExpect(status().isOk());
		assertThat(closed("unload")).isEqualTo(unload + 1);

		long third = start();
		this.clock.advance(Duration.ofSeconds(181));
		this.sweeper.sweep();
		assertThat(closed("lease_expired")).isGreaterThanOrEqualTo(expired + 1);
		assertThat(this.jdbc.sql("SELECT state FROM learning_sessions WHERE learning_session_id = :s")
			.param("s", third)
			.query(String.class)
			.single()).isEqualTo("lease_expired");
	}

	private long start() throws Exception {
		return ApiClient.json(this.student.post(LearningSessionApiTests.start(this.course, this.item))
			.andExpect(status().isCreated())).get("sessionId").asLong();
	}

	private ResultActions beat(long session, long seq) throws Exception {
		return this.student.postJson(LearningSessionApiTests.heartbeats(this.course, session), "{\"seq\":" + seq + "}");
	}

	private double closed(String reason) {
		Counter counter = this.meters.find("astra.learning.sessions.closed").tag("reason", reason).counter();
		return counter == null ? 0 : counter.count();
	}

}
