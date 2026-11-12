package com.stackcorp.astra.integration.delivery;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.stackcorp.astra.files.api.DeliverySigner;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CloudFrontProperties.class)
class CloudFrontConfiguration {

	// key pair가 없으면(로컬 실행) 서명하지 않는다. 그때 CDN 경로는 열리지 않는다.
	@Bean
	@ConditionalOnProperty("astra.cloudfront.key-pair-id")
	DeliverySigner cloudFrontDeliverySigner(CloudFrontProperties properties) {
		return new CloudFrontDeliverySigner(properties);
	}

}
