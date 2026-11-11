package com.stackcorp.astra.summary.job;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.LlmConsents;
import com.stackcorp.astra.summary.api.LlmException;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * 요약 작업 대기열, 작업 결과, 모델 호출의 지표(architecture.md 9절, SC-09, ADR-0034). 모델 호출은 모델 이름과 쓰임
 * (kind=page_summary)별로 수, 시간, token만 센다.
 */
class SummaryMetricsTests extends IntegrationTest {

	@Autowired
	SummaryJobService service;

	@Autowired
	SummaryJobRunner runner;

	@Autowired
	SummaryBacklog backlog;

	@Autowired
	FakeLlm llm;

	@Autowired
	TestData data;

	@Autowired
	MeterRegistry meters;

	@Autowired
	ApplicationContext context;

	long course;

	long professor;

	@BeforeEach
	void drain() {
		List<Long> consented = LlmConsents.consentedCourse(this.context, this.data);
		this.course = consented.get(0);
		this.professor = consented.get(1);
		this.llm.reset(FakeLlm::honest);
		while (this.runner.runOnce()) {
			// 앞 테스트가 남긴 대기 작업을 비운다.
		}
	}

	@Test
	void reportsTheQueueAndCountsOutcomesCallsAndTokensByModel() {
		this.backlog.sample();
		double queued = gauge("queued");
		double completed = finished("completed", "none");
		double calls = calls("ok");
		double inputTokens = tokens("input");
		double outputTokens = tokens("output");

		SummaryJob job = this.service.request(this.course, this.data.pdfFile(TestPdfs.pdf(3), "clean"), this.professor);
		this.backlog.sample();
		assertThat(gauge("queued")).isEqualTo(queued + 1);
		assertThat(this.meters.get("astra.summary.jobs.oldest.age").gauge().value()).isGreaterThanOrEqualTo(0);

		assertThat(this.runner.runOnce()).isTrue();

		SummaryJob done = this.service.get(job.id());
		assertThat(done.state()).isEqualTo(JobState.COMPLETED);
		assertThat(finished("completed", "none")).isEqualTo(completed + 1);
		assertThat(calls("ok")).isEqualTo(calls + done.calls());
		assertThat(tokens("input")).isEqualTo(inputTokens + done.inputTokens());
		assertThat(tokens("output")).isEqualTo(outputTokens + done.outputTokens());
		this.backlog.sample();
		assertThat(gauge("queued")).isEqualTo(queued);
		assertThat(this.meters.find("astra.llm.calls").timers()).allSatisfy((t) -> assertThat(t.getId().getTags())
			.extracting(Tag::getKey)
			.containsExactlyInAnyOrder("model", "kind", "outcome", "role"));
	}

	@Test
	void countsFailedJobsAndFailedCallsByCode() {
		double failed = finished("failed", "provider");
		double rejectedCalls = calls("auth");
		this.llm.reset((request) -> {
			throw new LlmException(LlmException.Code.AUTH, "rejected");
		});

		SummaryJob job = this.service.request(this.course, this.data.pdfFile(TestPdfs.pdf(1), "clean"), this.professor);
		this.runner.runOnce();

		assertThat(this.service.get(job.id()).state()).isEqualTo(JobState.FAILED);
		assertThat(finished("failed", "provider")).isEqualTo(failed + 1);
		assertThat(calls("auth")).isGreaterThan(rejectedCalls);
	}

	private double gauge(String state) {
		return this.meters.get("astra.summary.jobs").tag("state", state).gauge().value();
	}

	private double finished(String state, String code) {
		Counter counter = this.meters.find("astra.summary.jobs.finished").tag("state", state).tag("code", code).counter();
		return counter == null ? 0 : counter.count();
	}

	private double calls(String outcome) {
		Timer timer = this.meters.find("astra.llm.calls")
			.tag("model", "fake-model")
			.tag("kind", "page_summary")
			.tag("outcome", outcome)
			.timer();
		return timer == null ? 0 : timer.count();
	}

	private double tokens(String type) {
		Counter counter = this.meters.find("astra.llm.tokens")
			.tag("model", "fake-model")
			.tag("kind", "page_summary")
			.tag("type", type)
			.counter();
		return counter == null ? 0 : counter.count();
	}

}
