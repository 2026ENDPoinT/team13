package com.stackcorp.astra.integration.converter;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * @param url converter process 주소. 같은 host의 내부 network에 있다
 * @param timeout 문서 하나의 변환 제한 시간
 * @param maxResponseBytes 응답 zip 전체의 크기 상한
 * @param maxEntryBytes zip 안 파일 하나의 크기 상한
 */
@ConfigurationProperties("astra.converter")
record ConverterProperties(@DefaultValue("http://converter:8090") URI url, @DefaultValue("10m") Duration timeout,
		@DefaultValue("1GB") DataSize maxResponseBytes, @DefaultValue("64MB") DataSize maxEntryBytes) {
}
