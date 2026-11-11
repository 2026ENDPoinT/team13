package com.stackcorp.astra.integration.scan;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClamdProperties.class)
class ClamdConfiguration {

	// host가 없으면 검사기를 만들지 않는다. files 모듈이 시작할 때 경고하거나 astra.scan.required에 따라 시작을 막는다.
	@Bean(destroyMethod = "close")
	@Conditional(HostConfigured.class)
	ClamdScanner contentScanner(ClamdProperties properties) {
		return new ClamdScanner(properties);
	}

	static class HostConfigured implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return StringUtils.hasText(context.getEnvironment().getProperty("astra.scan.clamd.host"));
		}

	}

}
