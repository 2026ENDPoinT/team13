package com.stackcorp.astra.summary.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.LlmConsents;
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

	@Autowired
	ApplicationContext context;

	long course;

	long professor;

	@BeforeEach
	void resetModel() {
		this.llm.reset(FakeLlm::honest);
		while (this.runner.runOnce()) {
			// 앞 테스트가 남긴 대기 작업을 비운다.
		}
		List<Long> consented = LlmConsents.consentedCourse(this.context, this.data);
		this.course = consented.get(0);
		this.professor = consented.get(1);
	}

	@Test
	void requestedJobRendersSummarizesAndPublishes() {
		long fileId = pdfFile(TestPdfs.pdf(3), "clean");

		SummaryJob queued = request(fileId);
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
		request(fileId);

		SummaryJobException ex = catchThrowableOfType(SummaryJobException.class,
				() -> request(fileId));

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
		SummaryJob failed = request(fileId);
		this.runner.runOnce();
		assertThat(this.service.get(failed.id())).satisfies((j) -> {
			assertThat(j.state()).isEqualTo(JobState.FAILED);
			assertThat(j.errorCode()).isEqualTo("provider");
			assertThat(j.calls()).isEqualTo(2);
		});
		this.llm.reset(FakeLlm::honest);

		SummaryJob retry = this.service.retry(this.course, failed.id(), this.professor);
		this.runner.runOnce();

		assertThat(this.service.get(retry.id()).state()).isEqualTo(JobState.COMPLETED);
		assertThat(this.llm.log).singleElement().satisfies((r) -> assertThat(FakeLlm.isStory(r)).isTrue());
		assertThat(retry.retryOfJobId()).isEqualTo(failed.id());
	}

	@Test
	@ExtendWith(OutputCaptureExtension.class)
	void providerFailuresStoreAFixedSentencePerKind(CapturedOutput output) {
		// 제공자 문구, 예외 class 이름, 주소, key 조각은 화면 문장에 들어가지 않는다. 일시적인 실패는 재시도한 뒤에 실패한다.
		String raw = "java.net.http.HttpConnectTimeoutException: HTTP connect timed out https://llm.example/v1 sk-secret";
		Map<LlmException.Code, String> expected = new LinkedHashMap<>();
		expected.put(LlmException.Code.AUTH, "모델 제공자가 API key를 거부했습니다. 관리자에게 알려 주세요.");
		expected.put(LlmException.Code.BAD_REQUEST, "모델 제공자가 요청을 거부했습니다. 관리자에게 알려 주세요.");
		expected.put(LlmException.Code.PROTOCOL, "모델 제공자의 응답을 처리하지 못했습니다. 다시 시도해 주세요.");
		expected.put(LlmException.Code.RATE_LIMIT, "모델 제공자의 요청 한도에 걸렸습니다. 잠시 후 다시 시도해 주세요.");
		expected.put(LlmException.Code.SERVER, "모델 제공자 서버에 오류가 났습니다. 잠시 후 다시 시도해 주세요.");
		expected.put(LlmException.Code.NETWORK, "모델 제공자에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.");
		for (Map.Entry<LlmException.Code, String> kind : expected.entrySet()) {
			this.llm.reset((req) -> {
				throw new LlmException(kind.getKey(), "제공자에 연결하지 못했습니다: " + raw);
			});
			SummaryJob job = request(pdfFile(TestPdfs.pdf(1), "clean"));
			this.runner.runOnce();
			assertThat(this.service.get(job.id())).as(kind.getKey().name()).satisfies((j) -> {
				assertThat(j.state()).isEqualTo(JobState.FAILED);
				assertThat(j.errorCode()).isEqualTo("provider");
				assertThat(j.errorMessage()).isEqualTo(kind.getValue());
			});
		}
		// 모델 client 밖의 예외도 class 이름 없이 같은 종류의 문장이다.
		this.llm.reset((req) -> {
			throw new IllegalStateException(raw);
		});
		SummaryJob job = request(pdfFile(TestPdfs.pdf(1), "clean"));
		this.runner.runOnce();
		assertThat(this.service.get(job.id()).errorMessage()).isEqualTo("모델 호출이 실패했습니다. 잠시 후 다시 시도해 주세요.");
		// 그 예외의 종류와 stack은 서버 로그에만 남는다.
		assertThat(output.getAll().lines()
			.filter((line) -> line.contains("summary job provider call failed jobId=" + job.id() + " ")))
			.singleElement()
			.asString()
			.contains("error=java.lang.IllegalStateException", "stack_trace");
	}

	@Test
	void cancelsQueuedJobOnce() {
		long fileId = pdfFile(TestPdfs.pdf(1), "clean");
		SummaryJob job = request(fileId);

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
		jobId.set(request(fileId).id());

		this.runner.runOnce();

		SummaryJob job = this.service.get(jobId.get());
		assertThat(job.state()).isEqualTo(JobState.CANCELLED);
		assertThat(job.cancelRequested()).isTrue();
		assertThat(this.llm.log).hasSize(1);
	}

	@Test
	void closesJobsWhoseWorkerStoppedRenewingLease() {
		long fileId = pdfFile(TestPdfs.pdf(1), "clean");
		SummaryJob job = request(fileId);
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
		assertThat(request(fileId).state()).isEqualTo(JobState.QUEUED);
	}

	@Test
	void rejectsFilesThatAreNotCleanPdfs() {
		assertThat(catchThrowableOfType(SummaryJobException.class,
				() -> request(pdfFile(TestPdfs.pdf(1), "pending")))
			.code()).isEqualTo(Code.FILE_NOT_READY);
		long text = this.data.file("notes.txt", "text/plain", "hello".getBytes(), "clean");
		assertThat(catchThrowableOfType(SummaryJobException.class, () -> request(text)).code())
			.isEqualTo(Code.UNSUPPORTED_FILE);
		assertThat(catchThrowableOfType(SummaryJobException.class, () -> request(Long.MAX_VALUE))
			.code()).isEqualTo(Code.NOT_FOUND);
	}

	@Test
	void failsJobWithoutCallingModelWhenPdfCannotBeConverted() {
		long fileId = pdfFile(TestPdfs.pdf(1, "user-pw", null), "clean");
		SummaryJob job = request(fileId);

		this.runner.runOnce();

		assertThat(this.service.get(job.id())).satisfies((j) -> {
			assertThat(j.state()).isEqualTo(JobState.FAILED);
			assertThat(j.errorCode()).isEqualTo("file");
			// converter 문구 대신 실패 종류의 문장이다.
			assertThat(j.errorMessage()).isEqualTo("암호가 걸린 PDF라 쪽 이미지로 바꾸지 못했습니다. 암호를 푼 파일을 올려 주세요.");
		});
		assertThat(this.llm.log).isEmpty();
		assertThat(this.jdbc.sql("SELECT error_code FROM conversion_jobs WHERE file_id = :id")
			.param("id", fileId)
			.query(String.class)
			.single()).isEqualTo("encrypted");
	}

	private SummaryJob request(long fileId) {
		return this.service.request(this.course, fileId, this.professor);
	}

	private long pdfFile(byte[] pdf, String scanState) {
		return this.data.pdfFile(pdf, scanState);
	}

	private boolean exists(String bucket, String key) {
		return !this.s3.listObjectsV2((b) -> b.bucket(bucket).prefix(key)).contents().isEmpty();
	}

}
