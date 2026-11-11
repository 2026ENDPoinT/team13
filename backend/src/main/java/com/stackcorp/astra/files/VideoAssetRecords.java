package com.stackcorp.astra.files;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * video_assets, video_transcode_jobs. 상태 전이는 모두 현재 상태를 조건으로 거는 UPDATE다. 실행 중 작업의 갱신은 lease 소유자일
 * 때만 반영된다. 끝난 작업(succeeded, failed, cancelled)은 바뀌지 않는다.
 */
@Repository
class VideoAssetRecords {

	private final JdbcClient jdbc;

	VideoAssetRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @throws org.springframework.dao.DuplicateKeyException 그 파일에 이미 asset이 있을 때 */
	long insertAsset(long fileId, String assetKey, String keyUri) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("INSERT INTO video_assets (file_id, asset_key, key_uri) VALUES (:file, :asset, :uri)")
			.param("file", fileId)
			.param("asset", assetKey)
			.param("uri", keyUri)
			.update(key, "video_asset_id");
		return key.getKey().longValue();
	}

	void insertJob(long assetId) {
		this.jdbc.sql("INSERT INTO video_transcode_jobs (video_asset_id) VALUES (:asset)").param("asset", assetId).update();
	}

	/**
	 * 실행할 때가 된 가장 오래된 대기 작업 하나를 running으로 바꾸고 lease를 건다. 여러 worker가 동시에 불러도 한 곳만 잡는다.
	 */
	@Transactional
	Optional<Claimed> claimNext(String owner, Duration lease) {
		Optional<Long> id = this.jdbc.sql("""
				SELECT video_transcode_job_id FROM video_transcode_jobs
				 WHERE job_state = 'queued' AND next_attempt_at <= CURRENT_TIMESTAMP(3)
				 ORDER BY next_attempt_at, video_transcode_job_id LIMIT 1 FOR UPDATE SKIP LOCKED
				""").query(Long.class).optional();
		if (id.isEmpty()) {
			return Optional.empty();
		}
		this.jdbc.sql("""
				UPDATE video_transcode_jobs
				   SET job_state = 'running', attempt_count = attempt_count + 1, lease_owner = :owner,
				       lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND),
				       started_at = CURRENT_TIMESTAMP(3)
				 WHERE video_transcode_job_id = :id AND job_state = 'queued'
				""").param("owner", owner).param("lease", lease.toSeconds()).param("id", id.get()).update();
		return this.jdbc.sql("""
				SELECT j.video_transcode_job_id, j.attempt_count, a.video_asset_id, a.file_id, a.asset_key, a.key_uri
				  FROM video_transcode_jobs j JOIN video_assets a ON a.video_asset_id = j.video_asset_id
				 WHERE j.video_transcode_job_id = :id
				""")
			.param("id", id.get())
			.query((rs, n) -> new Claimed(rs.getLong(1), rs.getInt(2), rs.getLong(3), rs.getLong(4), rs.getString(5),
					rs.getString(6)))
			.optional();
	}

	/** @return lease를 아직 갖고 있으면 true */
	boolean renewLease(long jobId, String owner, Duration lease) {
		return this.jdbc.sql("""
				UPDATE video_transcode_jobs SET lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND)
				 WHERE video_transcode_job_id = :id AND job_state = 'running' AND lease_owner = :owner
				""").param("lease", lease.toSeconds()).param("id", jobId).param("owner", owner).update() == 1;
	}

	boolean cancelRequested(long jobId) {
		return this.jdbc
			.sql("SELECT cancel_requested_at IS NOT NULL FROM video_transcode_jobs WHERE video_transcode_job_id = :id")
			.param("id", jobId)
			.query(Boolean.class)
			.single();
	}

	/** @return 취소 요청이 없고 lease를 갖고 있어 성공으로 닫았으면 true */
	boolean succeed(long jobId, String owner) {
		return this.jdbc.sql("""
				UPDATE video_transcode_jobs
				   SET job_state = 'succeeded', error_code = NULL, finished_at = CURRENT_TIMESTAMP(3),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE video_transcode_job_id = :id AND job_state = 'running' AND lease_owner = :owner
				   AND cancel_requested_at IS NULL
				""").param("id", jobId).param("owner", owner).update() == 1;
	}

	/** 실행 중 작업을 대기로 돌린다. delay가 지나야 다시 잡힌다. */
	boolean requeue(long jobId, String owner, String errorCode, Duration delay) {
		return this.jdbc.sql("""
				UPDATE video_transcode_jobs
				   SET job_state = 'queued', error_code = :code,
				       next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :delay SECOND),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE video_transcode_job_id = :id AND job_state = 'running' AND lease_owner = :owner
				""").param("code", errorCode).param("delay", delay.toSeconds()).param("id", jobId).param("owner", owner).update() == 1;
	}

	/** @param state failed 또는 cancelled */
	boolean close(long jobId, String owner, String state, String errorCode) {
		return this.jdbc.sql("""
				UPDATE video_transcode_jobs
				   SET job_state = :state, error_code = :code, finished_at = CURRENT_TIMESTAMP(3),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE video_transcode_job_id = :id AND job_state = 'running' AND lease_owner = :owner
				""").param("state", state).param("code", errorCode).param("id", jobId).param("owner", owner).update() == 1;
	}

	/** lease가 끝난 실행 중 작업을 잠근다. 다른 worker가 잠근 행은 건너뛴다. */
	List<Expired> lockExpired(int limit) {
		return this.jdbc.sql("""
				SELECT video_transcode_job_id, video_asset_id, attempt_count, cancel_requested_at IS NOT NULL
				  FROM video_transcode_jobs
				 WHERE job_state = 'running' AND lease_expires_at < CURRENT_TIMESTAMP(3)
				 ORDER BY video_transcode_job_id LIMIT :limit FOR UPDATE SKIP LOCKED
				""")
			.param("limit", limit)
			.query((rs, n) -> new Expired(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getBoolean(4)))
			.list();
	}

	/** lease가 끝난 작업을 닫거나 대기로 돌린다. @param state queued, failed, cancelled */
	void closeExpired(long jobId, String state, Duration delay) {
		this.jdbc.sql("""
				UPDATE video_transcode_jobs
				   SET job_state = :state, error_code = 'interrupted',
				       next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :delay SECOND),
				       finished_at = IF(:state = 'queued', NULL, CURRENT_TIMESTAMP(3)),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE video_transcode_job_id = :id AND job_state = 'running' AND lease_expires_at < CURRENT_TIMESTAMP(3)
				""").param("state", state).param("delay", delay.toSeconds()).param("id", jobId).update();
	}

	/** 원본이 지워졌는데 아직 거두지 않은 asset을 잠근다. */
	List<Long> lockAssetsOfInactiveFiles(int limit) {
		return this.jdbc.sql("""
				SELECT a.video_asset_id FROM video_assets a JOIN files f ON f.file_id = a.file_id
				 WHERE f.state <> 'active' AND a.asset_state <> 'removed'
				 ORDER BY a.video_asset_id LIMIT :limit FOR UPDATE OF a SKIP LOCKED
				""").param("limit", limit).query(Long.class).list();
	}

	/** asset을 거두고 대기 작업은 취소, 실행 중 작업에는 취소를 요청한다. */
	void remove(long assetId) {
		this.jdbc.sql("UPDATE video_assets SET asset_state = 'removed' WHERE video_asset_id = :id")
			.param("id", assetId)
			.update();
		this.jdbc.sql("""
				UPDATE video_transcode_jobs
				   SET job_state = 'cancelled', cancel_requested_at = CURRENT_TIMESTAMP(3), finished_at = CURRENT_TIMESTAMP(3)
				 WHERE video_asset_id = :id AND job_state = 'queued'
				""").param("id", assetId).update();
		this.jdbc.sql("""
				UPDATE video_transcode_jobs SET cancel_requested_at = IFNULL(cancel_requested_at, CURRENT_TIMESTAMP(3))
				 WHERE video_asset_id = :id AND job_state = 'running'
				""").param("id", assetId).update();
	}

	/** 거둔 asset 중 실행 중 작업이 없어 HLS와 key를 지워도 되는 것. */
	List<Purge> purgeable(int limit) {
		return this.jdbc.sql("""
				SELECT a.video_asset_id, a.asset_key FROM video_assets a
				 WHERE a.asset_state = 'removed' AND a.purged_at IS NULL
				   AND NOT EXISTS (SELECT 1 FROM video_transcode_jobs j
				                    WHERE j.video_asset_id = a.video_asset_id AND j.job_state = 'running')
				 ORDER BY a.video_asset_id LIMIT :limit
				""").param("limit", limit).query((rs, n) -> new Purge(rs.getLong(1), rs.getString(2))).list();
	}

	void markPurged(long assetId) {
		this.jdbc.sql("UPDATE video_assets SET purged_at = CURRENT_TIMESTAMP(3) WHERE video_asset_id = :id AND purged_at IS NULL")
			.param("id", assetId)
			.update();
	}

	/** 원본이 아직 있을 때만 ready로 바꾼다. @return 바꿨으면 true */
	boolean ready(long assetId, String playlistPath, int durationSec, int segments, long outputBytes) {
		return this.jdbc.sql("""
				UPDATE video_assets a JOIN files f ON f.file_id = a.file_id
				   SET a.asset_state = 'ready', a.playlist_path = :path, a.duration_sec = :duration,
				       a.segment_count = :segments, a.output_bytes = :bytes
				 WHERE a.video_asset_id = :id AND a.asset_state = 'processing' AND f.state = 'active'
				""")
			.param("path", playlistPath)
			.param("duration", durationSec)
			.param("segments", segments)
			.param("bytes", outputBytes)
			.param("id", assetId)
			.update() == 1;
	}

	boolean failed(long assetId) {
		return this.jdbc
			.sql("UPDATE video_assets SET asset_state = 'failed' WHERE video_asset_id = :id AND asset_state = 'processing'")
			.param("id", assetId)
			.update() == 1;
	}

	record Claimed(long jobId, int attempt, long assetId, long fileId, String assetKey, String keyUri) {
	}

	record Expired(long jobId, long assetId, int attempts, boolean cancelRequested) {
	}

	record Purge(long assetId, String assetKey) {
	}

}
