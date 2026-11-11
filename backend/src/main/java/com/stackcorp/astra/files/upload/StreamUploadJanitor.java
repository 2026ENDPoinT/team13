package com.stackcorp.astra.files.upload;

import java.time.Duration;

import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.files.storage.ObjectStreams;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * api process가 스트림 업로드 도중 멈추면 multipart upload의 part가 남는다. worker가 오래된 것을 취소한다(ADR-0016).
 * 정상 경로의 실패는 {@link StreamUploadService}가 그 자리에서 취소한다. 여러 worker가 함께 실행해도 결과가 같다.
 */
@Component
@Profile("worker")
class StreamUploadJanitor {

	private static final Logger log = LoggerFactory.getLogger(StreamUploadJanitor.class);

	private final ObjectStreams streams;

	private final StreamUploadProperties properties;

	StreamUploadJanitor(ObjectStreams streams, StreamUploadProperties properties) {
		this.streams = streams;
		this.properties = properties;
	}

	@Scheduled(fixedDelayString = "${astra.upload.stream.cleanup-interval:1h}",
			initialDelayString = "${astra.upload.stream.cleanup-interval:1h}")
	void abortAbandoned() {
		abortOlderThan(this.properties.abandonAfter());
	}

	int abortOlderThan(Duration age) {
		int aborted = this.streams.abortIncomplete(Bucket.CONTENT, StreamUploadService.KEY_PREFIX, age);
		if (aborted > 0) {
			log.warn("aborted abandoned stream uploads count={}", aborted);
		}
		return aborted;
	}

}
