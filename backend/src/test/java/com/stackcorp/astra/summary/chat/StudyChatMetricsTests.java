package com.stackcorp.astra.summary.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.LlmConsents;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.job.SummaryJob;
import com.stackcorp.astra.summary.job.SummaryJobService;
import com.stackcorp.astra.summary.job.SummaryRunnerDriver;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * 학습 대화의 대기열, 끝난 질문, 모델 호출 지표(architecture.md 9절, SC-09, ADR-0034). 모델 호출은 요약 작업과 같은 지표에
 * kind=study_chat으로 나뉘고, tag에는 ID나 질문 글이 들어가지 않는다.
 */
class StudyChatMetricsTests extends IntegrationTest {

	@Autowired
	StudyChatService chats;

	@Autowired
	StudyChatRunner runner;

	@Autowired
	StudyChatBacklog backlog;

	@Autowired
	SummaryJobService summaries;

	@Autowired
	FakeLlm llm;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MeterRegistry meters;

	@Autowired
	ApplicationContext context;

	long course;

	long student;

	long fileId;

	@BeforeEach
	void publishedMaterial() {
		this.llm.reset(FakeLlm::honest);
		while (SummaryRunnerDriver.runOnce(this.context) || this.runner.runOnce()) {
			// 앞 테스트가 남긴 작업과 질문을 비운다.
		}
		List<Long> consented = LlmConsents.consentedCourse(this.context, this.data);
		this.course = consented.get(0);
		long professor = consented.get(1);
		this.student = this.data.user("STUDENT");
		this.data.enroll(this.course, this.student);
		this.fileId = this.data.pdfFile(TestPdfs.pdfWithText("Page one explains sigma notation"), "clean");
		this.data.activity(this.course, "lecture_material", true, null, this.data.attachmentGroup(this.fileId));
		SummaryJob job = this.summaries.request(this.course, this.fileId, professor);
		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();
		this.summaries.publish(job.id(), professor);
	}

	@Test
	void reportsTheQueueAndCountsAnsweredExchangesCallsAndTokensAsStudyChat() {
		this.backlog.sample();
		double queued = gauge("queued");
		double answered = finished("answered", "none");
		double calls = calls("study_chat", "ok");
		double summaryCalls = calls("page_summary", "ok");
		double input = tokens("study_chat", "input");
		double output = tokens("study_chat", "output");

		StudyChat chat = this.chats.open(this.student, this.course, this.fileId);
		long exchange = this.chats.ask(chat, "sigma가 뭐예요? 제 번호는 23101234").exchange().id();
		this.backlog.sample();
		assertThat(gauge("queued")).isEqualTo(queued + 1);
		assertThat(this.meters.get("astra.studychat.exchanges.oldest.age").gauge().value()).isGreaterThanOrEqualTo(0);

		assertThat(this.runner.runOnce()).isTrue();

		Exchange done = this.chats.exchange(chat, exchange).orElseThrow().exchange();
		assertThat(done.state()).isEqualTo(ExchangeState.ANSWERED);
		assertThat(finished("answered", "none")).isEqualTo(answered + 1);
		assertThat(calls("study_chat", "ok")).isEqualTo(calls + done.calls());
		assertThat(calls("page_summary", "ok")).isEqualTo(summaryCalls);
		assertThat(tokens("study_chat", "input")).isEqualTo(input + done.inputTokens());
		assertThat(tokens("study_chat", "output")).isEqualTo(output + done.outputTokens());
		this.backlog.sample();
		assertThat(gauge("queued")).isEqualTo(queued);
		assertCodeTagsOnly(List.of("astra.llm.calls", "astra.llm.tokens", "astra.studychat.exchanges",
				"astra.studychat.exchanges.finished"));
	}

	@Test
	void countsFailedAndInterruptedExchangesByCode() {
		double provider = finished("failed", "provider");
		double interrupted = finished("failed", "interrupted");
		double rejectedCalls = calls("study_chat", "auth");
		this.llm.reset((request) -> {
			throw new LlmException(LlmException.Code.AUTH, "rejected");
		});
		StudyChat chat = this.chats.open(this.student, this.course, this.fileId);
		this.chats.ask(chat, "sigma?");

		assertThat(this.runner.runOnce()).isTrue();

		assertThat(finished("failed", "provider")).isEqualTo(provider + 1);
		assertThat(calls("study_chat", "auth")).isEqualTo(rejectedCalls + 1);

		// 실행하던 worker가 멈춰 lease가 끝난 질문은 interrupted로 닫혀 세어진다.
		long stuck = this.chats.ask(chat, "다시").exchange().id();
		this.jdbc.sql("""
				UPDATE study_chat_exchanges
				   SET exchange_state = 'running', lease_owner = 'stopped-worker',
				       lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
				 WHERE study_chat_exchange_id = :id
				""").param("id", stuck).update();
		this.runner.failExpired();

		assertThat(finished("failed", "interrupted")).isGreaterThanOrEqualTo(interrupted + 1);
	}

	private void assertCodeTagsOnly(List<String> names) {
		for (String name : names) {
			for (Meter meter : this.meters.find(name).meters()) {
				assertThat(meter.getId().getTags()).extracting(Tag::getValue)
					.allSatisfy((value) -> assertThat(value).matches("[a-z][a-z0-9_.-]*")
						.doesNotContain(String.valueOf(this.fileId), String.valueOf(this.student),
								String.valueOf(this.course)));
			}
		}
		assertThat(this.meters.find("astra.llm.calls").timers()).allSatisfy((t) -> assertThat(t.getId().getTags())
			.extracting(Tag::getKey)
			.containsExactlyInAnyOrder("model", "kind", "outcome", "role"));
	}

	private double gauge(String state) {
		return this.meters.get("astra.studychat.exchanges").tag("state", state).gauge().value();
	}

	private double finished(String state, String code) {
		Counter counter = this.meters.find("astra.studychat.exchanges.finished")
			.tag("state", state)
			.tag("code", code)
			.counter();
		return counter == null ? 0 : counter.count();
	}

	private double calls(String kind, String outcome) {
		Timer timer = this.meters.find("astra.llm.calls")
			.tag("model", "fake-model")
			.tag("kind", kind)
			.tag("outcome", outcome)
			.timer();
		return timer == null ? 0 : timer.count();
	}

	private double tokens(String kind, String type) {
		Counter counter = this.meters.find("astra.llm.tokens")
			.tag("model", "fake-model")
			.tag("kind", kind)
			.tag("type", type)
			.counter();
		return counter == null ? 0 : counter.count();
	}

}
