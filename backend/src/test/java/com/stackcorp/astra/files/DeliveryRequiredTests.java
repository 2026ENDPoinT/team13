package com.stackcorp.astra.files;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.stackcorp.astra.files.api.DeliveryCookies;
import com.stackcorp.astra.files.api.DeliverySigner;

/**
 * 서명기가 없으면 /doc, /vod cookie를 내지 않아 쪽 이미지와 영상이 열리지 않는다. compose.yaml은 api에 astra.delivery.required를 켜서
 * 그 상태로 시작하지 않게 하고, 무엇을 설정할지 알린다(ADR-0009).
 */
class DeliveryRequiredTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(DeliveryCookieService.class);

	@Test
	void startsWithoutASignerByDefaultAndIssuesNoCookie() {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context.getBean(DeliveryCookies.class).forDocument("a".repeat(64))).isEmpty();
		});
	}

	@Test
	void refusesToStartWithoutASignerWhenDeliveryIsRequiredAndNamesBothWaysToConfigureIt() {
		this.runner.withPropertyValues("astra.delivery.required=true").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).hasMessageContaining("ASTRA_DELIVERY_ORIGIN_ENABLED")
				.hasMessageContaining("ASTRA_DELIVERY_ORIGIN_SIGNING_KEY")
				.hasMessageContaining("ASTRA_CLOUDFRONT_KEY_PAIR_ID");
		});
	}

	@Test
	void startsWhenDeliveryIsRequiredAndASignerIsConfigured() {
		DeliverySigner signer = (path, expiresAt) -> List.of(new DeliverySigner.SignedCookie("ASTRA-Delivery", "1.x"));
		this.runner.withPropertyValues("astra.delivery.required=true")
			.withBean(DeliverySigner.class, () -> signer)
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context.getBean(DeliveryCookies.class).forDocument("a".repeat(64))).hasSize(1);
			});
	}

}
