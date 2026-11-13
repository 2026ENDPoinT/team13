package com.stackcorp.astra.integration.llm;

import java.util.List;
import java.util.Locale;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.files.api.TextModel;
import com.stackcorp.astra.files.api.TextModelException;
import com.stackcorp.astra.shared.metrics.MetricTags;
import com.stackcorp.astra.summary.api.LlmClient;
import com.stackcorp.astra.summary.api.LlmContent;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.api.LlmMessage;
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.api.LlmResponse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * files의 녹음 요약 port({@link TextModel}, ADR-0041)를 요약 모듈의 모델 port({@link LlmClient}, ADR-0008)로 구현한다. 같은 제공자,
 * key, 동시 요청 상한을 쓴다. {@link LlmClient} bean은 key가 있고 {@code astra.llm.enabled}가 켜져 있을 때만 있다
 * ({@link LlmConfiguration}). 쪽별 요약의 교수자 동의(ADR-0032)는 거치지 않는다. 녹음은 학생 본인의 콘텐츠이고 자동 처리는 소유자
 * 결정이다(ADR-0041).
 * <p>
 * 지표는 쪽별 요약, 학습 대화와 같은 이름에 {@code kind=recording_summary}로 남긴다(MeteredLlmClient와 같은 형식). 요청과 응답
 * 본문은 어디에도 남기지 않는다.
 */
@Component
class LlmTextModel implements TextModel {

	static final String KIND = "recording_summary";

	private final ObjectProvider<LlmClient> llm;

	private final MeterRegistry meters;

	LlmTextModel(ObjectProvider<LlmClient> llm, MeterRegistry meters) {
		this.llm = llm;
		this.meters = meters;
	}

	@Override
	public boolean available() {
		return this.llm.getIfAvailable() != null;
	}

	@Override
	public String complete(String system, String user, int maxOutputTokens) {
		LlmClient client = this.llm.getIfAvailable();
		if (client == null) {
			throw new TextModelException(TextModelException.Kind.CONFIG, "모델 제공자가 설정되지 않았습니다.", null);
		}
		LlmRequest request = new LlmRequest(LlmRequest.Purpose.RECORDING, system,
				List.of(new LlmMessage(LlmMessage.Role.USER, List.of(new LlmContent.Text(user)))), maxOutputTokens);
		String model = MetricTags.code(client.model());
		Timer.Sample sample = Timer.start(this.meters);
		String outcome = "error";
		try {
			LlmResponse response = client.complete(request);
			outcome = "ok";
			tokens(model, "input", response.usage().inputTokens());
			tokens(model, "output", response.usage().outputTokens());
			if (response.stopReason() == LlmResponse.StopReason.MAX_TOKENS) {
				throw new TextModelException(TextModelException.Kind.TRUNCATED, "모델 응답이 출력 상한에서 잘렸습니다.", null);
			}
			if (response.text() == null || response.text().isBlank()) {
				throw new TextModelException(TextModelException.Kind.UNAVAILABLE, "모델 응답에 글이 없습니다.", null);
			}
			return response.text();
		}
		catch (LlmException ex) {
			outcome = MetricTags.code(ex.code().name());
			throw new TextModelException(
					ex.retryable() || ex.code() == LlmException.Code.PROTOCOL ? TextModelException.Kind.UNAVAILABLE
							: TextModelException.Kind.CONFIG,
					"모델 호출이 실패했습니다 (" + ex.code().name().toLowerCase(Locale.ROOT) + ")", ex);
		}
		finally {
			sample.stop(Timer.builder("astra.llm.calls")
				.description("모델 호출 시간과 결과")
				.tag("model", model)
				.tag("kind", KIND)
				.tag("outcome", outcome)
				.register(this.meters));
		}
	}

	private void tokens(String model, String type, long count) {
		Counter.builder("astra.llm.tokens")
			.description("모델 호출의 token 수")
			.baseUnit("tokens")
			.tag("model", model)
			.tag("kind", KIND)
			.tag("type", type)
			.register(this.meters)
			.increment(count);
	}

}
