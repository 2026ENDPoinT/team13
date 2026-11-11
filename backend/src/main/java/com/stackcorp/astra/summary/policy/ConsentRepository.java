package com.stackcorp.astra.summary.policy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

/**
 * llm_consents. 유효한 동의는 (과목, 교수자, 범위)마다 하나이고 DB 유일 제약이 지킨다. 철회는 revoked_at을 채우는 조건부 UPDATE다.
 */
@Repository
class ConsentRepository {

	private static final String COLUMNS = """
			llm_consent_id, course_id, instructor_user_id, processing_scope, notice_version, profile_sha256, provider_name,
			provider_operator, processing_region, retention_days, training_use, cross_border_transfer, risk_acknowledged,
			granted_at""";

	private final JdbcClient jdbc;

	ConsentRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 유효한 동의를 잠가 읽는다. */
	Optional<Row> lockActive(long courseId, long userId, ProcessingScope scope) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM llm_consents
				 WHERE course_id = :course AND instructor_user_id = :user AND processing_scope = :scope
				   AND revoked_at IS NULL
				 FOR UPDATE
				""")
			.param("course", courseId)
			.param("user", userId)
			.param("scope", scope.value())
			.query(ConsentRepository::map)
			.optional();
	}

	List<Row> active(long courseId, long userId) {
		return this.jdbc.sql("SELECT " + COLUMNS + """
				 FROM llm_consents
				 WHERE course_id = :course AND instructor_user_id = :user AND revoked_at IS NULL
				 ORDER BY processing_scope
				""").param("course", courseId).param("user", userId).query(ConsentRepository::map).list();
	}

	/** 지금 profile 지문으로 한 유효한 동의가 있는지. */
	boolean hasCurrent(long courseId, long userId, ProcessingScope scope, String profileSha256) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM llm_consents
				 WHERE course_id = :course AND instructor_user_id = :user AND processing_scope = :scope
				   AND revoked_at IS NULL AND profile_sha256 = :sha
				""")
			.param("course", courseId)
			.param("user", userId)
			.param("scope", scope.value())
			.param("sha", profileSha256)
			.query(Long.class)
			.single() > 0;
	}

	/**
	 * @throws org.springframework.dao.DuplicateKeyException 같은 범위에 유효한 동의가 이미 있을 때
	 */
	long insert(long courseId, long userId, ProcessingScope scope, ProviderProfile profile, boolean acknowledged) {
		GeneratedKeyHolder key = new GeneratedKeyHolder();
		this.jdbc.sql("""
				INSERT INTO llm_consents (course_id, instructor_user_id, processing_scope, notice_version, profile_sha256,
				                          provider_name, provider_operator, processing_region, retention_days, training_use,
				                          cross_border_transfer, risk_acknowledged)
				VALUES (:course, :user, :scope, :notice, :sha, :name, :operator, :region, :retention, :training, :transfer,
				        :ack)
				""")
			.param("course", courseId)
			.param("user", userId)
			.param("scope", scope.value())
			.param("notice", profile.noticeVersion())
			.param("sha", profile.digest())
			.param("name", profile.providerName())
			.param("operator", profile.providerOperator())
			.param("region", profile.processingRegion())
			.param("retention", profile.retentionDays())
			.param("training", profile.trainingUse())
			.param("transfer", profile.crossBorderTransfer())
			.param("ack", acknowledged)
			.update(key, "llm_consent_id");
		return key.getKey().longValue();
	}

	Row find(long consentId) {
		return this.jdbc.sql("SELECT " + COLUMNS + " FROM llm_consents WHERE llm_consent_id = :id")
			.param("id", consentId)
			.query(ConsentRepository::map)
			.single();
	}

	/** @return 유효하던 동의를 철회했으면 true */
	boolean revoke(long consentId) {
		return this.jdbc.sql("""
				UPDATE llm_consents SET revoked_at = CURRENT_TIMESTAMP(3)
				 WHERE llm_consent_id = :id AND revoked_at IS NULL
				""").param("id", consentId).update() == 1;
	}

	record Row(long id, long courseId, long userId, ProcessingScope scope, String noticeVersion, String profileSha256,
			String providerName, String providerOperator, String processingRegion, Integer retentionDays,
			boolean trainingUse, boolean crossBorderTransfer, boolean acknowledged, Instant grantedAt) {

		Consent toConsent(String currentSha256) {
			return new Consent(this.id, this.courseId, this.userId, this.scope, this.noticeVersion, this.profileSha256,
					this.providerName, this.providerOperator, this.processingRegion, this.retentionDays,
					this.trainingUse, this.crossBorderTransfer, this.acknowledged, this.grantedAt,
					this.profileSha256.equals(currentSha256));
		}

	}

	private static Row map(ResultSet rs, int n) throws SQLException {
		return new Row(rs.getLong("llm_consent_id"), rs.getLong("course_id"), rs.getLong("instructor_user_id"),
				ProcessingScope.of(rs.getString("processing_scope")).orElseThrow(), rs.getString("notice_version"),
				rs.getString("profile_sha256"), rs.getString("provider_name"), rs.getString("provider_operator"),
				rs.getString("processing_region"), rs.getObject("retention_days", Integer.class),
				rs.getBoolean("training_use"), rs.getBoolean("cross_border_transfer"),
				rs.getBoolean("risk_acknowledged"), rs.getTimestamp("granted_at").toInstant());
	}

}
