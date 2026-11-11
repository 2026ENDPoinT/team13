package com.stackcorp.astra.files.filebox;

import static com.stackcorp.astra.files.filebox.FileboxCalls.command;
import static com.stackcorp.astra.files.filebox.FileboxCalls.file;
import static com.stackcorp.astra.files.filebox.FileboxCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.files.StorageGcDriver;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * 파일함 ZIP 대기열과 만들기 결과의 지표(architecture.md 9절, ADR-0028, ADR-0034). tag에는 결과와 사유 코드만 있다.
 */
class ZipJobMetricsTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ZipJobWorker worker;

	@Autowired
	FileboxProperties properties;

	@Autowired
	MeterRegistry meters;

	@Autowired
	ApplicationContext context;

	ApiClient owner;

	@BeforeEach
	void drained() {
		while (this.worker.runOnce() > 0) {
			// 앞 테스트가 남긴 작업을 비운다.
		}
		this.owner = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	@Test
	void reportsTheQueueAndCountsBuildsByOutcomeAndReason() throws Exception {
		StorageGcDriver.sampleBacklog(this.context);
		double requested = gauge("requested");
		double ready = builds("ready", "none");
		double unavailable = builds("failed", "item_unavailable");
		long kept = file(this.owner, null, "kept.txt", text());
		long gone = file(this.owner, null, "gone.txt", text());

		long readyJob = request("m-kept", kept);
		long failedJob = request("m-gone", kept, gone);
		command(this.owner, "trash", gone).andExpect(status().isOk());
		StorageGcDriver.sampleBacklog(this.context);
		assertThat(gauge("requested")).isEqualTo(requested + 2);
		assertThat(this.meters.get("astra.files.zip.jobs.oldest.age").gauge().value()).isGreaterThanOrEqualTo(0);

		assertThat(this.worker.runOnce()).isEqualTo(2);

		assertThat(state(readyJob)).isEqualTo("ready");
		assertThat(state(failedJob)).isEqualTo("failed");
		assertThat(builds("ready", "none")).isEqualTo(ready + 1);
		assertThat(builds("failed", "item_unavailable")).isEqualTo(unavailable + 1);
		StorageGcDriver.sampleBacklog(this.context);
		assertThat(gauge("requested")).isEqualTo(requested);
		assertCodeTagsOnly();
	}

	@Test
	void countsJobsOfAStoppedWorkerThatUsedEveryAttemptAsInterrupted() throws Exception {
		double interrupted = builds("failed", "interrupted");
		long job = request("m-stuck", file(this.owner, null, "stuck.txt", text()));
		assertThat(this.worker.claim()).isPresent();
		this.jdbc.sql("""
				UPDATE zip_jobs SET attempt_count = :max, lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
				 WHERE zip_job_id = :id
				""").param("max", this.properties.zip().maxAttempts()).param("id", job).update();

		this.worker.claim();

		assertThat(state(job)).isEqualTo("failed");
		assertThat(builds("failed", "interrupted")).isEqualTo(interrupted + 1);
	}

	private long request(String key, long... itemIds) throws Exception {
		return ApiClient.json(this.owner.send(MockMvcRequestBuilders.post("/api/filebox/zip-jobs")
			.header("Idempotency-Key", key + "-" + System.nanoTime())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"itemIds\":" + FileboxCalls.ids(itemIds) + "}")).andExpect(status().isAccepted()))
			.get("jobId")
			.asLong();
	}

	private String state(long jobId) {
		return this.jdbc.sql("SELECT zip_state FROM zip_jobs WHERE zip_job_id = :id")
			.param("id", jobId)
			.query(String.class)
			.single();
	}

	private void assertCodeTagsOnly() {
		for (String name : List.of("astra.files.zip.builds", "astra.files.zip.jobs", "astra.files.zip.jobs.oldest.age")) {
			for (Meter meter : this.meters.find(name).meters()) {
				assertThat(meter.getId().getTags()).extracting(Tag::getValue)
					.allSatisfy((value) -> assertThat(value).matches("[a-z][a-z_.-]*"));
			}
		}
	}

	private double gauge(String state) {
		return this.meters.get("astra.files.zip.jobs").tag("state", state).gauge().value();
	}

	private double builds(String outcome, String code) {
		Counter counter = this.meters.find("astra.files.zip.builds").tag("outcome", outcome).tag("code", code).counter();
		return counter == null ? 0 : counter.count();
	}

}
