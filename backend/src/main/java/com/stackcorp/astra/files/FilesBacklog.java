package com.stackcorp.astra.files;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.shared.metrics.SampledGauge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * 저장소 GC, 업로드 검사, 작업 대기열의 밀린 수(architecture.md 9절, ADR-0034). worker가 정해진 간격으로 index를 타는 query로
 * {@link SampledGauge#CAP}까지만 센다. 지표를 내보낼 때는 DB를 읽지 않는다.
 * <ul>
 * <li>astra.files.gc.backlog{kind=expired_uploads}: 첨부하지 않은 채 만료됐지만 GC가 아직 닫지 않은 업로드(orphan upload)</li>
 * <li>astra.files.gc.backlog{kind=deleted_files}: 지웠지만 파생물을 아직 정리하지 않은 파일. 유예 시간 안의 파일도 센다</li>
 * <li>astra.files.gc.backlog{kind=unreferenced_blobs}: 참조가 0이고 아직 지우지 않은 원본. 유예 시간 안의 원본도 센다</li>
 * <li>astra.files.scan.backlog: 검사를 기다리는 내용(quarantined)</li>
 * <li>astra.files.rendition.jobs{state=requested|converting}, astra.files.rendition.jobs.oldest.age: 문서 변환 대기열(ADR-0033)</li>
 * <li>astra.files.video.jobs{state=queued|running}, astra.files.video.jobs.oldest.age: 영상 변환 대기열(ADR-0016)</li>
 * <li>astra.files.zip.jobs{state=requested|building}, astra.files.zip.jobs.oldest.age: 파일함 ZIP 대기열(ADR-0028)</li>
 * </ul>
 * 대기열의 oldest.age는 지금 실행할 수 있는 대기 작업 가운데 가장 오래 기다린 것의 초다. 다시 시도를 기다리는 작업은 시도 시각부터 센다.
 * 대기 작업이 없으면 0이다.
 */
@Component
@Profile("worker")
class FilesBacklog {

	private final JdbcClient jdbc;

	private final SampledGauge expiredUploads;

	private final SampledGauge deletedFiles;

	private final SampledGauge unreferencedBlobs;

	private final SampledGauge scanQueue;

	private final SampledGauge renditionsRequested;

	private final SampledGauge renditionsConverting;

	private final SampledGauge renditionsOldest;

	private final SampledGauge videosQueued;

	private final SampledGauge videosRunning;

	private final SampledGauge videosOldest;

	private final SampledGauge zipsRequested;

	private final SampledGauge zipsBuilding;

	private final SampledGauge zipsOldest;

	FilesBacklog(JdbcClient jdbc, MeterRegistry meters) {
		this.jdbc = jdbc;
		String gc = "저장소 GC가 처리할 밀린 수(상한 " + SampledGauge.CAP + ")";
		this.expiredUploads = SampledGauge.register(meters, "astra.files.gc.backlog", gc, "rows",
				Tags.of("kind", "expired_uploads"));
		this.deletedFiles = SampledGauge.register(meters, "astra.files.gc.backlog", gc, "rows",
				Tags.of("kind", "deleted_files"));
		this.unreferencedBlobs = SampledGauge.register(meters, "astra.files.gc.backlog", gc, "rows",
				Tags.of("kind", "unreferenced_blobs"));
		this.scanQueue = SampledGauge.register(meters, "astra.files.scan.backlog",
				"검사를 기다리는 내용 수(상한 " + SampledGauge.CAP + ")", "rows", Tags.empty());
		this.renditionsRequested = jobs(meters, "astra.files.rendition.jobs", "문서 변환", "requested");
		this.renditionsConverting = jobs(meters, "astra.files.rendition.jobs", "문서 변환", "converting");
		this.renditionsOldest = oldest(meters, "astra.files.rendition.jobs.oldest.age", "문서 변환");
		this.videosQueued = jobs(meters, "astra.files.video.jobs", "영상 변환", "queued");
		this.videosRunning = jobs(meters, "astra.files.video.jobs", "영상 변환", "running");
		this.videosOldest = oldest(meters, "astra.files.video.jobs.oldest.age", "영상 변환");
		this.zipsRequested = jobs(meters, "astra.files.zip.jobs", "파일함 ZIP", "requested");
		this.zipsBuilding = jobs(meters, "astra.files.zip.jobs", "파일함 ZIP", "building");
		this.zipsOldest = oldest(meters, "astra.files.zip.jobs.oldest.age", "파일함 ZIP");
	}

	@Scheduled(fixedDelayString = "${astra.metrics.backlog-interval:1m}",
			initialDelayString = "${astra.metrics.backlog-interval:1m}")
	void sample() {
		// 각 query는 upload_sessions(upload_state, expires_at), files(state, derivatives_purged_at),
		// file_blobs(reference_count, purged_at), file_blobs(blob_state, scan_next_at) index를 탄다.
		this.expiredUploads.set(count("""
				SELECT 1 FROM upload_sessions
				 WHERE upload_state IN ('quarantined', 'clean') AND expires_at <= CURRENT_TIMESTAMP(3)
				"""));
		this.deletedFiles.set(count("SELECT 1 FROM files WHERE state = 'deleted' AND derivatives_purged_at IS NULL"));
		this.unreferencedBlobs
			.set(count("SELECT 1 FROM file_blobs WHERE reference_count = 0 AND purged_at IS NULL"));
		this.scanQueue.set(count("SELECT 1 FROM file_blobs WHERE blob_state = 'quarantined' AND purged_at IS NULL"));
		sampleQueues();
	}

	// 대기열 query는 conversion_jobs(conversion_state, next_attempt_at), video_transcode_jobs(job_state, next_attempt_at),
	// zip_jobs(zip_state, expires_at) index를 탄다. 영상은 worker가 잡는 순서(next_attempt_at)대로 첫 행만 읽고, 변환과 ZIP은
	// 대기 범위를 상한까지만 읽어 가장 오래된 값을 고른다.
	private void sampleQueues() {
		this.renditionsRequested.set(count("SELECT 1 FROM conversion_jobs WHERE conversion_state = 'requested'"));
		this.renditionsConverting.set(count("SELECT 1 FROM conversion_jobs WHERE conversion_state = 'converting'"));
		this.renditionsOldest.set(age("""
				SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND, COALESCE(next_attempt_at, created_at), CURRENT_TIMESTAMP(3))), 0)
				  FROM (SELECT next_attempt_at, created_at FROM conversion_jobs
				         WHERE conversion_state = 'requested'
				           AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(3))
				         LIMIT %d) due
				""".formatted(SampledGauge.CAP)));
		this.videosQueued.set(count("SELECT 1 FROM video_transcode_jobs WHERE job_state = 'queued'"));
		this.videosRunning.set(count("SELECT 1 FROM video_transcode_jobs WHERE job_state = 'running'"));
		this.videosOldest.set(age("""
				SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND, next_attempt_at, CURRENT_TIMESTAMP(3))), 0)
				  FROM (SELECT next_attempt_at FROM video_transcode_jobs
				         WHERE job_state = 'queued' AND next_attempt_at <= CURRENT_TIMESTAMP(3)
				         ORDER BY next_attempt_at LIMIT 1) due
				"""));
		this.zipsRequested.set(count("SELECT 1 FROM zip_jobs WHERE zip_state = 'requested'"));
		this.zipsBuilding.set(count("SELECT 1 FROM zip_jobs WHERE zip_state = 'building'"));
		this.zipsOldest.set(age("""
				SELECT COALESCE(MAX(TIMESTAMPDIFF(SECOND, created_at, CURRENT_TIMESTAMP(3))), 0)
				  FROM (SELECT created_at FROM zip_jobs WHERE zip_state = 'requested' LIMIT %d) due
				""".formatted(SampledGauge.CAP)));
	}

	private long age(String sql) {
		return this.jdbc.sql(sql).query(Long.class).single();
	}

	private static SampledGauge jobs(MeterRegistry meters, String name, String queue, String state) {
		return SampledGauge.register(meters, name, "상태별 " + queue + " 작업 수(상한 " + SampledGauge.CAP + ")", "jobs",
				Tags.of("state", state));
	}

	private static SampledGauge oldest(MeterRegistry meters, String name, String queue) {
		return SampledGauge.register(meters, name, "실행할 수 있는 " + queue + " 대기 작업이 가장 오래 기다린 시간", "seconds",
				Tags.empty());
	}

	private long count(String rows) {
		return this.jdbc.sql("SELECT COUNT(*) FROM (" + rows + " LIMIT " + SampledGauge.CAP + ") bounded")
			.query(Long.class)
			.single();
	}

}
