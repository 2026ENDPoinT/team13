package com.stackcorp.astra.integration.deepgram;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepgramProperties.class)
class DeepgramConfiguration {

	// key가 없어도 bean은 만든다. available()이 false라 files가 전사 작업을 만들지 않는다(ADR-0041).
	@Bean(destroyMethod = "close")
	DeepgramSpeechToText deepgramSpeechToText(DeepgramProperties properties) {
		return new DeepgramSpeechToText(properties);
	}

}
