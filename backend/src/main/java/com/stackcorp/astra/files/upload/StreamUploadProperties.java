package com.stackcorp.astra.files.upload;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * 본문 스트림 업로드(ADR-0016). 파일 하나의 크기 상한은 upload_policies.max_file_bytes가 정한다.
 * worker가 끝나지 않은 multipart upload를 찾는 간격은 astra.upload.stream.cleanup-interval(기본 1h)이다.
 *
 * @param partSize S3 multipart part 크기. 업로드 하나가 이만큼 메모리를 쓴다. 5MB 이상
 * @param abandonAfter 이 시간이 지나도 끝나지 않은 multipart upload는 worker가 취소한다
 */
@ConfigurationProperties("astra.upload.stream")
record StreamUploadProperties(@DefaultValue("8MB") DataSize partSize, @DefaultValue("24h") Duration abandonAfter) {
}
