package com.stackcorp.astra.shared.idempotency;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(IdempotencyProperties.class)
class IdempotencyConfiguration {

	@Configuration(proxyBeanMethods = false)
	@Profile("worker")
	@EnableScheduling
	static class WorkerScheduling {

	}

}
