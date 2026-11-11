package com.stackcorp.astra.identity.account;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.identity.api.ProfileImages;

/**
 * 아바타 사진 행(profile_images)과 프로필의 사진 참조(user_profiles.profile_image_id)의 상태 전이(ADR-0038). 여러 행을 바꾸는 전이는
 * READ COMMITTED transaction에서 user_profiles 행을 먼저 잠그고 profile_images를 바꾼다. 잠금 충돌로 되돌려지면 정해진 횟수만큼 다시
 * 한다. 한 행만 바꾸는 worker 전이는 lease를 조건으로 한 compare-and-set이다.
 */
@Repository
class ProfileImageRecords implements ProfileImages {

	private static final int ATTEMPTS = 3;

	private static final Logger log = LoggerFactory.getLogger(ProfileImageRecords.class);

	private final JdbcClient jdbc;

	private final TransactionTemplate transaction;

	ProfileImageRecords(JdbcClient jdbc, PlatformTransactionManager transactionManager) {
		this.jdbc = jdbc;
		this.transaction = new TransactionTemplate(transactionManager);
		this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
	}

	@Override
	public long stage(long userId, String storageKey, String sourceKey) {
		return inTransaction(() -> {
			lockProfile(userId);
			this.jdbc.sql("""
					UPDATE profile_images
					   SET image_status = 'DELETED', deleted_at = CURRENT_TIMESTAMP(3), lease_owner = NULL, lease_expires_at = NULL
					 WHERE user_id = :user AND image_status IN ('TEMP', 'FAILED')
					""").param("user", userId).update();
			GeneratedKeyHolder key = new GeneratedKeyHolder();
			this.jdbc.sql("""
					INSERT INTO profile_images (user_id, storage_key, source_key, mime_type, image_status)
					VALUES (:user, :storage, :source, 'image/jpeg', 'TEMP')
					""").param("user", userId).param("storage", storageKey).param("source", sourceKey).update(key,
					"profile_image_id");
			this.jdbc.sql("UPDATE user_profiles SET profile_version = profile_version + 1 WHERE user_id = :user")
				.param("user", userId)
				.update();
			return key.getKey().longValue();
		});
	}

	@Override
	public Optional<Pending> claim(String owner, Duration lease) {
		return inTransaction(() -> {
			Optional<Pending> pending = this.jdbc.sql("""
					SELECT profile_image_id, user_id, storage_key, source_key, attempt_count FROM profile_images
					 WHERE image_status = 'TEMP' AND lease_owner IS NULL
					   AND (next_attempt_at IS NULL OR next_attempt_at <= CURRENT_TIMESTAMP(3))
					 ORDER BY profile_image_id LIMIT 1
					   FOR UPDATE SKIP LOCKED
					""")
				.query((rs, n) -> new Pending(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
						rs.getInt(5) + 1))
				.optional();
			pending.ifPresent((p) -> this.jdbc.sql("""
					UPDATE profile_images
					   SET lease_owner = :owner, lease_expires_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :lease SECOND),
					       attempt_count = attempt_count + 1
					 WHERE profile_image_id = :id
					""").param("owner", owner).param("lease", lease.toSeconds()).param("id", p.imageId()).update());
			return pending;
		});
	}

	@Override
	public boolean activate(long imageId, String owner, String sha256, long sizeBytes, int width, int height) {
		return inTransaction(() -> {
			Optional<Long> userId = this.jdbc.sql("SELECT user_id FROM profile_images WHERE profile_image_id = :id")
				.param("id", imageId)
				.query(Long.class)
				.optional();
			if (userId.isEmpty()) {
				return false;
			}
			lockProfile(userId.get());
			int updated = this.jdbc.sql("""
					UPDATE profile_images
					   SET image_status = 'ACTIVE', content_sha256 = :sha, file_size = :size, image_width = :width,
					       image_height = :height, mime_type = 'image/jpeg', lease_owner = NULL, lease_expires_at = NULL,
					       next_attempt_at = NULL, error_code = NULL
					 WHERE profile_image_id = :id AND image_status = 'TEMP' AND lease_owner = :owner
					""")
				.param("sha", sha256)
				.param("size", sizeBytes)
				.param("width", width)
				.param("height", height)
				.param("id", imageId)
				.param("owner", owner)
				.update();
			if (updated == 0) {
				return false;
			}
			this.jdbc.sql("""
					UPDATE profile_images SET image_status = 'DELETED', deleted_at = CURRENT_TIMESTAMP(3)
					 WHERE user_id = :user AND image_status = 'ACTIVE' AND profile_image_id <> :id
					""").param("user", userId.get()).param("id", imageId).update();
			this.jdbc.sql("""
					UPDATE user_profiles
					   SET avatar_type = 'IMAGE', profile_image_id = :id, profile_version = profile_version + 1
					 WHERE user_id = :user
					""").param("id", imageId).param("user", userId.get()).update();
			return true;
		});
	}

	@Override
	public boolean retryLater(long imageId, String owner, String errorCode, Duration delay) {
		return this.jdbc.sql("""
				UPDATE profile_images
				   SET lease_owner = NULL, lease_expires_at = NULL, error_code = :code,
				       next_attempt_at = DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL :delay SECOND)
				 WHERE profile_image_id = :id AND image_status = 'TEMP' AND lease_owner = :owner
				""").param("code", errorCode).param("delay", delay.toSeconds()).param("id", imageId).param("owner", owner)
			.update() == 1;
	}

	@Override
	public boolean fail(long imageId, String owner, String errorCode) {
		return this.jdbc.sql("""
				UPDATE profile_images
				   SET image_status = 'FAILED', error_code = :code, lease_owner = NULL, lease_expires_at = NULL
				 WHERE profile_image_id = :id AND image_status = 'TEMP' AND lease_owner = :owner
				""").param("code", errorCode).param("id", imageId).param("owner", owner).update() == 1;
	}

	@Override
	public int recoverExpired(int maxAttempts) {
		int closed = this.jdbc.sql("""
				UPDATE profile_images
				   SET image_status = 'FAILED', error_code = 'unavailable', lease_owner = NULL, lease_expires_at = NULL
				 WHERE image_status = 'TEMP' AND lease_expires_at < CURRENT_TIMESTAMP(3) AND attempt_count >= :max
				""").param("max", maxAttempts).update();
		int requeued = this.jdbc.sql("""
				UPDATE profile_images SET lease_owner = NULL, lease_expires_at = NULL
				 WHERE image_status = 'TEMP' AND lease_expires_at < CURRENT_TIMESTAMP(3)
				""").update();
		return closed + requeued;
	}

	@Override
	public Optional<Current> current(long userId) {
		return this.jdbc.sql("""
				SELECT i.profile_image_id, i.storage_key, i.content_sha256
				  FROM user_profiles p JOIN profile_images i ON i.profile_image_id = p.profile_image_id
				 WHERE p.user_id = :user AND p.avatar_type = 'IMAGE' AND i.image_status = 'ACTIVE'
				""").param("user", userId).query((rs, n) -> new Current(rs.getLong(1), rs.getString(2), rs.getString(3)))
			.optional();
	}

	@Override
	public boolean processing(long userId) {
		return this.jdbc.sql("SELECT EXISTS(SELECT 1 FROM profile_images WHERE user_id = :user AND image_status = 'TEMP')")
			.param("user", userId)
			.query(Boolean.class)
			.single();
	}

	@Override
	public List<Discarded> discarded(int limit) {
		return this.jdbc.sql("""
				SELECT profile_image_id, image_status, storage_key, source_key FROM profile_images
				 WHERE (image_status IN ('DELETED', 'FAILED') AND purged_at IS NULL)
				    OR (image_status = 'ACTIVE' AND source_key IS NOT NULL)
				 ORDER BY profile_image_id LIMIT :limit
				""").param("limit", limit).query((rs, n) -> {
			List<String> keys = new ArrayList<>();
			if (!"ACTIVE".equals(rs.getString(2))) {
				keys.add(rs.getString(3));
			}
			if (rs.getString(4) != null) {
				keys.add(rs.getString(4));
			}
			return new Discarded(rs.getLong(1), List.copyOf(keys));
		}).list();
	}

	@Override
	public void purged(long imageId) {
		this.jdbc.sql("""
				UPDATE profile_images
				   SET source_key = NULL,
				       purged_at = IF(image_status IN ('DELETED', 'FAILED'), CURRENT_TIMESTAMP(3), purged_at)
				 WHERE profile_image_id = :id
				""").param("id", imageId).update();
	}

	/** 프로필 행을 잠근다. 없으면 색상형으로 만든다. */
	private void lockProfile(long userId) {
		this.jdbc.sql("""
				INSERT INTO user_profiles (user_id, avatar_type) VALUES (:user, 'COLOR')
				ON DUPLICATE KEY UPDATE user_id = user_id
				""").param("user", userId).update();
		this.jdbc.sql("SELECT profile_id FROM user_profiles WHERE user_id = :user FOR UPDATE")
			.param("user", userId)
			.query(Long.class)
			.single();
	}

	private <T> T inTransaction(Supplier<T> work) {
		for (int attempt = 1;; attempt++) {
			try {
				return this.transaction.execute((tx) -> work.get());
			}
			catch (TransientDataAccessException ex) {
				if (attempt >= ATTEMPTS) {
					throw ex;
				}
				log.info("profile image transaction retried attempt={} error={}", attempt,
						ex.getClass().getSimpleName());
			}
		}
	}

}
