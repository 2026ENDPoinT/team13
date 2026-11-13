package com.stackcorp.astra.integration.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * 앱 직접 응답(ADR-0009)을 켰는데 서명 key가 없거나 Base64가 아니면 시작하지 않고, 넣어야 할 환경 변수를 알린다.
 */
class OriginDeliveryConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(OriginDeliveryConfiguration.class)
		.withPropertyValues("astra.delivery.origin.enabled=true");

	@Test
	void startsWithA32ByteBase64Key() {
		this.runner.withPropertyValues("astra.delivery.origin.signing-key=" + Base64.getEncoder().encodeToString(new byte[32]))
			.run((context) -> assertThat(context).hasSingleBean(OriginDeliverySigner.class));
	}

	@Test
	void refusesToStartWithoutAKeyAndNamesTheVariable() {
		this.runner.run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause().hasMessageContaining("ASTRA_DELIVERY_ORIGIN_SIGNING_KEY");
		});
	}

	@Test
	void refusesToStartWithAKeyThatIsNotBase64AndNamesTheVariable() {
		this.runner.withPropertyValues("astra.delivery.origin.signing-key=change-me-base64-32-bytes").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("ASTRA_DELIVERY_ORIGIN_SIGNING_KEY")
				.hasMessageContaining("Base64");
		});
	}

}
