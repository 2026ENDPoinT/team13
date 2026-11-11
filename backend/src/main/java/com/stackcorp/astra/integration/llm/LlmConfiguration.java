package com.stackcorp.astra.integration.llm;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.util.StringUtils;

import com.stackcorp.astra.summary.api.LlmClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LlmProperties.class)
class LlmConfiguration {

	// key가 없으면 bean을 만들지 않는다. 요약 생성만 쓸 수 없고 앱은 뜬다.
	@Bean
	@Conditional(ApiKeyConfigured.class)
	LlmClient llmClient(LlmProperties properties) {
		return new OpenAiCompatibleLlmClient(properties);
	}

	static class ApiKeyConfigured implements Condition {

		@Override
		public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
			return StringUtils.hasText(context.getEnvironment().getProperty("astra.llm.api-key"));
		}

	}

}
