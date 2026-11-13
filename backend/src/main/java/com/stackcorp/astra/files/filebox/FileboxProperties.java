package com.stackcorp.astra.files.filebox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 개인 파일함 설정(ADR-0028).
 *
 * @param quota 사용자 한 사람의 기본 용량. filebox_quotas.quota_bytes가 있으면 그 값을 쓴다
 * @param maxItems 사용자 한 사람의 항목 수 상한(폴더 포함, 휴지통 포함)
 * @param maxDepth 폴더 깊이 상한. 루트 바로 아래 폴더가 1이다
 * @param rateLimit 한 사용자가 rateWindow 안에 보낼 수 있는 파일함 변경 명령 수(폴더 만들기, 이름 바꾸기, 이동, 휴지통, 복원, 영구 삭제)
 */
@ConfigurationProperties("astra.filebox")
record FileboxProperties(@DefaultValue("1GB") DataSize quota, @DefaultValue("5000") int maxItems,
		@DefaultValue("10") int maxDepth, @DefaultValue("120") int rateLimit, @DefaultValue("10m") Duration rateWindow,
		@DefaultValue Zip zip) {

	FileboxProperties {
		if (maxItems < 1 || maxDepth < 1 || rateLimit < 1) {
			throw new IllegalArgumentException("astra.filebox.max-items, max-depth, rate-limit는 1 이상이어야 합니다.");
		}
	}

	/**
	 * @param maxItems ZIP 하나에 넣을 수 있는 파일 수
	 * @param maxTotalSize ZIP 하나에 넣을 파일 크기의 합 상한
	 * @param maxConcurrent 한 사용자가 동시에 기다리거나 만들고 있을 수 있는 작업 수
	 * @param ttl 생성물을 내려받을 수 있는 시간. 지나면 worker가 지운다
	 * @param lease worker 하나가 작업을 잡고 있는 시간. 파일 하나를 넣을 때마다 늘린다
	 * @param maxAttempts 생성이 실패했을 때 다시 시도하는 횟수의 상한
	 * @param pollInterval worker가 대기 작업과 만료 생성물을 찾는 간격
	 * @param rateLimit 한 사용자가 rateWindow 안에 요청할 수 있는 작업 수
	 * @param workerId lease 소유자 이름. 비우면 host와 pid
	 */
	record Zip(@DefaultValue("100") int maxItems, @DefaultValue("500MB") DataSize maxTotalSize,
			@DefaultValue("2") int maxConcurrent, @DefaultValue("2h") Duration ttl, @DefaultValue("10m") Duration lease,
			@DefaultValue("3") int maxAttempts, @DefaultValue("10s") Duration pollInterval,
			@DefaultValue("10") int rateLimit, @DefaultValue("10m") Duration rateWindow, String workerId) {

		Zip {
			if (maxItems < 1 || maxConcurrent < 1 || maxAttempts < 1 || rateLimit < 1) {
				throw new IllegalArgumentException(
						"astra.filebox.zip.max-items, max-concurrent, max-attempts, rate-limit는 1 이상이어야 합니다.");
			}
		}

	}

}
