package com.stackcorp.astra.shared.time;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonFormat;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * API의 offset 없는 일시는 기관 시간대의 벽시계 문자열이다(ADR-0039). 요청 본문의 {@code LocalDateTime}에 {@code Z}가 붙어 있으면
 * 400으로 거부한다. Jackson은 기본으로 {@code Z}를 말없이 떼어 UTC 시각을 기관 시각으로 읽으므로, 브라우저의 {@code toISOString()}
 * 값이 9시간 어긋난 마감으로 저장된다. {@code +09:00} 같은 offset은 원래부터 읽지 못해 400이다.
 */
@Configuration(proxyBeanMethods = false)
class WallClockJsonConfiguration {

	@Bean
	JsonMapperBuilderCustomizer wallClockDateTimes() {
		return (builder) -> builder.withConfigOverride(LocalDateTime.class,
				(override) -> override.setFormat(JsonFormat.Value.forLeniency(false)));
	}

}
