package com.stackcorp.astra.integration.converter;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.stackcorp.astra.files.api.PageConverter;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConverterProperties.class)
class ConverterConfiguration {

	@Bean(destroyMethod = "close")
	HttpPageConverter pageConverter(ConverterProperties properties) {
		return new HttpPageConverter(properties);
	}

}
