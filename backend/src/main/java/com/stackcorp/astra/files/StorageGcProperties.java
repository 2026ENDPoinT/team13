package com.stackcorp.astra.files;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * worker의 저장소 GC 설정.
 *
 * @param interval 한 번 돌고 다음에 돌 때까지 쉬는 시간
 * @param batchSize 한 번에 처리하는 행 수의 상한(만료 업로드, 지운 파일, 참조 없는 blob 각각)
 * @param derivativeGrace 파일을 지운 뒤 파생물을 지우기까지 기다리는 시간. 그 전에 시작한 쓰기가 끝나기를 기다린다
 * @param blobGrace 참조가 0이 된 원본 객체를 지우기까지 기다리는 시간
 */
@ConfigurationProperties("astra.files.gc")
record StorageGcProperties(@DefaultValue("1m") Duration interval, @DefaultValue("100") int batchSize,
		@DefaultValue("10m") Duration derivativeGrace, @DefaultValue("1h") Duration blobGrace) {

	StorageGcProperties {
		if (batchSize < 1) {
			throw new IllegalArgumentException("astra.files.gc.batch-size는 1 이상이어야 합니다.");
		}
	}

}
