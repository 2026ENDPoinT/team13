package com.stackcorp.astra.summary.job;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.stackcorp.astra.summary.pipeline.Usage;

/**
 * summary_jobs, summary_publications. 상태 전이는 모두 현재 상태를 조건으로 거는 UPDATE라 끝난 작업은 바뀌지 않는다.
 * 실행 중 작업의 갱신은 lease 소유자일 때만 반영된다.
 */
@Repository
class SummaryJobRepository {

	private static final String COLUMNS = """
			summary_job_id, file_id, file_version_no, requested_by_user_id, retry_of_job_id, job_state, job_stage,
			pages_done, pages_total, model_name, prompt_version, call_count, input_tokens, output_tokens, error_code,
			error_message, cancel_requested_at, created_at, started_at, finished_at""";

	private final JdbcClient jdbc;

	SummaryJobRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @throws org.springframework.dao.DuplicateKeyException 같은 파일에 진행 중인 작업이 있을 때
	 */
	long insert(long fileId, int fileVersionNo, Long requestedBy, Long retryOf) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO summary_jobs (file_id, file_version_no, requested_by_user_id, retry_of_job_id)
				VALUES (:fileId, :version, :requestedBy, :retryOf)
				""")
			.param("fileId", fileId)
			.param("version", fileVersionNo)
			.param("requestedBy", requestedBy)
			.param("retryOf", retryOf)
			.update(key, "summary_job_id");
		return key.getKey().longValue();
	}

	Optional<SummaryJob> find(long jobId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM summary_jobs WHERE summary_job_id = :id")
			.param("id", jobId)
			.query(SummaryJobRepository::map)
			.optional();
	}

	/** 파일의 최근 작업. 최신이 먼저다. */
	List<SummaryJob> listByFile(long fileId, int limit) {
		return this.jdbc.sql("SELECT " + COLUMNS
				+ " FROM summary_jobs WHERE file_id = :fileId ORDER BY summary_job_id DESC LIMIT :limit")
			.param("fileId", fileId)
			.param("limit", limit)
			.query(SummaryJobRepository::map)
			.list();
	}

	/** 재시도 사슬의 첫 작업 ID. 중간 결과는 이 작업 이름 아래에 모인다. */
	long rootOf(long jobId) {
		long current = jobId;
		for (int depth = 0; depth < 100; depth++) {
			long parent = this.jdbc.sql("SELECT IFNULL(retry_of_job_id, 0) FROM summary_jobs WHERE summary_job_id = :id")
				.param("id", current)
				.query(Long.class)
				.single();
			if (parent == 0) {
				return current;
			}
			current = parent;
		}
		throw new IllegalStateException("재시도 사슬이 너무 깁니다: " + jobId);
	}

	/** 가장 오래된 대기 작업 하나를 running으로 바꾸고 lease를 건다. 여러 worker가 동시에 불러도 한 곳만 잡는다. */
	@Transactional
	Optional<SummaryJob> claimNext(String owner, Duration lease, String model, String promptVersion) {
		Optional<Long> id = this.jdbc.sql("""
				SELECT summary_job_id FROM summary_jobs WHERE job_state = 'queued'
				 ORDER BY summary_job_id LIMIT 1 FOR UPDATE SKIP LOCKED
				""").query(Long.class).optional();
		if (id.isEmpty()) {
			return Optional.empty();
		}
		this.jdbc.sql("""
				UPDATE summary_jobs
				   SET job_state = 'running', job_stage = 'pages', lease_owner = :owner,
				       lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND),
				       started_at = CURRENT_TIMESTAMP(3), model_name = :model, prompt_version = :prompt
				 WHERE summary_job_id = :id AND job_state = 'queued'
				""")
			.param("owner", owner)
			.param("lease", lease.toSeconds())
			.param("model", model)
			.param("prompt", promptVersion)
			.param("id", id.get())
			.update();
		return find(id.get());
	}

	/** @return lease를 아직 갖고 있으면 true */
	boolean renewLease(long jobId, String owner, Duration lease) {
		return this.jdbc.sql("""
				UPDATE summary_jobs SET lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND)
				 WHERE summary_job_id = :id AND job_state = 'running' AND lease_owner = :owner
				""").param("lease", lease.toSeconds()).param("id", jobId).param("owner", owner).update() == 1;
	}

	void progress(long jobId, String owner, String stage, int pagesDone, int pagesTotal, Usage usage) {
		this.jdbc.sql("""
				UPDATE summary_jobs
				   SET job_stage = :stage, pages_done = :done, pages_total = :total, call_count = :calls,
				       input_tokens = :input, output_tokens = :output
				 WHERE summary_job_id = :id AND job_state = 'running' AND lease_owner = :owner
				""")
			.param("stage", stage)
			.param("done", pagesDone)
			.param("total", pagesTotal)
			.param("calls", usage.calls())
			.param("input", usage.inputTokens())
			.param("output", usage.outputTokens())
			.param("id", jobId)
			.param("owner", owner)
			.update();
	}

	/** @return 반영됐으면 true. lease를 잃었으면 false */
	boolean finish(long jobId, String owner, JobState state, Usage usage, String errorCode, String errorMessage) {
		return this.jdbc.sql("""
				UPDATE summary_jobs
				   SET job_state = :state, job_stage = IF(:state = 'completed', 'done', job_stage),
				       call_count = :calls, input_tokens = :input, output_tokens = :output,
				       error_code = :code, error_message = :message,
				       finished_at = CURRENT_TIMESTAMP(3), lease_owner = NULL, lease_expires_at = NULL
				 WHERE summary_job_id = :id AND job_state = 'running' AND lease_owner = :owner
				""")
			.param("state", state.value())
			.param("calls", usage.calls())
			.param("input", usage.inputTokens())
			.param("output", usage.outputTokens())
			.param("code", errorCode)
			.param("message", errorMessage)
			.param("id", jobId)
			.param("owner", owner)
			.update() == 1;
	}

	/** @return 대기 중이던 작업을 취소했으면 true */
	boolean cancelQueued(long jobId) {
		return this.jdbc.sql("""
				UPDATE summary_jobs
				   SET job_state = 'cancelled', cancel_requested_at = CURRENT_TIMESTAMP(3), finished_at = CURRENT_TIMESTAMP(3)
				 WHERE summary_job_id = :id AND job_state = 'queued'
				""").param("id", jobId).update() == 1;
	}

	/** @return 실행 중인 작업에 취소를 요청했으면 true */
	boolean requestCancel(long jobId) {
		return this.jdbc.sql("""
				UPDATE summary_jobs SET cancel_requested_at = IFNULL(cancel_requested_at, CURRENT_TIMESTAMP(3))
				 WHERE summary_job_id = :id AND job_state = 'running'
				""").param("id", jobId).update() == 1;
	}

	boolean cancelRequested(long jobId) {
		return this.jdbc.sql("SELECT cancel_requested_at IS NOT NULL FROM summary_jobs WHERE summary_job_id = :id")
			.param("id", jobId)
			.query(Boolean.class)
			.single();
	}

	/** lease가 끝난 실행 중 작업을 interrupted로 닫는다. 자동으로 다시 실행하지 않는다(ADR-0006). */
	int failExpired() {
		return this.jdbc.sql("""
				UPDATE summary_jobs
				   SET job_state = 'failed', error_code = 'interrupted',
				       error_message = '작업을 실행하던 worker가 멈췄습니다. 다시 시도해 주세요.',
				       finished_at = CURRENT_TIMESTAMP(3), lease_owner = NULL, lease_expires_at = NULL
				 WHERE job_state = 'running' AND lease_expires_at < CURRENT_TIMESTAMP(3)
				""").update();
	}

	void publish(long fileId, long jobId, Long publishedBy) {
		this.jdbc.sql("""
				INSERT INTO summary_publications (file_id, summary_job_id, published_by_user_id)
				VALUES (:fileId, :jobId, :by) AS new
				ON DUPLICATE KEY UPDATE summary_job_id = new.summary_job_id,
				                        published_by_user_id = new.published_by_user_id,
				                        published_at = CURRENT_TIMESTAMP(3)
				""").param("fileId", fileId).param("jobId", jobId).param("by", publishedBy).update();
	}

	Optional<Publication> findPublication(long fileId) {
		return this.jdbc.sql("""
				SELECT summary_job_id, published_by_user_id, published_at FROM summary_publications WHERE file_id = :fileId
				""")
			.param("fileId", fileId)
			.query((rs, n) -> new Publication(fileId, rs.getLong(1), rs.getObject(2, Long.class), instant(rs, 3)))
			.optional();
	}

	record Publication(long fileId, long jobId, Long publishedByUserId, Instant publishedAt) {
	}

	private static SummaryJob map(ResultSet rs, int row) throws SQLException {
		return new SummaryJob(rs.getLong("summary_job_id"), rs.getLong("file_id"), rs.getInt("file_version_no"),
				rs.getObject("requested_by_user_id", Long.class), rs.getObject("retry_of_job_id", Long.class),
				JobState.of(rs.getString("job_state")), rs.getString("job_stage"), rs.getInt("pages_done"),
				rs.getObject("pages_total", Integer.class), rs.getString("model_name"), rs.getString("prompt_version"),
				rs.getInt("call_count"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
				rs.getString("error_code"), rs.getString("error_message"), rs.getTimestamp("cancel_requested_at") != null,
				instant(rs, "created_at"), instant(rs, "started_at"), instant(rs, "finished_at"));
	}

	private static Instant instant(ResultSet rs, String column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

	private static Instant instant(ResultSet rs, int column) throws SQLException {
		Timestamp t = rs.getTimestamp(column);
		return t == null ? null : t.toInstant();
	}

}
