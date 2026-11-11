package com.stackcorp.astra.integration.oidc;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import com.stackcorp.astra.identity.api.ExternalIdentityProvider;

/**
 * issuer가 없으면 아무것도 만들지 않는다. 그때 로그인 endpoint는 404이고 IdP를 부르지 않는다(ADR-0017).
 */
@Configuration(proxyBeanMethods = false)
@Conditional(OidcConfiguration.IssuerConfigured.class)
@EnableConfigurationProperties(OidcProperties.class)
class OidcConfiguration {

	@Bean
	ExternalIdentityProvider oidcIdentityProvider(OidcProperties properties) {
		return new OidcIdentityProvider(properties, Clock.systemUTC());
	}

	static class IssuerConfigured implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return StringUtils.hasText(context.getEnvironment().getProperty("astra.oidc.issuer"));
		}

	}

}
