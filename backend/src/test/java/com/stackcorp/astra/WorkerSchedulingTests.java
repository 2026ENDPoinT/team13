package com.stackcorp.astra;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.TaskScheduler;

/**
 * worker의 {@code @Scheduled} method는 모두 Spring Boot가 만든 {@link TaskScheduler} 하나에서 돈다. 대기열을 비울 때까지 도는
 * poll(문서 변환, 사진, 영상 변환, ZIP, 업로드 검사, 요약, 학습 대화)이 모두 바빠도 다른 주기 작업(lease 만료, GC 등)이 돌아야 한다.
 */
class WorkerSchedulingTests extends IntegrationTest {

	/** 외부 호출을 기다리며 오래 붙잡을 수 있는 poll의 수. */
	private static final int LONG_RUNNING_POLLS = 7;

	@Autowired
	TaskScheduler scheduler;

	@Test
	void runsOtherScheduledTasksWhileEveryLongPollIsBusy() throws InterruptedException {
		CountDownLatch release = new CountDownLatch(1);
		CountDownLatch busy = new CountDownLatch(LONG_RUNNING_POLLS);
		try {
			for (int i = 0; i < LONG_RUNNING_POLLS; i++) {
				this.scheduler.schedule(() -> {
					busy.countDown();
					awaitQuietly(release);
				}, Instant.now());
			}
			assertThat(busy.await(5, TimeUnit.SECONDS)).as("long polls running at once").isTrue();

			CountDownLatch swept = new CountDownLatch(1);
			this.scheduler.schedule(swept::countDown, Instant.now());
			assertThat(swept.await(5, TimeUnit.SECONDS)).as("another scheduled task ran while polls were busy")
				.isTrue();
		}
		finally {
			release.countDown();
		}
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(30, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
