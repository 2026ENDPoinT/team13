package com.stackcorp.astra.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.RenditionException;
import com.stackcorp.astra.files.api.TranscodeException;
import com.stackcorp.astra.files.api.VideoAssets;
import com.stackcorp.astra.files.api.VideoTranscoder;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;

/**
 * 저장소 GC, orphan upload backlog, 문서 변환과 영상 변환 대기열의 지표(architecture.md 9절, ADR-0034). tag에는 코드가 정한 값만
 * 있고 파일, 작업 ID는 없다.
 */
class FilesMetricsTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	MeterRegistry meters;

	@Autowired
	StorageGc gc;

	@Autowired
	FilesBacklog backlog;

	@Autowired
	DocumentRenditions renditions;

	@Autowired
	DocumentRenditionService conversionQueue;

	@Autowired
	FileRecords fileRecords;

	@Autowired
	VideoAssets videoAssets;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	ApplicationContext context;

	@Test
	void reportsOrphanUploadsUntilTheGcExpiresThemAndCountsWhatItProcessed() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		long upload = new ApiClient(this.mvc, this.data.session(prof)).uploaded(course, "orphan.txt",
				("orphan " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8));
		this.backlog.sample();
		double before = gauge("expired_uploads");
		double processed = processed("expired_uploads");

		this.jdbc.sql("UPDATE upload_sessions SET expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE) WHERE upload_id = :id")
			.param("id", upload)
			.update();
		this.backlog.sample();
		assertThat(gauge("expired_uploads")).isEqualTo(before + 1);

		this.gc.run();

		assertThat(processed("expired_uploads")).isGreaterThanOrEqualTo(processed + 1);
		assertThat(this.jdbc.sql("SELECT upload_state FROM upload_sessions WHERE upload_id = :id")
			.param("id", upload)
			.query(String.class)
			.single()).isEqualTo("expired");
		this.backlog.sample();
		assertThat(gauge("expired_uploads")).isLessThanOrEqualTo(before);
		assertThat(gauge("deleted_files")).isGreaterThanOrEqualTo(0);
		assertThat(gauge("unreferenced_blobs")).isGreaterThanOrEqualTo(0);
	}

	@Test
	void timesConversionsAndCountsFailuresByCode() {
		long good = this.data.pdfFile(TestPdfs.pdf(1), "clean");
		long broken = this.data.pdfFile("not a pdf".getBytes(StandardCharsets.US_ASCII), "clean");
		double ok = conversions("ok");
		double failed = conversions("failed");

		this.renditions.prepare(good);
		// 이미 만든 rendition을 다시 읽는 것은 변환이 아니다.
		this.renditions.prepare(good);
		RenditionException ex = catchThrowableOfType(RenditionException.class, () -> this.renditions.prepare(broken));

		assertThat(conversions("ok")).isEqualTo(ok + 1);
		assertThat(conversions("failed")).isEqualTo(failed + 1);
		assertThat(this.meters.get("astra.files.rendition.conversions")
			.tag("outcome", "failed")
			.tag("code", ex.detail().toLowerCase())
			.timer()
			.count()).isPositive();
	}

	@Test
	void reportsTheConversionQueueAndCountsConversionsInterruptedByAStoppedWorker() {
		while (this.conversionQueue.convertNext()) {
			// 앞 테스트가 남긴 변환을 비운다.
		}
		this.backlog.sample();
		double requested = queue("astra.files.rendition.jobs", "requested");
		double retried = interrupted("astra.files.rendition.interrupted", "retry");
		double ok = conversions("ok");
		long file = this.data.pdfFile(TestPdfs.pdf(1), "clean");

		this.conversionQueue.request(this.fileRecords.find(file).orElseThrow(), null);
		this.backlog.sample();
		assertThat(queue("astra.files.rendition.jobs", "requested")).isEqualTo(requested + 1);
		assertThat(this.meters.get("astra.files.rendition.jobs.oldest.age").gauge().value()).isGreaterThanOrEqualTo(0);

		// 변환하던 worker가 멈춰 lease가 끝났다.
		this.jdbc.sql("""
				UPDATE conversion_jobs
				   SET conversion_state = 'converting', attempt_count = 1, lease_owner = 'stopped-worker',
				       lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
				 WHERE file_id = :file AND conversion_state = 'requested'
				""").param("file", file).update();
		this.backlog.sample();
		assertThat(queue("astra.files.rendition.jobs", "requested")).isEqualTo(requested);
		assertThat(queue("astra.files.rendition.jobs", "converting")).isPositive();

		this.conversionQueue.recoverExpired();
		assertThat(interrupted("astra.files.rendition.interrupted", "retry")).isGreaterThanOrEqualTo(retried + 1);
		while (this.conversionQueue.convertNext()) {
			// 되돌린 변환을 실행한다.
		}

		assertThat(conversions("ok")).isGreaterThanOrEqualTo(ok + 1);
		this.backlog.sample();
		assertThat(queue("astra.files.rendition.jobs", "requested")).isEqualTo(requested);
		assertCodeTagsOnly(List.of("astra.files.rendition.jobs", "astra.files.rendition.interrupted",
				"astra.files.rendition.conversions"));
	}

	@Test
	void reportsTheVideoQueueAndTimesTranscodesByOutcomeAndCode() {
		VideoRunnerDriver.drain(this.context);
		this.backlog.sample();
		double queued = queue("astra.files.video.jobs", "queued");
		double succeeded = transcodes("succeeded", "none");
		double invalid = transcodes("failed", "invalid_media");
		double retry = transcodes("retry", "unavailable");
		VideoTranscoder ok = (source, sink, cancelled) -> {
			sink.accept(0, 2.0, new byte[188]);
			return new VideoTranscoder.Transcoded(1, 2.0);
		};
		VideoTranscoder broken = (source, sink, cancelled) -> {
			throw new TranscodeException(TranscodeException.Code.INVALID_MEDIA, "영상이 아닙니다.");
		};
		VideoTranscoder down = (source, sink, cancelled) -> {
			throw new TranscodeException(TranscodeException.Code.UNAVAILABLE, "transcoder에 연결하지 못했습니다.");
		};

		video();
		this.backlog.sample();
		assertThat(queue("astra.files.video.jobs", "queued")).isEqualTo(queued + 1);
		assertThat(this.meters.get("astra.files.video.jobs.oldest.age").gauge().value()).isGreaterThanOrEqualTo(0);
		assertThat(VideoRunnerDriver.runOnce(this.context, ok)).isTrue();
		assertThat(transcodes("succeeded", "none")).isEqualTo(succeeded + 1);

		video();
		assertThat(VideoRunnerDriver.runOnce(this.context, broken)).isTrue();
		assertThat(transcodes("failed", "invalid_media")).isEqualTo(invalid + 1);

		long retried = video();
		try {
			assertThat(VideoRunnerDriver.runOnce(this.context, down)).isTrue();
			assertThat(transcodes("retry", "unavailable")).isEqualTo(retry + 1);
			this.backlog.sample();
			// 다시 시도를 기다리는 작업은 대기 수에 들지만 아직 실행할 수 없어 나이에는 들지 않는다.
			assertThat(queue("astra.files.video.jobs", "queued")).isEqualTo(queued + 1);
			assertThat(this.meters.get("astra.files.video.jobs.oldest.age").gauge().value()).isZero();

			// 실행하던 worker가 멈춰 lease가 끝난 작업은 recoverExpired가 대기로 되돌리며 센다.
			double interrupted = interrupted("astra.files.video.transcodes.interrupted", "retry");
			this.jdbc.sql("""
					UPDATE video_transcode_jobs
					   SET job_state = 'running', lease_owner = 'stopped-worker',
					       lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
					 WHERE video_asset_id = :asset
					""").param("asset", retried).update();
			VideoRunnerDriver.recoverExpired(this.context);
			assertThat(interrupted("astra.files.video.transcodes.interrupted", "retry"))
				.isGreaterThanOrEqualTo(interrupted + 1);
			assertCodeTagsOnly(List.of("astra.files.video.jobs", "astra.files.video.transcodes",
					"astra.files.video.transcodes.interrupted"));
		}
		finally {
			this.jdbc.sql("""
					UPDATE video_transcode_jobs SET job_state = 'cancelled', lease_owner = NULL, lease_expires_at = NULL
					 WHERE video_asset_id = :asset AND job_state IN ('queued', 'running')
					""").param("asset", retried).update();
		}
	}

	// 변환할 수 있는 영상 파일을 asset으로 등록한다. @return asset ID
	private long video() {
		long fileId = this.data.file("lecture.mp4", "video/mp4", new byte[] { 1, 2, 3 }, "clean");
		return this.transaction
			.execute((tx) -> this.videoAssets.register(fileId, "/api/courses/1/lecture-items/" + fileId + "/hls-key"));
	}

	// tag 값에 숫자가 없으면 ID가 들어갈 수 없다.
	private void assertCodeTagsOnly(List<String> names) {
		for (String name : names) {
			for (Meter meter : this.meters.find(name).meters()) {
				assertThat(meter.getId().getTags()).extracting(Tag::getValue)
					.allSatisfy((value) -> assertThat(value).matches("[a-z][a-z_.-]*"));
			}
		}
	}

	private double queue(String name, String state) {
		return this.meters.get(name).tag("state", state).gauge().value();
	}

	private double interrupted(String name, String outcome) {
		Counter counter = this.meters.find(name).tag("outcome", outcome).counter();
		return counter == null ? 0 : counter.count();
	}

	private double transcodes(String outcome, String code) {
		Timer timer = this.meters.find("astra.files.video.transcodes").tag("outcome", outcome).tag("code", code).timer();
		return timer == null ? 0 : timer.count();
	}

	private double gauge(String kind) {
		return this.meters.get("astra.files.gc.backlog").tag("kind", kind).gauge().value();
	}

	private double processed(String step) {
		Counter counter = this.meters.find("astra.files.gc.processed").tag("step", step).counter();
		return counter == null ? 0 : counter.count();
	}

	private double conversions(String outcome) {
		return this.meters.find("astra.files.rendition.conversions")
			.tag("outcome", outcome)
			.timers()
			.stream()
			.mapToDouble(Timer::count)
			.sum();
	}

}
