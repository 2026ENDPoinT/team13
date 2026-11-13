package com.stackcorp.astra.shared.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.stackcorp.astra.IntegrationTest;

/**
 * 주기 작업 lease 잠금(ADR-0034). 두 worker를 같은 DB에 붙은 두 인스턴스로 흉내 내고 실제 thread로 동시에 부른다.
 */
class SchedulerLockTests extends IntegrationTest {

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Test
	void twoInstancesRunALockedTaskOnceWhileItIsRunning() throws Exception {
		String name = name();
		SchedulerLock first = instance("worker-a");
		SchedulerLock second = instance("worker-b");
		AtomicInteger runs = new AtomicInteger();
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Boolean>> results = List.of(first, second).stream().map((lock) -> pool.submit(() -> {
				start.await(WAIT.toSeconds(), TimeUnit.SECONDS);
				return lock.runExclusively(name, Duration.ofMinutes(1), () -> {
					runs.incrementAndGet();
					entered.countDown();
					await(finish);
				});
			})).toList();
			// 진 쪽은 기다리지 않고 바로 건너뛴다. 이긴 쪽은 아직 실행 중이다.
			Future<Boolean> skipped = firstDone(results);
			assertThat(skipped.get()).isFalse();
			await(entered);
			assertThat(runs).hasValue(1);
			finish.countDown();
			Future<Boolean> ran = results.get(results.get(0) == skipped ? 1 : 0);
			assertThat(ran.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
		}
		assertThat(runs).hasValue(1);
		// 끝나면 바로 놓는다. 다음 실행은 기다리지 않는다.
		assertThat(second.runExclusively(name, Duration.ofMinutes(1), runs::incrementAndGet)).isTrue();
		assertThat(runs).hasValue(2);
	}

	@Test
	void locksWithNeighbouringNamesDoNotBlockEachOther() throws Exception {
		String name = name();
		SchedulerLock first = instance("worker-a");
		SchedulerLock second = instance("worker-b");
		// 두 작업이 서로 안에 들어와 있어야 장벽을 넘는다. 한쪽이 다른 쪽 잠금을 기다리면 시간 초과로 실패한다.
		CyclicBarrier inside = new CyclicBarrier(2);

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<Boolean> a = pool.submit(() -> first.runExclusively(name + ".a", Duration.ofMinutes(1), () -> await(inside)));
			Future<Boolean> b = pool.submit(() -> second.runExclusively(name + ".b", Duration.ofMinutes(1), () -> await(inside)));
			assertThat(a.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
			assertThat(b.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
		}
	}

	@Test
	void releasesTheLockWhenTheTaskFails() {
		String name = name();
		SchedulerLock first = instance("worker-a");
		SchedulerLock second = instance("worker-b");

		assertThatIllegalStateException().isThrownBy(() -> first.runExclusively(name, Duration.ofMinutes(10), () -> {
			throw new IllegalStateException("task failed");
		})).withMessage("task failed");

		AtomicInteger runs = new AtomicInteger();
		assertThat(second.runExclusively(name, Duration.ofMinutes(10), runs::incrementAndGet)).isTrue();
		assertThat(runs).hasValue(1);
	}

	@Test
	void anotherInstanceTakesOverAfterTheLeaseExpiresAndTheOldHolderCannotReleaseIt() throws Exception {
		String name = name();
		SchedulerLock stuck = instance("worker-a");
		SchedulerLock other = instance("worker-b");
		SchedulerLock third = instance("worker-c");
		CountDownLatch stuckInside = new CountDownLatch(1);
		CountDownLatch unstick = new CountDownLatch(1);
		CountDownLatch otherInside = new CountDownLatch(1);
		CountDownLatch otherFinish = new CountDownLatch(1);

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			// 잡은 process가 멈춘 것처럼 lease보다 오래 붙잡고 있는다.
			Future<Boolean> held = pool.submit(() -> stuck.runExclusively(name, Duration.ofSeconds(1), () -> {
				stuckInside.countDown();
				await(unstick);
			}));
			await(stuckInside);
			assertThat(other.runExclusively(name, Duration.ofMinutes(1), () -> {
			})).as("lease 안에서는 잡지 못한다").isFalse();

			Thread.sleep(1_500);
			Future<Boolean> takenOver = pool.submit(() -> other.runExclusively(name, Duration.ofMinutes(1), () -> {
				otherInside.countDown();
				await(otherFinish);
			}));
			await(otherInside);

			// 멈췄던 쪽이 끝나도 새 소유자의 lease는 그대로다.
			unstick.countDown();
			assertThat(held.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
			assertThat(third.runExclusively(name, Duration.ofMinutes(1), () -> {
			})).isFalse();
			assertThat(holder(name)).startsWith("worker-b:");

			otherFinish.countDown();
			assertThat(takenOver.get(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();
		}
		assertThat(third.runExclusively(name, Duration.ofMinutes(1), () -> {
		})).isTrue();
	}

	private SchedulerLock instance(String id) {
		return new SchedulerLock(this.jdbc, this.transactionManager, id);
	}

	private String holder(String name) {
		return this.jdbc.sql("SELECT locked_by FROM scheduler_locks WHERE lock_name = :name")
			.param("name", name)
			.query(String.class)
			.single();
	}

	private static String name() {
		return "test.lock_" + UUID.randomUUID().toString().substring(0, 8);
	}

	private static <T> Future<T> firstDone(List<Future<T>> futures) throws InterruptedException {
		long deadline = System.nanoTime() + WAIT.toNanos();
		while (System.nanoTime() < deadline) {
			for (Future<T> future : futures) {
				if (future.isDone()) {
					return future;
				}
			}
			Thread.sleep(10);
		}
		throw new AssertionError("neither instance returned");
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(WAIT.toSeconds(), TimeUnit.SECONDS)) {
				throw new AssertionError("timed out");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

	private static void await(CyclicBarrier barrier) {
		try {
			barrier.await(WAIT.toSeconds(), TimeUnit.SECONDS);
		}
		catch (Exception ex) {
			throw new AssertionError("barrier not reached", ex);
		}
	}

}
