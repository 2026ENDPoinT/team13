package com.stackcorp.astra.integration.scan;

import java.time.Duration;
import java.util.regex.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.StringUtils;
import org.springframework.util.unit.DataSize;

/**
 * 같은 배포 안의 ClamAV daemon(clamd) 설정(ADR-0018). host가 비어 있으면 검사기를 만들지 않는다.
 *
 * @param host clamd host 이름이나 IP. 연결하는 곳은 이 host와 port 하나다
 * @param port clamd TCP port
 * @param connectTimeout 연결 제한 시간
 * @param timeout 파일 하나를 보내고 결과를 받기까지의 전체 제한 시간
 * @param chunkSize INSTREAM chunk 하나의 크기
 * @param maxStreamBytes 보내는 내용의 상한. clamd의 StreamMaxLength와 같게 둔다. 이보다 큰 파일은 보내지 않고 검사할 수 없음으로 본다
 */
@ConfigurationProperties("astra.scan.clamd")
record ClamdProperties(String host, @DefaultValue("3310") int port, @DefaultValue("2s") Duration connectTimeout,
		@DefaultValue("120s") Duration timeout, @DefaultValue("64KB") DataSize chunkSize,
		@DefaultValue("64MB") DataSize maxStreamBytes) {

	// URL, 경로, 계정 정보가 섞인 값을 받지 않는다. host 이름, IPv4, IPv6만 받는다.
	private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}|[0-9A-Fa-f:]{2,39}");

	ClamdProperties {
		if (StringUtils.hasText(host) && !HOST.matcher(host).matches()) {
			throw new IllegalArgumentException("astra.scan.clamd.host는 host 이름이나 IP 주소만 받습니다.");
		}
		if (port < 1 || port > 65535) {
			throw new IllegalArgumentException("astra.scan.clamd.port가 올바르지 않습니다.");
		}
		if (connectTimeout.isNegative() || connectTimeout.isZero() || timeout.isNegative() || timeout.isZero()) {
			throw new IllegalArgumentException("astra.scan.clamd의 제한 시간은 0보다 커야 합니다.");
		}
		if (chunkSize.toBytes() < 1 || chunkSize.toBytes() > DataSize.ofMegabytes(8).toBytes()) {
			throw new IllegalArgumentException("astra.scan.clamd.chunk-size는 1 byte 이상 8 MB 이하입니다.");
		}
		if (maxStreamBytes.toBytes() < 1) {
			throw new IllegalArgumentException("astra.scan.clamd.max-stream-bytes는 1 byte 이상입니다.");
		}
	}

}
