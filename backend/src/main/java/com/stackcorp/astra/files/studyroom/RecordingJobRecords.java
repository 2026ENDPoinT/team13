package com.stackcorp.astra.files.studyroom;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 녹음 전사·요약 작업(study_room_recording_jobs)과 사용자별 전사 사용량(study_room_transcription_usage). ADR-0041.
 * <p>
 * 상태 전이는 모두 현재 상태를 조건으로 거는 UPDATE다. 실행 중 작업의 기록은 lease 소유자일 때만 반영되므로, 녹음을 지워 행이
 * 사라졌거나 lease를 잃은 worker의 결과는 0행으로 버려진다. 잠금 순서는 jobs → usage다.
 */
@Repository
class RecordingJobRecords {

	private static final String CLAIMED = """
			SELECT j.study_room_recording_job_id, j.owner_user_id, j.study_room_recording_id, r.course_id, j.job_kind,
			       j.attempt_count, r.duration_ms, r.audio_mime_type, f.file_size_bytes, b.storage_key
			  FROM study_room_recording_jobs j
			  JOIN study_room_recordings r ON r.study_room_recording_id = j.study_room_recording_id
			  JOIN files f ON f.file_id = r.file_id
			  JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
			""";

	private final JdbcClient jdbc;

	RecordingJobRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ---------- api ----------

	/** 녹음 행을 만든 같은 transaction에서 부른다. */
	void insert(long owner, long recordingId, RecordingJobKind kind) {
		this.jdbc.sql("""
				INSERT INTO study_room_recording_jobs (owner_user_id, study_room_recording_id, job_kind)
				VALUES (:owner, :recording, :kind)
				""").param("owner", owner).param("recording", recordingId).param("kind", kind.value()).update();
	}

	/** 소유자의 녹음 하나의 작업. 결과를 함께 읽는다. */
	List<JobRow> ofRecording(long owner, long recordingId) {
		return this.jdbc.sql("""
				SELECT job_kind, job_state, failure_code, result_json FROM study_room_recording_jobs
				 WHERE owner_user_id = :owner AND study_room_recording_id = :recording
				""")
			.param("owner", owner)
			.param("recording", recordingId)
			.query((rs, n) -> new JobRow(RecordingJobKind.of(rs.getString(1)), rs.getString(2), rs.getString(3),
					rs.getString(4)))
			.list();
	}

	/** 녹음을 지우는 transaction에서 녹음 행보다 먼저 부른다. 결과도 함께 지워진다. */
	void deleteOfRecording(long owner, long recordingId) {
		this.jdbc.sql("DELETE FROM study_room_recording_jobs WHERE owner_user_id = :owner AND study_room_recording_id = :recording")
			.param("owner", owner)
			.param("recording", recordingId)
			.update();
	}

	// ---------- worker: 정리 ----------

	/** 파일이 검사에서 거부된 녹음의 대기 작업을 닫는다. */
	int failRejected() {
		return this.jdbc.sql("""
				UPDATE study_room_recording_jobs j
				  JOIN study_room_recordings r ON r.study_room_recording_id = j.study_room_recording_id
				  JOIN files f ON f.file_id = r.file_id
				  JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				   SET j.job_state = 'failed', j.failure_code = 'rejected', j.finished_at = CURRENT_TIMESTAMP(3)
				 WHERE j.job_state = 'queued' AND (f.scan_state = 'infected' OR b.blob_state = 'rejected')
				""").update();
	}

	/** 제공자가 설정되지 않은 종류의 대기 작업을 닫는다. */
	int failQueued(RecordingJobKind kind, String code) {
		return this.jdbc.sql("""
				UPDATE study_room_recording_jobs SET job_state = 'failed', failure_code = :code, finished_at = CURRENT_TIMESTAMP(3)
				 WHERE job_kind = :kind AND job_state = 'queued'
				""").param("code", code).param("kind", kind.value()).update();
	}

	/** 전사가 실패한 녹음의 대기 요약을 닫는다. */
	int failOrphanSummaries() {
		return this.jdbc.sql("""
				UPDATE study_room_recording_jobs s
				  JOIN study_room_recording_jobs t ON t.study_room_recording_id = s.study_room_recording_id
				                                  AND t.job_kind = 'transcription'
				   SET s.job_state = 'failed', s.failure_code = 'transcript_failed', s.finished_at = CURRENT_TIMESTAMP(3)
				 WHERE s.job_kind = 'summary' AND s.job_state = 'queued' AND t.job_state = 'failed'
				""").update();
	}

	// ---------- worker: 잡기 ----------

	int processing(RecordingJobKind kind) {
		return this.jdbc.sql("SELECT COUNT(*) FROM study_room_recording_jobs WHERE job_kind = :kind AND job_state = 'processing'")
			.param("kind", kind.value())
			.query(Integer.class)
			.single();
	}

	/** 검사를 마친(ready) 녹음의 실행할 수 있는 대기 전사 하나를 잠근다. 다른 worker가 잠근 행은 건너뛴다. */
	Optional<Claimed> lockNextTranscription() {
		return this.jdbc.sql(CLAIMED + """
				 WHERE j.job_kind = 'transcription' AND j.job_state = 'queued' AND j.next_attempt_at <= CURRENT_TIMESTAMP(3)
				   AND f.scan_state = 'clean' AND b.blob_state = 'clean'
				 ORDER BY j.next_attempt_at, j.study_room_recording_job_id LIMIT 1
				 FOR UPDATE OF j SKIP LOCKED
				""").query(RecordingJobRecords::claimed).optional();
	}

	/** 같은 녹음의 전사가 ready인 실행할 수 있는 대기 요약 하나를 잠근다. */
	Optional<Claimed> lockNextSummary() {
		return this.jdbc.sql(CLAIMED + """
				  JOIN study_room_recording_jobs t ON t.study_room_recording_id = j.study_room_recording_id
				                                  AND t.job_kind = 'transcription'
				 WHERE j.job_kind = 'summary' AND j.job_state = 'queued' AND j.next_attempt_at <= CURRENT_TIMESTAMP(3)
				   AND t.job_state = 'ready'
				 ORDER BY j.next_attempt_at, j.study_room_recording_job_id LIMIT 1
				 FOR UPDATE OF j SKIP LOCKED
				""").query(RecordingJobRecords::claimed).optional();
	}

	/** 잠근 대기 작업을 실행 중으로 바꾼다. @param reservedSeconds 전사면 잡아 둔 초, 요약이면 null */
	void start(long jobId, String owner, Duration lease, Integer reservedSeconds) {
		this.jdbc.sql("""
				UPDATE study_room_recording_jobs
				   SET job_state = 'processing', attempt_count = attempt_count + 1, lease_owner = :owner,
				       lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND),
				       started_at = CURRENT_TIMESTAMP(3), reserved_seconds = :reserved,
				       reserved_on = IF(:reserved IS NULL, NULL, CURRENT_DATE)
				 WHERE study_room_recording_job_id = :id AND job_state = 'queued'
				""")
			.param("owner", owner)
			.param("lease", lease.toSeconds())
			.param("reserved", reservedSeconds)
			.param("id", jobId)
			.update();
	}

	/** 잠근 대기 작업을 실행하지 않고 실패로 닫는다. */
	void failLocked(long jobId, String code) {
		this.jdbc.sql("""
				UPDATE study_room_recording_jobs SET job_state = 'failed', failure_code = :code, finished_at = CURRENT_TIMESTAMP(3)
				 WHERE study_room_recording_job_id = :id AND job_state = 'queued'
				""").param("code", code).param("id", jobId).update();
	}

	/** 녹음의 대기 요약을 실패로 닫는다. 전사가 실패한 transaction에서 부른다. */
	void failQueuedSummary(long recordingId, String code) {
		this.jdbc.sql("""
				UPDATE study_room_recording_jobs SET job_state = 'failed', failure_code = :code, finished_at = CURRENT_TIMESTAMP(3)
				 WHERE study_room_recording_id = :recording AND job_kind = 'summary' AND job_state = 'queued'
				""").param("code", code).param("recording", recordingId).update();
	}

	// ---------- worker: 실행 중 ----------

	/** @return lease를 아직 갖고 있으면 true. 녹음을 지워 행이 없으면 false */
	boolean renewLease(long jobId, String owner, Duration lease) {
		return this.jdbc.sql("""
				UPDATE study_room_recording_jobs SET lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND)
				 WHERE study_room_recording_job_id = :id AND job_state = 'processing' AND lease_owner = :owner
				""").param("lease", lease.toSeconds()).param("id", jobId).param("owner", owner).update() == 1;
	}

	/** 실행 중 작업을 잠근다. lease 소유자가 아니거나 행이 없으면 비어 있다. */
	Optional<Running> lockRunning(long jobId, String owner) {
		return this.jdbc.sql("""
				SELECT reserved_seconds, reserved_on FROM study_room_recording_jobs
				 WHERE study_room_recording_job_id = :id AND job_state = 'processing' AND lease_owner = :owner FOR UPDATE
				""")
			.param("id", jobId)
			.param("owner", owner)
			.query((rs, n) -> new Running(rs.getObject(1, Integer.class), rs.getObject(2, java.sql.Date.class)))
			.optional();
	}

	/** {@link #lockRunning} 뒤에 부른다. @param result ready면 결과 JSON, failed면 null */
	void finish(long jobId, String state, String code, String result, Integer audioSeconds) {
		this.jdbc.sql("""
				UPDATE study_room_recording_jobs
				   SET job_state = :state, failure_code = :code, result_json = :result, audio_seconds = :audio,
				       finished_at = CURRENT_TIMESTAMP(3), lease_owner = NULL, lease_expires_at = NULL,
				       reserved_seconds = NULL, reserved_on = NULL
				 WHERE study_room_recording_job_id = :id
				""")
			.param("state", state)
			.param("code", code)
			.param("result", result)
			.param("audio", audioSeconds)
			.param("id", jobId)
			.update();
	}

	/** {@link #lockRunning} 또는 {@link #lockExpired} 뒤에 부른다. 대기로 돌린다. */
	void requeue(long jobId, String code, Duration delay) {
		this.jdbc.sql("""
				UPDATE study_room_recording_jobs
				   SET job_state = 'queued', failure_code = :code,
				       next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :delay SECOND),
				       lease_owner = NULL, lease_expires_at = NULL, reserved_seconds = NULL, reserved_on = NULL
				 WHERE study_room_recording_job_id = :id
				""").param("code", code).param("delay", delay.toSeconds()).param("id", jobId).update();
	}

	/** lease가 끝난 실행 중 작업을 잠근다. 다른 worker가 잠근 행은 건너뛴다. */
	List<Expired> lockExpired(int limit) {
		return this.jdbc.sql("""
				SELECT study_room_recording_job_id, study_room_recording_id, owner_user_id, job_kind, attempt_count,
				       reserved_seconds, reserved_on
				  FROM study_room_recording_jobs
				 WHERE job_state = 'processing' AND lease_expires_at < CURRENT_TIMESTAMP(3)
				 ORDER BY study_room_recording_job_id LIMIT :limit FOR UPDATE SKIP LOCKED
				""")
			.param("limit", limit)
			.query((rs, n) -> new Expired(rs.getLong(1), rs.getLong(2), rs.getLong(3), RecordingJobKind.of(rs.getString(4)),
					rs.getInt(5), new Running(rs.getObject(6, Integer.class), rs.getObject(7, java.sql.Date.class))))
			.list();
	}

	/** 같은 녹음의 ready 전사 결과와 그 실제 길이(초). */
	Optional<TranscriptRow> transcriptOf(long recordingId) {
		return this.jdbc.sql("""
				SELECT result_json, audio_seconds FROM study_room_recording_jobs
				 WHERE study_room_recording_id = :recording AND job_kind = 'transcription' AND job_state = 'ready'
				""")
			.param("recording", recordingId)
			.query((rs, n) -> new TranscriptRow(rs.getString(1), rs.getObject(2, Integer.class)))
			.optional();
	}

	// ---------- 사용량 ----------

	/** 오늘 행을 만들고(없으면) 잠근 뒤 오늘과 이번 달 합(초)을 돌려준다. */
	Usage lockUsage(long owner) {
		this.jdbc.sql("""
				INSERT INTO study_room_transcription_usage (owner_user_id, usage_date) VALUES (:owner, CURRENT_DATE)
				ON DUPLICATE KEY UPDATE owner_user_id = owner_user_id
				""").param("owner", owner).update();
		return this.jdbc.sql("""
				SELECT IFNULL(SUM(IF(usage_date = CURRENT_DATE, audio_seconds, 0)), 0), IFNULL(SUM(audio_seconds), 0)
				  FROM study_room_transcription_usage
				 WHERE owner_user_id = :owner AND usage_date >= DATE_FORMAT(CURRENT_DATE, '%Y-%m-01')
				   AND usage_date <= CURRENT_DATE
				 FOR UPDATE
				""").param("owner", owner).query((rs, n) -> new Usage(rs.getLong(1), rs.getLong(2))).single();
	}

	void addToday(long owner, long seconds) {
		this.jdbc.sql("""
				UPDATE study_room_transcription_usage SET audio_seconds = audio_seconds + :seconds
				 WHERE owner_user_id = :owner AND usage_date = CURRENT_DATE
				""").param("seconds", seconds).param("owner", owner).update();
	}

	/** 잡아 둔 날짜의 행을 고친다. 음수가 되지 않는다. @param delta 더할 초(돌려줄 때는 음수) */
	void adjust(long owner, java.sql.Date on, long delta) {
		if (on == null || delta == 0) {
			return;
		}
		this.jdbc.sql("""
				INSERT INTO study_room_transcription_usage (owner_user_id, usage_date, audio_seconds)
				VALUES (:owner, :on, GREATEST(:delta, 0))
				ON DUPLICATE KEY UPDATE audio_seconds = GREATEST(CAST(study_room_transcription_usage.audio_seconds AS SIGNED) + :delta, 0)
				""").param("owner", owner).param("on", on).param("delta", delta).update();
	}

	/** 테스트와 운영 확인용: 사용자의 날짜별 사용량 합. */
	long usageSeconds(long owner) {
		return this.jdbc.sql("SELECT IFNULL(SUM(audio_seconds), 0) FROM study_room_transcription_usage WHERE owner_user_id = :owner")
			.param("owner", owner)
			.query(Long.class)
			.single();
	}

	private static Claimed claimed(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
		return new Claimed(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), RecordingJobKind.of(rs.getString(5)),
				rs.getInt(6) + 1, rs.getLong(7), rs.getString(8), rs.getLong(9), rs.getString(10));
	}

	/**
	 * @param resultJson ready면 결과 JSON
	 */
	record JobRow(RecordingJobKind kind, String state, String failureCode, String resultJson) {
	}

	/**
	 * 잡은 작업.
	 *
	 * @param attempt 이번 실행이 몇 번째인지(1부터)
	 * @param durationMs 올릴 때 보낸 길이(client 값)
	 */
	record Claimed(long jobId, long owner, long recordingId, long courseId, RecordingJobKind kind, int attempt,
			long durationMs, String mimeType, long sizeBytes, String storageKey) {
	}

	/** @param reservedOn 사용량을 잡아 둔 날짜. 요약이면 null */
	record Running(Integer reservedSeconds, java.sql.Date reservedOn) {
	}

	record Expired(long jobId, long recordingId, long owner, RecordingJobKind kind, int attempts, Running running) {
	}

	record TranscriptRow(String resultJson, Integer audioSeconds) {
	}

	/** 초 단위. */
	record Usage(long today, long month) {
	}

}
