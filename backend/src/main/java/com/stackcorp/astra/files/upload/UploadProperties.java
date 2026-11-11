package com.stackcorp.astra.files.upload;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param ttl 첨부하지 않은 업로드를 쓸 수 있는 시간
 * @param rateLimit 한 사용자가 rateWindow 안에 올릴 수 있는 파일 수
 */
@ConfigurationProperties("astra.upload")
record UploadProperties(@DefaultValue("24h") Duration ttl, @DefaultValue("30") int rateLimit,
		@DefaultValue("10m") Duration rateWindow) {
}
