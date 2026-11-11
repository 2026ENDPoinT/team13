package com.stackcorp.astra.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.files.VideoAssetRecords.Claimed;
import com.stackcorp.astra.files.api.VideoAssets;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 변환 작업 원장은 worker가 둘 이상이어도 작업 하나를 한 worker에게만 준다(SC-05, SKIP LOCKED와 상태 조건 UPDATE).
 */
class VideoTranscodeJobTests extends IntegrationTest {

	@Autowired
	VideoAssetRecords records;

	@Autowired
	VideoAssets assets;

	@Autowired
	TestData data;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Test
	void twoWorkersClaimingAtOnceNeverGetTheSameJob() throws Exception {
		VideoRunnerDriver.drain(this.context);
		Set<Long> registered = new HashSet<>();
		for (int i = 0; i < 8; i++) {
			long fileId = this.data.file("lecture.mp4", "video/mp4", new byte[] { 1, 2, 3 }, "clean");
			registered.add(this.transaction
				.execute((tx) -> this.assets.register(fileId, "/api/courses/1/lecture-items/" + fileId + "/hls-key")));
		}
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			Future<List<Long>> a = workers.submit(claimAll("worker-a", start));
			Future<List<Long>> b = workers.submit(claimAll("worker-b", start));
			start.countDown();
			List<Long> claimedByA = a.get();
			List<Long> claimedByB = b.get();

			assertThat(claimedByA).doesNotHaveDuplicates().doesNotContainAnyElementsOf(claimedByB);
			assertThat(claimedByB).doesNotHaveDuplicates();
			List<Long> all = new ArrayList<>(claimedByA);
			all.addAll(claimedByB);
			assertThat(all).containsExactlyInAnyOrderElementsOf(registered);
			for (long assetId : claimedByA) {
				assertThat(owner(assetId)).isEqualTo("worker-a");
			}
		}
		finally {
			workers.shutdownNow();
			this.jdbc.sql("""
					UPDATE video_transcode_jobs SET job_state = 'cancelled', lease_owner = NULL, lease_expires_at = NULL
					 WHERE video_asset_id IN (:assets)
					""").param("assets", registered).update();
		}
	}

	private Callable<List<Long>> claimAll(String owner, CountDownLatch start) {
		return () -> {
			start.await();
			List<Long> claimed = new ArrayList<>();
			for (Optional<Claimed> next; (next = this.transaction
				.execute((tx) -> this.records.claimNext(owner, Duration.ofMinutes(10)))).isPresent();) {
				claimed.add(next.get().assetId());
			}
			return claimed;
		};
	}

	private String owner(long assetId) {
		return this.jdbc.sql("SELECT lease_owner FROM video_transcode_jobs WHERE video_asset_id = :a")
			.param("a", assetId)
			.query(String.class)
			.single();
	}

}
