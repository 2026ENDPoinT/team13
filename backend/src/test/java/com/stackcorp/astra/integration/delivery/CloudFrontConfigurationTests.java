package com.stackcorp.astra.integration.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.stackcorp.astra.files.api.DeliverySigner;

/**
 * key pair ID가 있으면 서명기를 만든다. 배포(Terraform)는 key pair ID를 항상 넣으므로 개인 key가 없으면 무엇이 빠졌는지 알리고 시작하지 않는다.
 */
class CloudFrontConfigurationTests {

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withUserConfiguration(CloudFrontConfiguration.class)
		.withPropertyValues("astra.cloudfront.base-url=https://lms.example.ac.kr");

	@Test
	void doesNotSignWithoutAKeyPairId() {
		this.runner.run((context) -> {
			assertThat(context).hasNotFailed();
			assertThat(context).doesNotHaveBean(DeliverySigner.class);
		});
	}

	@Test
	void refusesToStartWithAKeyPairIdButNoPrivateKeyAndNamesTheMissingSecret() {
		this.runner.withPropertyValues("astra.cloudfront.key-pair-id=K2KEYPAIR").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).rootCause()
				.hasMessageContaining("astra.cloudfront.key-pair-id")
				.hasMessageContaining("ASTRA_CLOUDFRONT_PRIVATE_KEY");
		});
	}

}
