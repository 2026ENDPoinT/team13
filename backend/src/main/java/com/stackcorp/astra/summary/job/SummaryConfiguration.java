package com.stackcorp.astra.summary.job;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SummaryProperties.class)
class SummaryConfiguration {

	@Configuration(proxyBeanMethods = false)
	@Profile("worker")
	@EnableScheduling
	static class WorkerScheduling {

	}

}
