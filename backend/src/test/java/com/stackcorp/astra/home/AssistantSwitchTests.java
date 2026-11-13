package com.stackcorp.astra.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.http.HttpStatus;

import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.api.LlmClient;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * 도우미 스위치(ADR-0044 14번). {@code astra.assistant.enabled=false}면 규칙 답까지 503 {@code llm_disabled}이고, 모델 bean이 있어도
 * {@code astra.llm.enabled=false}면 모델을 쓰지 않는다. 배포 설정마다 Spring context를 새로 띄우지 않도록 객체를 직접 만든다. 외부 AI
 * 스위치와 제공자 없음의 API 응답은 LlmDisabledTests, LlmNotConfiguredTests가 본다.
 */
class AssistantSwitchTests {

	@Test
	void theAssistantSwitchTurnsTheWholeRouteOff() {
		AssistantService off = new AssistantService(properties(false), null, null, null);

		assertThatThrownBy(off::requireEnabled).isInstanceOfSatisfying(ApiException.class, (ex) -> {
			assertThat(ex.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
			assertThat(ex.code()).isEqualTo("llm_disabled");
		});
		new AssistantService(properties(true), null, null, null).requireEnabled();
	}

	@Test
	void theModelIsUnavailableWhenExternalAiIsSwitchedOffEvenWithAClient() {
		StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("llm", new FakeLlm()));

		assertThat(new AssistantModel(beans.getBeanProvider(LlmClient.class), properties(true), new SimpleMeterRegistry(),
				false)
			.available()).isFalse();
		assertThat(new AssistantModel(beans.getBeanProvider(LlmClient.class), properties(true), new SimpleMeterRegistry(),
				true)
			.available()).isTrue();
		assertThat(new AssistantModel(new StaticListableBeanFactory().getBeanProvider(LlmClient.class), properties(true),
				new SimpleMeterRegistry(), true)
			.available()).isFalse();
	}

	private static AssistantProperties properties(boolean enabled) {
		return new AssistantProperties(enabled, Duration.ofSeconds(20), 30, 10, 60, Duration.ofMinutes(1), 600);
	}

}
