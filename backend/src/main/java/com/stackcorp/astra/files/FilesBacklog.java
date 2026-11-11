package com.stackcorp.astra.files;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.shared.metrics.SampledGauge;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;

/**
 * 저장소 GC와 업로드 검사의 밀린 수(architecture.md 9절, ADR-0034). worker가 정해진 간격으로 index를 타는 query로
 * {@link SampledGauge#CAP}까지만 센다. 지표를 내보낼 때는 DB를 읽지 않는다.
 * <ul>
 * <li>astra.files.gc.backlog{kind=expired_uploads}: 첨부하지 않은 채 만료됐지만 GC가 아직 닫지 않은 업로드(orphan upload)</li>
 * <li>astra.files.gc.backlog{kind=deleted_files}: 지웠지만 파생물을 아직 정리하지 않은 파일. 유예 시간 안의 파일도 센다</li>
 * <li>astra.files.gc.backlog{kind=unreferenced_blobs}: 참조가 0이고 아직 지우지 않은 원본. 유예 시간 안의 원본도 센다</li>
 * <li>astra.files.scan.backlog: 검사를 기다리는 내용(quarantined)</li>
 * </ul>
 */
@Component
@Profile("worker")
class FilesBacklog {

	private final JdbcClient jdbc;

	private final SampledGauge expiredUploads;

	private final SampledGauge deletedFiles;

	private final SampledGauge unreferencedBlobs;

	private final SampledGauge scanQueue;

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
	}

	private long count(String rows) {
		return this.jdbc.sql("SELECT COUNT(*) FROM (" + rows + " LIMIT " + SampledGauge.CAP + ") bounded")
			.query(Long.class)
			.single();
	}

}
