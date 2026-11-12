package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.micrometer.observation.autoconfigure.ObservationAutoConfiguration;
import org.springframework.boot.micrometer.tracing.autoconfigure.MicrometerTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.OpenTelemetryTracingAutoConfiguration;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.otlp.OtlpTracingAutoConfiguration;
import org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.registry.otlp.OtlpMeterRegistry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;

/**
 * 배포하는 application.properties의 지표·trace 내보내기 설정(architecture.md 9절, ADR-0034). 통합 테스트는 Spring Boot가 내보내기를
 * 끈 채 띄우므로, 같은 auto-configuration을 이 설정 그대로 올려 내보내기 bean이 생기는지 본다. bean이 없으면 밖으로 연결하지 않는다.
 */
class ObservabilityExportTests {

	private static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
		.withConfiguration(AutoConfigurations.of(ObservationAutoConfiguration.class, MetricsAutoConfiguration.class,
				CompositeMeterRegistryAutoConfiguration.class, SimpleMetricsExportAutoConfiguration.class,
				OtlpMetricsExportAutoConfiguration.class, OpenTelemetrySdkAutoConfiguration.class,
				OpenTelemetryTracingAutoConfiguration.class, MicrometerTracingAutoConfiguration.class,
				OtlpTracingAutoConfiguration.class))
		.withUserConfiguration(ObservabilityConfiguration.class)
		.withInitializer((context) -> context.getEnvironment()
			.getPropertySources()
			.addLast(new PropertiesPropertySource("application.properties", shipped())));

	@Test
	void exportsNothingByDefaultEvenWithAnEndpointConfigured() {
		this.runner
			.withPropertyValues("management.otlp.metrics.export.url=http://127.0.0.1:9/v1/metrics",
					"management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(MeterRegistry.class);
				assertThat(context).doesNotHaveBean(OtlpMeterRegistry.class);
				assertThat(context).doesNotHaveBean(OtlpHttpSpanExporter.class);
				assertThat(extractsClientTrace(context.getBean(TextMapPropagator.class))).isFalse();
			});
	}

	@Test
	void exportsOnlyWhenEnabledAndNeverTrustsTheClientTraceHeader() {
		this.runner
			.withPropertyValues("ASTRA_OTLP_ENABLED=true",
					"management.otlp.metrics.export.url=http://127.0.0.1:9/v1/metrics",
					"management.opentelemetry.tracing.export.otlp.endpoint=http://127.0.0.1:9/v1/traces")
			.run((context) -> {
				assertThat(context).hasNotFailed();
				assertThat(context).hasSingleBean(OtlpMeterRegistry.class);
				assertThat(context).hasSingleBean(OtlpHttpSpanExporter.class);
				assertThat(extractsClientTrace(context.getBean(TextMapPropagator.class))).isFalse();
			});
	}

	@Test
	void refusesToStartWhenEnabledWithoutAnEndpoint() {
		this.runner.withPropertyValues("ASTRA_OTLP_ENABLED=true").run((context) -> {
			assertThat(context).hasFailed();
			assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("management.otlp.metrics.export.url");
		});
		this.runner
			.withPropertyValues("ASTRA_OTLP_ENABLED=true",
					"management.otlp.metrics.export.url=http://127.0.0.1:9/v1/metrics")
			.run((context) -> assertThat(context.getStartupFailure()).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("management.opentelemetry.tracing.export.otlp.endpoint"));
	}

	private static boolean extractsClientTrace(TextMapPropagator propagator) {
		Map<String, String> headers = Map.of("traceparent", TRACEPARENT, "b3", "4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-1",
				"baggage", "userId=42");
		Context extracted = propagator.extract(Context.root(), headers, new TextMapGetter<Map<String, String>>() {

			@Override
			public Iterable<String> keys(Map<String, String> carrier) {
				return carrier.keySet();
			}

			@Override
			public String get(Map<String, String> carrier, String key) {
				return carrier == null ? null : carrier.get(key);
			}

		});
		return Span.fromContext(extracted).getSpanContext().isValid() || extracted != Context.root();
	}

	private static Properties shipped() {
		try {
			return PropertiesLoaderUtils.loadProperties(new ClassPathResource("application.properties"));
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
