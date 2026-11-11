package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.shared.audit.AuditLog;
import com.stackcorp.astra.shared.idempotency.Idempotency;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 멱등 원장과 감사 기록의 지표(SC-09, ADR-0012, ADR-0034). 이미 있는 key를 만난 요청을 결과별로 세고, 처리 중인 같은 key를 기다린
 * 시간을 잰다. transaction 밖에서 빠진 감사 기록을 센다.
 */
class LedgerMetricsTests extends IntegrationTest {

	private static final String OPERATION = "metrics.check";

	@Autowired
	Idempotency idempotency;

	@Autowired
	AuditLog audit;

	@Autowired
	TestData data;

	@Autowired
	MeterRegistry meters;

	@Test
	void countsReplaysAndConflictsOfAKnownKey() {
		long user = this.data.user("STUDENT");
		String key = UUID.randomUUID().toString();
		double replayed = requests("replayed");
		double conflicts = requests("conflict");
		long waits = waits();

		assertThat(this.idempotency.execute(user, OPERATION, key, Map.of("n", 1), String.class, () -> "first"))
			.isEqualTo("first");
		assertThat(requests("replayed")).isEqualTo(replayed);
		assertThat(this.idempotency.execute(user, OPERATION, key, Map.of("n", 1), String.class, () -> "second"))
			.isEqualTo("first");
		assertThatThrownBy(
				() -> this.idempotency.execute(user, OPERATION, key, Map.of("n", 2), String.class, () -> "third"))
			.isInstanceOfSatisfying(ApiException.class, (ex) -> assertThat(ex.code()).isEqualTo("idempotency_conflict"));

		assertThat(requests("replayed")).isEqualTo(replayed + 1);
		assertThat(requests("conflict")).isEqualTo(conflicts + 1);
		assertThat(waits()).isEqualTo(waits + 2);
	}

	@Test
	void countsARequestThatGaveUpWaitingForTheSameKeyInFlight() throws Exception {
		long user = this.data.user("STUDENT");
		String key = UUID.randomUUID().toString();
		double inProgress = requests("in_progress");
		double waitedBefore = totalWaitMillis();
		CountDownLatch reserved = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);

		try (ExecutorService pool = Executors.newFixedThreadPool(1)) {
			Future<String> first = pool.submit(() -> this.idempotency.execute(user, OPERATION, key, Map.of("n", 1),
					String.class, () -> {
						reserved.countDown();
						try {
							finish.await(30, TimeUnit.SECONDS);
						}
						catch (InterruptedException ex) {
							Thread.currentThread().interrupt();
						}
						return "first";
					}));
			assertThat(reserved.await(30, TimeUnit.SECONDS)).isTrue();
			assertThatThrownBy(
					() -> this.idempotency.execute(user, OPERATION, key, Map.of("n", 1), String.class, () -> "second"))
				.isInstanceOfSatisfying(ApiException.class,
						(ex) -> assertThat(ex.code()).isEqualTo("idempotency_in_progress"));
			finish.countDown();
			assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo("first");

			assertThat(requests("in_progress")).isEqualTo(inProgress + 1);
			// 같은 key를 처리 중인 transaction을 in-flight-wait(기본 3초)만큼 기다렸다.
			assertThat(totalWaitMillis() - waitedBefore).isGreaterThanOrEqualTo(2_000);
		}
	}

	@Test
	void countsAuditRecordsLostOutsideATransaction() {
		double lost = this.meters.find("astra.audit.write.failures")
			.tag("operation", OPERATION)
			.counters()
			.stream()
			.mapToDouble(Counter::count)
			.sum();

		// 없는 사용자를 가리켜 FK 위반으로 쓰기가 실패한다. transaction 밖이라 호출은 실패하지 않는다.
		this.audit.record(Long.MAX_VALUE, null, OPERATION, null, null, null, AuditLog.OK);

		assertThat(this.meters.get("astra.audit.write.failures").tag("operation", OPERATION).counter().count())
			.isEqualTo(lost + 1);
	}

	private double requests(String outcome) {
		Counter counter = this.meters.find("astra.idempotency.requests")
			.tag("operation", OPERATION)
			.tag("outcome", outcome)
			.counter();
		return counter == null ? 0 : counter.count();
	}

	private double totalWaitMillis() {
		Timer timer = this.meters.find("astra.idempotency.wait").tag("operation", OPERATION).timer();
		return timer == null ? 0 : timer.totalTime(TimeUnit.MILLISECONDS);
	}

	private long waits() {
		Timer timer = this.meters.find("astra.idempotency.wait").tag("operation", OPERATION).timer();
		return timer == null ? 0 : timer.count();
	}

}
