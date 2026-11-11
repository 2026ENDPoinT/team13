package com.stackcorp.astra.summary.job;

import java.time.Instant;

/**
 * 요약 작업 하나의 상태. stage 값은 queued|pages|story|validate|done이다.
 */
public record SummaryJob(long id, long fileId, int fileVersionNo, Long requestedByUserId, Long retryOfJobId,
		JobState state, String stage, int pagesDone, Integer pagesTotal, String modelName, String promptVersion,
		int calls, long inputTokens, long outputTokens, String errorCode, String errorMessage,
		boolean cancelRequested, Instant createdAt, Instant startedAt, Instant finishedAt) {
}
