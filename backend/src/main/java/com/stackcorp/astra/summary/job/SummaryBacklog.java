package com.stackcorp.astra.summary.job;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.shared.metrics.SampledGauge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * 요약 작업 대기열(architecture.md 9절, ADR-0034). worker가 정해진 간격으로 summary_jobs(job_state, lease_expires_at) index를 타는
 * query로 계산한다. 지표를 내보낼 때는 DB를 읽지 않는다.
 * <ul>
 * <li>astra.summary.jobs{state=queued|running}: 상태별 작업 수({@link SampledGauge#CAP}까지)</li>
 * <li>astra.summary.jobs.oldest.age: 가장 오래 기다린 대기 작업이 기다린 초. 대기 작업이 없으면 0</li>
 * </ul>
 */
@Component
@Profile("worker")
class SummaryBacklog {

	private final JdbcClient jdbc;

	private final SampledGauge queued;

	private final SampledGauge running;

	private final SampledGauge oldestAge;

	SummaryBacklog(JdbcClient jdbc, MeterRegistry meters) {
		this.jdbc = jdbc;
		String description = "상태별 요약 작업 수(상한 " + SampledGauge.CAP + ")";
		this.queued = SampledGauge.register(meters, "astra.summary.jobs", description, "jobs",
				Tags.of("state", "queued"));
		this.running = SampledGauge.register(meters, "astra.summary.jobs", description, "jobs",
				Tags.of("state", "running"));
		this.oldestAge = SampledGauge.register(meters, "astra.summary.jobs.oldest.age",
				"가장 오래 기다린 대기 작업이 기다린 시간", "seconds", Tags.empty());
	}

	@Scheduled(fixedDelayString = "${astra.metrics.backlog-interval:1m}",
			initialDelayString = "${astra.metrics.backlog-interval:1m}")
	void sample() {
		this.queued.set(count("queued"));
		this.running.set(count("running"));
		// 대기 작업은 lease가 없어 index 안에서 ID 순서다. 가장 작은 ID가 가장 먼저 들어온 작업이다.
		this.oldestAge.set(this.jdbc.sql("""
				SELECT TIMESTAMPDIFF(SECOND, created_at, CURRENT_TIMESTAMP(3)) FROM summary_jobs
				 WHERE job_state = 'queued' ORDER BY summary_job_id LIMIT 1
				""").query(Long.class).optional().orElse(0L));
	}

	private long count(String state) {
		return this.jdbc.sql("SELECT COUNT(*) FROM (SELECT 1 FROM summary_jobs WHERE job_state = :state LIMIT "
				+ SampledGauge.CAP + ") bounded").param("state", state).query(Long.class).single();
	}

}
