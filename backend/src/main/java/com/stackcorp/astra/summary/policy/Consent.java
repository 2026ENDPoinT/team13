package com.stackcorp.astra.summary.policy;

import java.time.Instant;

/**
 * 유효한 동의 하나. 동의 당시의 제공자 profile 값을 함께 담는다.
 *
 * @param current 동의한 profile이 지금 배포의 profile과 같은지. 다르면 효력이 없고 다시 동의해야 한다
 */
public record Consent(long id, long courseId, long instructorUserId, ProcessingScope scope, String noticeVersion,
		String profileSha256, String providerName, String providerOperator, String processingRegion,
		Integer retentionDays, boolean trainingUse, boolean crossBorderTransfer, boolean riskAcknowledged,
		Instant grantedAt, boolean current) {
}
