package com.stackcorp.astra.shared;

import java.util.Arrays;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.server.observation.ServerRequestObservationConvention;
import org.springframework.util.StringUtils;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * 지표와 trace의 공통 설정(architecture.md 9절, ADR-0034).
 * <ul>
 * <li>모든 지표에 process 역할(api | worker) tag를 붙인다. 같은 이미지의 두 역할이 같은 이름의 지표를 낸다.</li>
 * <li>HTTP 요청 지표와 span의 값은 {@link HttpObservationConvention}이 정한다.</li>
 * <li>OTLP 내보내기는 기본으로 꺼져 있다(application.properties). 켰는데 받을 주소가 없으면 시작하지 않는다. 주소가 없을 때
 * 내보내기 library가 기본 주소(localhost)로 보내려 하지 않게 하기 위해서다.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
class ObservabilityConfiguration {

	@Bean
	ServerRequestObservationConvention httpObservationConvention() {
		return new HttpObservationConvention();
	}

	@Bean
	MeterRegistryCustomizer<MeterRegistry> roleTag(Environment environment) {
		String role = Arrays.asList(environment.getActiveProfiles()).contains("worker") ? "worker" : "api";
		return (registry) -> registry.config().commonTags("role", role);
	}

	// bean을 만들기 전에 확인한다. 내보내기 registry가 먼저 만들어지면 시작에 실패해 닫을 때 기본 주소로 한 번 보내려 한다.
	@Bean
	static OtlpExportCheck otlpExportCheck() {
		return new OtlpExportCheck();
	}

	/** OTLP 지표·trace 내보내기를 켰으면 받을 주소도 있어야 한다. */
	static final class OtlpExportCheck implements BeanFactoryPostProcessor, EnvironmentAware {

		private Environment environment;

		@Override
		public void setEnvironment(Environment environment) {
			this.environment = environment;
		}

		@Override
		public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
			boolean metrics = enabled("management.otlp.metrics.export.enabled",
					"management.defaults.metrics.export.enabled");
			if (metrics && !configured("management.otlp.metrics.export.url")) {
				throw new IllegalStateException(
						"OTLP 지표 내보내기를 켰지만 management.otlp.metrics.export.url(OTEL_EXPORTER_OTLP_ENDPOINT)이 없습니다.");
			}
			boolean tracing = enabled("management.tracing.export.otlp.enabled", "management.tracing.export.enabled");
			if (tracing && !configured("management.opentelemetry.tracing.export.otlp.endpoint")) {
				throw new IllegalStateException("OTLP trace 내보내기를 켰지만 "
						+ "management.opentelemetry.tracing.export.otlp.endpoint(OTEL_EXPORTER_OTLP_ENDPOINT)가 없습니다.");
			}
		}

		// Spring Boot와 같은 순서로 판정한다. 내보내기별 설정, 전체 설정, 없으면 켜짐.
		private boolean enabled(String specific, String global) {
			Boolean value = this.environment.getProperty(specific, Boolean.class);
			if (value == null) {
				value = this.environment.getProperty(global, Boolean.class, Boolean.TRUE);
			}
			return value;
		}

		private boolean configured(String property) {
			return StringUtils.hasText(this.environment.getProperty(property));
		}

	}

}
