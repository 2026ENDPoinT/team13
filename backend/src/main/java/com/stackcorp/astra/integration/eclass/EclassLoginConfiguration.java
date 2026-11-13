package com.stackcorp.astra.integration.eclass;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import com.stackcorp.astra.identity.api.SchoolPasswordVerifier;

/**
 * {@code astra.eclass-login.base-url}이 없으면 아무것도 만들지 않는다. 그때 학번·비밀번호 로그인은 404이고 학교 서버를 부르지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@Conditional(EclassLoginConfiguration.BaseUrlConfigured.class)
@EnableConfigurationProperties(EclassLoginProperties.class)
class EclassLoginConfiguration {

	@Bean
	SchoolPasswordVerifier eclassPasswordVerifier(EclassLoginProperties properties) {
		return new EclassPasswordVerifier(properties);
	}

	static class BaseUrlConfigured implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return StringUtils.hasText(context.getEnvironment().getProperty("astra.eclass-login.base-url"));
		}

	}

}
