package com.stackcorp.astra.shared.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * worker가 정해진 간격으로 계산해 둔 값을 내보내는 gauge(architecture.md 9절, ADR-0034). 지표를 내보낼 때 DB를 읽지 않는다.
 * 대기열과 backlog 수는 {@link #CAP}까지만 센다. 한 번의 계산이 상한 넘게 index를 읽지 않게 하기 위해서다. 아직 계산하지 않았으면 값이
 * NaN이라 내보내지 않는다.
 */
public final class SampledGauge {

	/** backlog를 셀 때의 상한. 이 값이면 "상한 이상"으로 읽는다. */
	public static final int CAP = 10_000;

	private volatile double value = Double.NaN;

	private SampledGauge() {
	}

	/**
	 * @param name 지표 이름. 예: astra.summary.jobs.queued
	 * @param tags 값이 몇 개로 정해진 tag만 쓴다. 사용자, 과목, 파일 ID는 넣지 않는다
	 */
	public static SampledGauge register(MeterRegistry registry, String name, String description, String baseUnit,
			Tags tags) {
		SampledGauge gauge = new SampledGauge();
		Gauge.builder(name, gauge, (g) -> g.value)
			.description(description)
			.baseUnit(baseUnit)
			.tags(tags)
			.strongReference(true)
			.register(registry);
		return gauge;
	}

	public void set(double value) {
		this.value = value;
	}

	public double value() {
		return this.value;
	}

}
