package com.stackcorp.astra.files.upload;

import java.time.Duration;

import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.files.storage.ObjectStreams;
import com.stackcorp.astra.shared.schedule.SchedulerLock;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * api process가 스트림 업로드 도중 멈추면 multipart upload의 part가 남는다. worker가 오래된 것을 취소한다(ADR-0016).
 * 정상 경로의 실패는 {@link StreamUploadService}가 그 자리에서 취소한다.
 * <p>
 * 주기 실행은 {@link SchedulerLock}으로 묶는다(ADR-0034). 끝나지 않은 upload 목록을 잠금 없이 읽으므로, 두 worker가 함께 돌면 같은
 * 목록을 두 번 읽고 한쪽의 취소 요청은 이미 없는 upload에 가서 실패한다. 취소한 수를 astra.files.upload.stream.aborted로 센다.
 */
@Component
@Profile("worker")
class StreamUploadJanitor {

	private static final Logger log = LoggerFactory.getLogger(StreamUploadJanitor.class);

	private static final String LOCK = "files.stream_upload_janitor";

	private static final Duration LOCK_AT_MOST = Duration.ofMinutes(10);

	private final ObjectStreams streams;

	private final StreamUploadProperties properties;

	private final SchedulerLock lock;

	private final Counter aborted;

	StreamUploadJanitor(ObjectStreams streams, StreamUploadProperties properties, SchedulerLock lock,
			MeterRegistry meters) {
		this.streams = streams;
		this.properties = properties;
		this.lock = lock;
		this.aborted = Counter.builder("astra.files.upload.stream.aborted")
			.description("worker가 취소한 끝나지 않은 multipart upload")
			.baseUnit("uploads")
			.register(meters);
	}

	@Scheduled(fixedDelayString = "${astra.upload.stream.cleanup-interval:1h}",
			initialDelayString = "${astra.upload.stream.cleanup-interval:1h}")
	void abortAbandoned() {
		this.lock.runExclusively(LOCK, LOCK_AT_MOST, () -> abortOlderThan(this.properties.abandonAfter()));
	}

	int abortOlderThan(Duration age) {
		int count = this.streams.abortIncomplete(Bucket.CONTENT, StreamUploadService.KEY_PREFIX, age);
		this.aborted.increment(count);
		if (count > 0) {
			log.warn("aborted abandoned stream uploads count={}", count);
		}
		return count;
	}

}
