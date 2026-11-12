package com.stackcorp.astra.files.filebox;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * zip_jobs, zip_job_items. 상태 전이는 모두 현재 상태와 lease 소유자를 조건으로 건 compare-and-set이다(SC-05). worker가 둘이어도
 * 같은 작업을 함께 만들지 않고, lease를 잃은 worker의 늦은 결과는 반영되지 않는다.
 * <p>
 * 상태: requested → building → ready → expired, 실패하면 failed → expired. ready와 failed는 TTL이 지나면 expired가 되고
 * 저장소 객체를 지운다.
 */
@Repository
class ZipJobRecords {

	private final JdbcClient jdbc;

	ZipJobRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	long insert(long owner, String itemSetHash, List<Long> itemIds, long totalBytes) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO zip_jobs (owner_user_id, item_set_hash, item_count, total_bytes, zip_state)
				VALUES (:owner, :hash, :count, :total, 'requested')
				""")
			.param("owner", owner)
			.param("hash", itemSetHash)
			.param("count", itemIds.size())
			.param("total", totalBytes)
			.update(key, "zip_job_id");
		long jobId = key.getKey().longValue();
		int order = 1;
		for (long itemId : itemIds) {
			this.jdbc.sql("""
					INSERT INTO zip_job_items (zip_job_id, filebox_item_id, sort_order) VALUES (:job, :item, :order)
					""").param("job", jobId).param("item", itemId).param("order", order++).update();
		}
		return jobId;
	}

	Optional<Job> find(long owner, long jobId) {
		return this.jdbc.sql(SELECT + " WHERE zip_job_id = :id AND owner_user_id = :owner")
			.param("id", jobId)
			.param("owner", owner)
			.query(ZipJobRecords::job)
			.optional();
	}

	/** 같은 항목 집합으로 기다리는 중, 만드는 중이거나 아직 내려받을 수 있는 작업. */
	Optional<Job> reusable(long owner, String itemSetHash) {
		return this.jdbc.sql(SELECT + """
				 WHERE owner_user_id = :owner AND item_set_hash = :hash
				   AND (zip_state IN ('requested', 'building') OR (zip_state = 'ready' AND expires_at > CURRENT_TIMESTAMP(3)))
				 ORDER BY zip_job_id DESC LIMIT 1
				""").param("owner", owner).param("hash", itemSetHash).query(ZipJobRecords::job).optional();
	}

	/** 기다리거나 만들고 있는 작업 수. */
	int pending(long owner) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM zip_jobs WHERE owner_user_id = :owner AND zip_state IN ('requested', 'building')
				""").param("owner", owner).query(Integer.class).single();
	}

	/**
	 * 만들 작업 하나를 잡고 lease를 건다. 기다리는 작업이나 lease가 끝난 작업만 고르고, 다른 worker가 잡고 있는 행은 건너뛴다.
	 * 시도 수를 다 쓴 작업은 고르지 않는다({@link #failExhausted}가 닫는다). transaction 안에서 부른다.
	 */
	Optional<Claimed> claim(String owner, Duration lease, int maxAttempts) {
		Optional<Claimed> next = this.jdbc.sql("""
				SELECT zip_job_id, owner_user_id, attempt_count + 1 FROM zip_jobs
				 WHERE (zip_state = 'requested' OR (zip_state = 'building' AND lease_expires_at <= CURRENT_TIMESTAMP(3)))
				   AND attempt_count < :max
				 ORDER BY zip_job_id LIMIT 1 FOR UPDATE SKIP LOCKED
				""")
			.param("max", maxAttempts)
			.query((rs, n) -> new Claimed(rs.getLong(1), rs.getLong(2), rs.getInt(3)))
			.optional();
		next.ifPresent((job) -> this.jdbc.sql("""
				UPDATE zip_jobs
				   SET zip_state = 'building', lease_owner = :owner, attempt_count = attempt_count + 1,
				       lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND)
				 WHERE zip_job_id = :id
				""").param("owner", owner).param("lease", lease.toSeconds()).param("id", job.id()).update());
		return next;
	}

	/** lease가 끝났는데 시도 수를 다 쓴 작업을 failed로 닫는다. @return 닫은 수 */
	int failExhausted(int maxAttempts, Duration ttl) {
		return this.jdbc.sql("""
				UPDATE zip_jobs
				   SET zip_state = 'failed', failure_reason = 'build_failed', lease_owner = NULL, lease_expires_at = NULL,
				       expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :ttl SECOND)
				 WHERE zip_state = 'building' AND lease_expires_at <= CURRENT_TIMESTAMP(3) AND attempt_count >= :max
				""").param("ttl", ttl.toSeconds()).param("max", maxAttempts).update();
	}

	/** 작업의 파일. ZIP 안 순서다. 요청한 사람의 항목만 고른다. */
	List<Entry> entries(long jobId, long owner) {
		return this.jdbc.sql("""
				SELECT i.item_name, i.created_at, b.storage_key,
				       i.item_state = 'active' AND i.item_kind = 'file' AND f.state = 'active' AND f.scan_state = 'clean'
				         AND b.blob_state = 'clean' AND b.purged_at IS NULL AS available
				  FROM zip_job_items z
				  JOIN filebox_items i ON i.filebox_item_id = z.filebox_item_id AND i.owner_user_id = :owner
				  LEFT JOIN files f ON f.file_id = i.file_id
				  LEFT JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE z.zip_job_id = :job
				 ORDER BY z.sort_order
				""")
			.param("job", jobId)
			.param("owner", owner)
			.query((rs, n) -> new Entry(rs.getString(1), rs.getObject(2, LocalDateTime.class), rs.getString(3),
					rs.getBoolean(4)))
			.list();
	}

	/** @return lease를 아직 갖고 있어 늘렸는지 */
	boolean renew(long jobId, String owner, Duration lease) {
		return this.jdbc.sql("""
				UPDATE zip_jobs SET lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND)
				 WHERE zip_job_id = :id AND zip_state = 'building' AND lease_owner = :owner
				""").param("lease", lease.toSeconds()).param("id", jobId).param("owner", owner).update() == 1;
	}

	/** @return lease를 아직 갖고 있어 ready로 바꿨는지 */
	boolean complete(long jobId, String owner, String storageKey, long sizeBytes, Duration ttl) {
		return this.jdbc.sql("""
				UPDATE zip_jobs
				   SET zip_state = 'ready', result_storage_key = :key, result_size_bytes = :size,
				       completed_at = CURRENT_TIMESTAMP(3), expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :ttl SECOND),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE zip_job_id = :id AND zip_state = 'building' AND lease_owner = :owner
				""")
			.param("key", storageKey)
			.param("size", sizeBytes)
			.param("ttl", ttl.toSeconds())
			.param("id", jobId)
			.param("owner", owner)
			.update() == 1;
	}

	/** 다시 시도하도록 lease를 풀고 requested로 되돌린다. @return lease를 아직 갖고 있었는지 */
	boolean retry(long jobId, String owner) {
		return this.jdbc.sql("""
				UPDATE zip_jobs SET zip_state = 'requested', lease_owner = NULL, lease_expires_at = NULL
				 WHERE zip_job_id = :id AND zip_state = 'building' AND lease_owner = :owner
				""").param("id", jobId).param("owner", owner).update() == 1;
	}

	/** lease를 풀고 failed로 닫는다. @return lease를 아직 갖고 있었는지 */
	boolean fail(long jobId, String owner, String reason, Duration ttl) {
		return this.jdbc.sql("""
				UPDATE zip_jobs
				   SET zip_state = 'failed', failure_reason = :reason, lease_owner = NULL, lease_expires_at = NULL,
				       expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :ttl SECOND)
				 WHERE zip_job_id = :id AND zip_state = 'building' AND lease_owner = :owner
				""")
			.param("reason", reason)
			.param("ttl", ttl.toSeconds())
			.param("id", jobId)
			.param("owner", owner)
			.update() == 1;
	}

	/**
	 * TTL이 지난 ready·failed 작업을 잠그고 expired로 바꾼다. 지난번에 객체를 지우지 못한 expired 작업도 다시 고른다.
	 * transaction 안에서 부른다.
	 *
	 * @return 객체를 지울 작업 ID
	 */
	List<Long> expire(int limit) {
		List<Long> ids = this.jdbc.sql("""
				SELECT zip_job_id FROM zip_jobs
				 WHERE (zip_state IN ('ready', 'failed') AND expires_at <= CURRENT_TIMESTAMP(3))
				    OR (zip_state = 'expired' AND purged_at IS NULL)
				 ORDER BY zip_job_id LIMIT :limit FOR UPDATE SKIP LOCKED
				""").param("limit", limit).query(Long.class).list();
		if (!ids.isEmpty()) {
			this.jdbc.sql("""
					UPDATE zip_jobs SET zip_state = 'expired'
					 WHERE zip_job_id IN (:ids) AND zip_state IN ('ready', 'failed') AND expires_at <= CURRENT_TIMESTAMP(3)
					""").param("ids", ids).update();
		}
		return ids;
	}

	void purged(long jobId) {
		this.jdbc.sql("UPDATE zip_jobs SET purged_at = CURRENT_TIMESTAMP(3) WHERE zip_job_id = :id AND zip_state = 'expired' AND purged_at IS NULL")
			.param("id", jobId)
			.update();
	}

	private static final String SELECT = """
			SELECT zip_job_id, zip_state, item_count, total_bytes, result_storage_key, result_size_bytes, expires_at,
			       expires_at <= CURRENT_TIMESTAMP(3) AS expired, failure_reason
			  FROM zip_jobs
			""";

	private static Job job(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		String state = rs.getString("zip_state");
		if ("ready".equals(state) && rs.getBoolean("expired")) {
			state = "expired";
		}
		java.sql.Timestamp expires = rs.getTimestamp("expires_at");
		return new Job(rs.getLong("zip_job_id"), state, rs.getInt("item_count"), rs.getLong("total_bytes"),
				rs.getString("result_storage_key"), rs.getObject("result_size_bytes", Long.class),
				expires == null ? null : expires.toInstant(), rs.getString("failure_reason"));
	}

	/** @param state ready이지만 TTL이 지났으면 GC 전이어도 expired */
	record Job(long id, String state, int itemCount, long totalBytes, String storageKey, Long sizeBytes,
			Instant expiresAt, String failure) {
	}

	/** @param attempt 이번 시도가 몇 번째인지. 1부터 센다 */
	record Claimed(long id, long owner, int attempt) {
	}

	/** @param available 파일함 항목이 활성이고 파일 내용이 검사를 통과해 남아 있는지 */
	record Entry(String name, LocalDateTime createdAt, String storageKey, boolean available) {
	}

}
