package com.stackcorp.astra.files;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * conversion_jobs와 그 썸네일(file_thumbnails). rendition 하나가 행 하나다. converted_doc_key가 rendition key다.
 * <p>
 * 상태는 {@code requested → converting → ready | failed}다. converting은 worker의 lease를 갖는다. 시도 수가 남은 일시 장애와 lease가
 * 끝난 변환은 requested로 돌아간다. 같은 파일 버전에 requested, converting, ready 행은 하나만 둘 수 있다(uq_conversion_jobs_live_slot).
 * 상태 전이는 모두 상태와 lease 소유자를 조건으로 한 compare-and-set이다(SC-05).
 * <p>
 * 삭제한 파일의 rendition은 없는 것으로 본다. 변환 중에 파일이 지워지면 GC가 행을 expired로 바꾸고, 변환은 완료로 바꾸지 못한다.
 */
@Repository
class ConversionRecords {

	private final JdbcClient jdbc;

	ConversionRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	Optional<String> findReadyKey(long fileId, int versionNo) {
		return this.jdbc.sql("""
				SELECT c.converted_doc_key FROM conversion_jobs c JOIN files f ON f.file_id = c.file_id
				 WHERE c.file_id = :fileId AND c.file_version_no = :version AND c.conv_type = 'img'
				   AND c.conversion_state = 'ready' AND f.state = 'active'
				 ORDER BY c.conversion_job_id DESC LIMIT 1
				""")
			.param("fileId", fileId)
			.param("version", versionNo)
			.query(String.class)
			.optional();
	}

	/** 지우지 않은 파일의 완료된 rendition key인지. */
	boolean live(String key) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM conversion_jobs c JOIN files f ON f.file_id = c.file_id
				 WHERE c.converted_doc_key = :key AND c.conversion_state = 'ready' AND f.state = 'active'
				""").param("key", key).query(Long.class).single() > 0;
	}

	/** 파일 버전의 마지막 rendition. GC가 닫은(expired) 행은 보지 않는다. */
	Optional<Job> latest(long fileId, int versionNo) {
		return this.jdbc.sql("""
				SELECT conversion_job_id, conversion_state, converted_doc_key, page_count, error_code
				  FROM conversion_jobs
				 WHERE file_id = :fileId AND file_version_no = :version AND conv_type = 'img' AND conversion_state <> 'expired'
				 ORDER BY conversion_job_id DESC LIMIT 1
				""")
			.param("fileId", fileId)
			.param("version", versionNo)
			.query((rs, n) -> new Job(rs.getLong(1), State.valueOf(rs.getString(2).toUpperCase()), rs.getString(3),
					rs.getObject(4, Integer.class), rs.getString(5)))
			.optional();
	}

	/**
	 * 변환을 대기열에 넣는다. 같은 파일 버전에 대기 중, 변환 중, 완료된 rendition이 있으면 DB 유일 키가 막는다.
	 *
	 * @param userId 요청한 사용자. worker가 스스로 넣으면 null
	 * @return 넣었으면 true
	 */
	boolean request(long fileId, int versionNo, Long userId, String key) {
		try {
			this.jdbc.sql("""
					INSERT INTO conversion_jobs (file_id, file_version_no, requested_by_user_id, conv_type, converted_doc_key, conversion_state)
					VALUES (:fileId, :version, :userId, 'img', :key, 'requested')
					""")
				.param("fileId", fileId)
				.param("version", versionNo)
				.param("userId", userId)
				.param("key", key)
				.update();
			return true;
		}
		catch (DuplicateKeyException ex) {
			return false;
		}
	}

	/** 다시 시도할 때가 된 대기 변환 하나를 잡는다. 다른 worker가 잡고 있는 행은 건너뛴다. transaction 안에서 부른다. */
	Optional<Claimed> claimNext(String owner, Duration lease) {
		return this.jdbc.sql("""
				SELECT conversion_job_id FROM conversion_jobs
				 WHERE conversion_state = 'requested' AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(3))
				 ORDER BY conversion_job_id LIMIT 1 FOR UPDATE SKIP LOCKED
				""").query(Long.class).optional().flatMap((id) -> claim(id, owner, lease));
	}

	/** 정한 대기 변환을 잡는다. 재시도를 기다리는 중이어도 잡는다. 다른 worker가 먼저 잡았으면 비어 있다. */
	Optional<Claimed> claim(long id, String owner, Duration lease) {
		int updated = this.jdbc.sql("""
				UPDATE conversion_jobs
				   SET conversion_state = 'converting', lease_owner = :owner, attempt_count = attempt_count + 1,
				       lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND), next_attempt_at = NULL,
				       started_at = CURRENT_TIMESTAMP(3)
				 WHERE conversion_job_id = :id AND conversion_state = 'requested'
				""").param("owner", owner).param("lease", lease.toSeconds()).param("id", id).update();
		if (updated == 0) {
			return Optional.empty();
		}
		return this.jdbc.sql("""
				SELECT conversion_job_id, file_id, file_version_no, converted_doc_key, attempt_count
				  FROM conversion_jobs WHERE conversion_job_id = :id
				""")
			.param("id", id)
			.query((rs, n) -> new Claimed(rs.getLong(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getInt(5)))
			.optional();
	}

	/**
	 * 완료로 바꾸고 썸네일 행을 쓴다. 두 쓰기는 같은 transaction이다. GC가 먼저 닫았거나 lease를 잃었으면 아무것도 쓰지 않는다.
	 * transaction 안에서 부른다.
	 *
	 * @return 완료로 바꿨으면 true
	 */
	boolean ready(long id, String owner, int pageCount, Thumbnail thumbnail) {
		int updated = this.jdbc.sql("""
				UPDATE conversion_jobs
				   SET conversion_state = 'ready', page_count = :pages, error_code = NULL, completed_at = CURRENT_TIMESTAMP(3),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE conversion_job_id = :id AND conversion_state = 'converting' AND lease_owner = :owner
				""").param("pages", pageCount).param("id", id).param("owner", owner).update();
		if (updated == 0) {
			return false;
		}
		this.jdbc.sql("""
				INSERT INTO file_thumbnails (conversion_job_id, variant, storage_key, content_type, size_bytes)
				VALUES (:id, :variant, :key, :type, :size)
				""")
			.param("id", id)
			.param("variant", thumbnail.variant())
			.param("key", thumbnail.storageKey())
			.param("type", thumbnail.contentType())
			.param("size", thumbnail.sizeBytes())
			.update();
		return true;
	}

	/**
	 * converter에 닿지 못했다. 대기열로 돌리고 delay 뒤에 다시 잡게 한다.
	 *
	 * @return lease를 아직 갖고 있어 돌렸으면 true
	 */
	boolean retryLater(long id, String owner, Duration delay, String errorCode) {
		return this.jdbc.sql("""
				UPDATE conversion_jobs
				   SET conversion_state = 'requested', error_code = :code, lease_owner = NULL, lease_expires_at = NULL,
				       next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :delay SECOND)
				 WHERE conversion_job_id = :id AND conversion_state = 'converting' AND lease_owner = :owner
				""").param("code", errorCode).param("delay", delay.toSeconds()).param("id", id).param("owner", owner)
			.update() == 1;
	}

	/** @return lease를 아직 갖고 있어 실패로 닫았으면 true */
	boolean failed(long id, String owner, String errorCode) {
		return this.jdbc.sql("""
				UPDATE conversion_jobs
				   SET conversion_state = 'failed', error_code = :code, completed_at = CURRENT_TIMESTAMP(3),
				       lease_owner = NULL, lease_expires_at = NULL
				 WHERE conversion_job_id = :id AND conversion_state = 'converting' AND lease_owner = :owner
				""").param("code", errorCode).param("id", id).param("owner", owner).update() == 1;
	}

	String state(long id) {
		return this.jdbc.sql("SELECT conversion_state FROM conversion_jobs WHERE conversion_job_id = :id")
			.param("id", id)
			.query(String.class)
			.single();
	}

	/**
	 * lease가 끝난 변환을 정리한다. 시도 수가 남았으면 대기열로 돌리고, 아니면 {@code interrupted}로 닫는다. transaction 안에서 부른다.
	 *
	 * @return 실패로 닫은 rendition의 key. 호출한 쪽이 commit 뒤에 객체를 지운다
	 */
	Released releaseExpired(int maxAttempts, int limit) {
		record Expired(long id, String key, int attempts) {
		}
		List<Expired> expired = this.jdbc.sql("""
				SELECT conversion_job_id, converted_doc_key, attempt_count FROM conversion_jobs
				 WHERE conversion_state = 'converting' AND lease_expires_at <= CURRENT_TIMESTAMP(3)
				 ORDER BY conversion_job_id LIMIT :limit FOR UPDATE SKIP LOCKED
				""").param("limit", limit).query((rs, n) -> new Expired(rs.getLong(1), rs.getString(2), rs.getInt(3))).list();
		int requeued = 0;
		List<String> closed = new ArrayList<>();
		for (Expired job : expired) {
			if (job.attempts() >= maxAttempts) {
				this.jdbc.sql("""
						UPDATE conversion_jobs
						   SET conversion_state = 'failed', error_code = 'interrupted', completed_at = CURRENT_TIMESTAMP(3),
						       lease_owner = NULL, lease_expires_at = NULL
						 WHERE conversion_job_id = :id AND conversion_state = 'converting'
						""").param("id", job.id()).update();
				closed.add(job.key());
			}
			else {
				this.jdbc.sql("""
						UPDATE conversion_jobs SET conversion_state = 'requested', lease_owner = NULL, lease_expires_at = NULL
						 WHERE conversion_job_id = :id AND conversion_state = 'converting'
						""").param("id", job.id()).update();
				requeued++;
			}
		}
		return new Released(requeued, closed);
	}

	/** 파일의 현재 버전에 대한 완료된 rendition의 썸네일. 지운 파일의 썸네일은 없다. */
	Optional<Thumbnail> thumbnail(long fileId, String variant) {
		return this.jdbc.sql("""
				SELECT t.variant, t.storage_key, t.content_type, t.size_bytes
				  FROM file_thumbnails t
				  JOIN conversion_jobs c ON c.conversion_job_id = t.conversion_job_id
				  JOIN files f ON f.file_id = c.file_id
				 WHERE c.file_id = :fileId AND c.file_version_no = f.file_version_no AND c.conversion_state = 'ready'
				   AND f.state = 'active' AND t.variant = :variant
				 ORDER BY c.conversion_job_id DESC LIMIT 1
				""")
			.param("fileId", fileId)
			.param("variant", variant)
			.query((rs, n) -> new Thumbnail(rs.getString(1), rs.getString(2), rs.getString(3), rs.getLong(4)))
			.optional();
	}

	enum State {
		REQUESTED, CONVERTING, READY, FAILED
	}

	/**
	 * @param pageCount 완료됐을 때만 값이 있다
	 * @param errorCode 실패했거나 재시도를 기다릴 때 마지막 실패 코드
	 */
	record Job(long id, State state, String key, Integer pageCount, String errorCode) {
	}

	/** @param attempt 이번 시도가 몇 번째인지. 1부터 센다 */
	record Claimed(long id, long fileId, int versionNo, String key, int attempt) {
	}

	/** @param storageKey content bucket의 key. 서버가 만든다 */
	record Thumbnail(String variant, String storageKey, String contentType, long sizeBytes) {
	}

	record Released(int requeued, List<String> closedKeys) {
	}

}
