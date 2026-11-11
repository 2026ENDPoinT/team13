package com.stackcorp.astra.integration.transcoder;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * transcoder process(com.stackcorp.transcoder.TranscoderServer) 호출 설정. worker 설정은 astra.transcoder.worker.*에 있다.
 *
 * @param url transcoder 주소. 같은 host의 내부 network에 있다. 환경 변수 ASTRA_TRANSCODER_URL
 * @param timeout 영상 하나의 변환과 응답 전체의 제한 시간
 * @param maxPlaylistBytes 응답의 재생 목록 크기 상한
 * @param maxSegmentBytes segment 하나의 크기 상한
 * @param maxSegments segment 수 상한
 */
@ConfigurationProperties("astra.transcoder")
record TranscoderProperties(@DefaultValue("http://transcoder:8091") URI url, @DefaultValue("3h") Duration timeout,
		@DefaultValue("1MB") DataSize maxPlaylistBytes, @DefaultValue("64MB") DataSize maxSegmentBytes,
		@DefaultValue("5000") int maxSegments) {
}
