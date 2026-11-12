package com.stackcorp.astra.integration.transcoder;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TranscoderProperties.class)
class TranscoderConfiguration {

	@Bean(destroyMethod = "close")
	HttpVideoTranscoder videoTranscoder(TranscoderProperties properties) {
		return new HttpVideoTranscoder(properties);
	}

}
