package com.stackcorp.astra.summary.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.api.LlmContent;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.job.SummaryJobException.Code;
import com.stackcorp.astra.summary.pack.Slide;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * 요청부터 rendition, 생성, 게시까지. MySQL, S3 호환 저장소, converter는 실제로 띄우고 모델만 가짜로 둔다.
 */
class SummaryJobFlowTests extends IntegrationTest {

	@Autowired
	SummaryJobService service;

	@Autowired
	SummaryJobRunner runner;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	S3Client s3;

	@Autowired
	TestBuckets buckets;

	@Autowired
	FakeLlm llm;

	@Autowired
	TestData data;

	@BeforeEach
	void resetModel() {
		this.llm.reset(FakeLlm::honest);
		while (this.runner.runOnce()) {
			// 앞 테스트가 남긴 대기 작업을 비운다.
		}
	}

	@Test
	void requestedJobRendersSummarizesAndPublishes() {
		long fileId = pdfFile(TestPdfs.pdf(3), "clean");

		SummaryJob queued = this.service.request(fileId, null);
		assertThat(queued.state()).isEqualTo(JobState.QUEUED);
		assertThat(this.service.published(fileId)).isEmpty();

		assertThat(this.runner.runOnce()).isTrue();

		SummaryJob done = this.service.get(queued.id());
		assertThat(done.state()).isEqualTo(JobState.COMPLETED);
		assertThat(done.stage()).isEqualTo("done");
		assertThat(done.pagesDone()).isEqualTo(3);
		assertThat(done.calls()).isEqualTo(2);
		assertThat(done.inputTokens()).isEqualTo(20);
		assertThat(done.modelName()).isEqualTo("fake-model");
		String key = this.jdbc.sql("SELECT converted_doc_key FROM conversion_jobs WHERE file_id = :id AND conversion_state = 'ready'")
			.param("id", fileId)
			.query(String.class)
			.single();
		assertThat(exists(this.buckets.delivery(), "doc/" + key + "/003.jpg")).isTrue();
		assertThat(exists(this.buckets.delivery(), "renditions/" + key + "/model/001.jpg")).isFalse();
		assertThat(exists(this.buckets.content(), "renditions/" + key + "/model/001.jpg")).isTrue();
		// 모델에 쪽 이미지와 추출 텍스트를 보냈다.
		LlmRequest first = this.llm.log.getFirst();
		assertThat(first.messages().getFirst().content()).filteredOn(LlmContent.Image.class::isInstance).hasSize(3);
		assertThat(FakeLlm.text(first)).contains("Page 2 sigma");

		PublishedSummary published = this.service.publish(queued.id(), null);

		assertThat(published.result().pack().slides()).extracting(Slide::page).containsExactly(1, 2, 3);
		assertThat(this.service.published(fileId)).hasValueSatisfying((p) -> {
			assertThat(p.jobId()).isEqualTo(queued.id());
			assertThat(p.result().pack().question()).isEqualTo("이 자료는 무엇을 설명하나요?");
		});
		assertThat(this.runner.runOnce()).isFalse();
	}

	@Test
	void allowsOneActiveJobPerFile() {
		long fileId = pdfFile(TestPdfs.pdf(1), "clean");
		this.service.request(fileId, null);

		SummaryJobException ex = catchThrowableOfType(SummaryJobException.class,
				() -> this.service.request(fileId, null));

		assertThat(ex.code()).isEqualTo(Code.CONFLICT);
	}

	@Test
	void retryContinuesFromCheckpointsOfFailedJob() {
		long fileId = pdfFile(TestPdfs.pdf(2), "clean");
		this.llm.reset((req) -> {
			if (FakeLlm.isStory(req)) {
				throw new LlmException(LlmException.Code.AUTH, "rejected");
			}
			return FakeLlm.honest(req);
		});
		SummaryJob failed = this.service.request(fileId, null);
		this.runner.runOnce();
		assertThat(this.service.get(failed.id())).satisfies((j) -> {
			assertThat(j.state()).isEqualTo(JobState.FAILED);
			assertThat(j.errorCode()).isEqualTo("provider");
			assertThat(j.calls()).isEqualTo(2);
		});
		this.llm.reset(FakeLlm::honest);

		SummaryJob retry = this.service.retry(failed.id(), null);
		this.runner.runOnce();

		assertThat(this.service.get(retry.id()).state()).isEqualTo(JobState.COMPLETED);
		assertThat(this.llm.log).singleElement().satisfies((r) -> assertThat(FakeLlm.isStory(r)).isTrue());
		assertThat(retry.retryOfJobId()).isEqualTo(failed.id());
	}

	@Test
	void cancelsQueuedJobOnce() {
		long fileId = pdfFile(TestPdfs.pdf(1), "clean");
		SummaryJob job = this.service.request(fileId, null);

		assertThat(this.service.cancel(job.id()).state()).isEqualTo(JobState.CANCELLED);
		assertThat(catchThrowableOfType(SummaryJobException.class, () -> this.service.cancel(job.id())).code())
			.isEqualTo(Code.INVALID_STATE);
		assertThat(this.runner.runOnce()).isFalse();
	}

	@Test
	void stopsRunningJobWhenCancelIsRequested() {
		long fileId = pdfFile(TestPdfs.pdf(8), "clean");
		AtomicReference<Long> jobId = new AtomicReference<>();
		this.llm.reset((req) -> {
			this.service.cancel(jobId.get());
			return FakeLlm.honest(req);
		});
		jobId.set(this.service.request(fileId, null).id());

		this.runner.runOnce();

		SummaryJob job = this.service.get(jobId.get());
		assertThat(job.state()).isEqualTo(JobState.CANCELLED);
		assertThat(job.cancelRequested()).isTrue();
		assertThat(this.llm.log).hasSize(1);
	}

	@Test
	void closesJobsWhoseWorkerStoppedRenewingLease() {
		long fileId = pdfFile(TestPdfs.pdf(1), "clean");
		SummaryJob job = this.service.request(fileId, null);
		this.jdbc.sql("""
				UPDATE summary_jobs SET job_state = 'running', lease_owner = 'gone',
				       lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
				 WHERE summary_job_id = :id
				""").param("id", job.id()).update();

		this.runner.failExpired();

		assertThat(this.service.get(job.id())).satisfies((j) -> {
			assertThat(j.state()).isEqualTo(JobState.FAILED);
			assertThat(j.errorCode()).isEqualTo("interrupted");
		});
		// 실패한 작업은 자동으로 다시 실행하지 않고, 같은 파일에 새 요청을 받는다.
		assertThat(this.service.request(fileId, null).state()).isEqualTo(JobState.QUEUED);
	}

	@Test
	void rejectsFilesThatAreNotCleanPdfs() {
		assertThat(catchThrowableOfType(SummaryJobException.class,
				() -> this.service.request(pdfFile(TestPdfs.pdf(1), "pending"), null))
			.code()).isEqualTo(Code.FILE_NOT_READY);
		long text = this.data.file("notes.txt", "text/plain", "hello".getBytes(), "clean");
		assertThat(catchThrowableOfType(SummaryJobException.class, () -> this.service.request(text, null)).code())
			.isEqualTo(Code.UNSUPPORTED_FILE);
		assertThat(catchThrowableOfType(SummaryJobException.class, () -> this.service.request(Long.MAX_VALUE, null))
			.code()).isEqualTo(Code.NOT_FOUND);
	}

	@Test
	void failsJobWithoutCallingModelWhenPdfCannotBeConverted() {
		long fileId = pdfFile(TestPdfs.pdf(1, "user-pw", null), "clean");
		SummaryJob job = this.service.request(fileId, null);

		this.runner.runOnce();

		assertThat(this.service.get(job.id())).satisfies((j) -> {
			assertThat(j.state()).isEqualTo(JobState.FAILED);
			assertThat(j.errorCode()).isEqualTo("file");
			assertThat(j.errorMessage()).contains("encrypted");
		});
		assertThat(this.llm.log).isEmpty();
		assertThat(this.jdbc.sql("SELECT error_code FROM conversion_jobs WHERE file_id = :id")
			.param("id", fileId)
			.query(String.class)
			.single()).isEqualTo("encrypted");
	}

	private long pdfFile(byte[] pdf, String scanState) {
		return this.data.pdfFile(pdf, scanState);
	}

	private boolean exists(String bucket, String key) {
		return !this.s3.listObjectsV2((b) -> b.bucket(bucket).prefix(key)).contents().isEmpty();
	}

}
