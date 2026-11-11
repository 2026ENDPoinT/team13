package com.stackcorp.astra.summary.job;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.summary.FakeLlm;

/**
 * worker process가 종료될 때 실행 중인 작업과 대기 작업이 어떻게 끝나는지. 종료 단계의 stop()을 직접 부른다.
 */
class WorkerShutdownTests extends IntegrationTest {

	@Autowired
	SummaryJobService service;

	@Autowired
	SummaryJobRunner runner;

	@Autowired
	FakeLlm llm;

	@Autowired
	TestData data;

	@BeforeEach
	void drain() {
		this.llm.reset(FakeLlm::honest);
		while (this.runner.runOnce()) {
			// 앞 테스트가 남긴 대기 작업을 비운다.
		}
	}

	@Test
	void closesRunningJobAsInterruptedAndClaimsNothingWhileStopped() {
		long fileId = this.data.pdfFile(TestPdfs.pdf(8), "clean");
		long running = this.service.request(fileId, null).id();
		this.llm.reset((request) -> {
			this.runner.stop();
			return FakeLlm.honest(request);
		});
		long queued;
		try {
			assertThat(this.runner.runOnce()).isTrue();

			// lease 만료를 기다리지 않고 바로 닫혀 같은 파일에 새 요청을 받는다.
			assertThat(this.service.get(running)).satisfies((job) -> {
				assertThat(job.state()).isEqualTo(JobState.FAILED);
				assertThat(job.errorCode()).isEqualTo("interrupted");
			});
			assertThat(this.llm.log).hasSize(1);
			queued = this.service.request(fileId, null).id();
			assertThat(this.runner.runOnce()).isFalse();
			assertThat(this.service.get(queued).state()).isEqualTo(JobState.QUEUED);
		}
		finally {
			this.runner.start();
		}

		this.llm.reset(FakeLlm::honest);
		assertThat(this.runner.runOnce()).isTrue();
		assertThat(this.service.get(queued).state()).isEqualTo(JobState.COMPLETED);
	}

	@Test
	void keepsUserCancellationWhenStopAndCancelMeet() {
		long fileId = this.data.pdfFile(TestPdfs.pdf(8), "clean");
		long jobId = this.service.request(fileId, null).id();
		this.llm.reset((request) -> {
			this.service.cancel(jobId);
			this.runner.stop();
			return FakeLlm.honest(request);
		});
		try {
			this.runner.runOnce();
		}
		finally {
			this.runner.start();
		}

		assertThat(this.service.get(jobId).state()).isEqualTo(JobState.CANCELLED);
	}

}
