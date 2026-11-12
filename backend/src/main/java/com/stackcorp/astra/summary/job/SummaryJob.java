package com.stackcorp.astra.summary.job;

import java.time.Instant;

/**
 * 요약 작업 하나의 상태. stage 값은 queued|pages|story|validate|done이다.
 *
 * @param courseId 요청 경로의 과목. 동의 확인과 사용량 합계의 범위다. V31 이전 작업은 null
 */
public record SummaryJob(long id, long fileId, int fileVersionNo, Long courseId, Long requestedByUserId, Long retryOfJobId,
		JobState state, String stage, int pagesDone, Integer pagesTotal, String modelName, String promptVersion,
		int calls, long inputTokens, long outputTokens, String errorCode, String errorMessage,
		boolean cancelRequested, Instant createdAt, Instant startedAt, Instant finishedAt) {
}
