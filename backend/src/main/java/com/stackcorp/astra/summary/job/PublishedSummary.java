package com.stackcorp.astra.summary.job;

import java.time.Instant;

/**
 * @param publishedByUserId 게시한 교수자. 학습 대화는 이 교수자의 동의를 따른다(ADR-0032)
 */
public record PublishedSummary(long fileId, long jobId, Long publishedByUserId, Instant publishedAt,
		SummaryResult result) {
}
