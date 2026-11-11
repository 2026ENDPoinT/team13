package com.stackcorp.astra.summary.job;

import com.stackcorp.astra.shared.metrics.MetricTags;
import com.stackcorp.astra.summary.api.LlmClient;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.api.LlmResponse;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * 모델 호출의 수, 시간, token을 모델 이름별로 잰다(architecture.md 9절, SC-09). 비용은 token 수에 제공자 단가를 곱해 지표 저장소에서
 * 계산한다. 요청과 응답 본문은 어디에도 남기지 않는다(ADR-0007 9번).
 * <ul>
 * <li>astra.llm.calls{model, outcome}: outcome은 ok, 실패 code(network, rate_limit, auth 등) 또는 error(그 밖의 예외)</li>
 * <li>astra.llm.tokens{model, type=input|output}: 제공자가 응답에 적은 token 수</li>
 * </ul>
 * 모델 이름은 설정값 하나라 tag 값의 종류가 늘지 않는다.
 */
final class MeteredLlmClient implements LlmClient {

	private final LlmClient delegate;

	private final MeterRegistry meters;

	private final String model;

	MeteredLlmClient(LlmClient delegate, MeterRegistry meters) {
		this.delegate = delegate;
		this.meters = meters;
		this.model = MetricTags.code(delegate.model());
	}

	@Override
	public String model() {
		return this.delegate.model();
	}

	@Override
	public boolean acceptsImages() {
		return this.delegate.acceptsImages();
	}

	@Override
	public LlmResponse complete(LlmRequest request) {
		Timer.Sample sample = Timer.start(this.meters);
		String outcome = "error";
		try {
			LlmResponse response = this.delegate.complete(request);
			outcome = "ok";
			tokens("input", response.usage().inputTokens());
			tokens("output", response.usage().outputTokens());
			return response;
		}
		catch (LlmException ex) {
			outcome = MetricTags.code(ex.code().name());
			throw ex;
		}
		finally {
			sample.stop(Timer.builder("astra.llm.calls")
				.description("모델 호출 시간과 결과")
				.tag("model", this.model)
				.tag("outcome", outcome)
				.register(this.meters));
		}
	}

	private void tokens(String type, long count) {
		Counter.builder("astra.llm.tokens")
			.description("모델 호출의 token 수")
			.baseUnit("tokens")
			.tag("model", this.model)
			.tag("type", type)
			.register(this.meters)
			.increment(count);
	}

}
