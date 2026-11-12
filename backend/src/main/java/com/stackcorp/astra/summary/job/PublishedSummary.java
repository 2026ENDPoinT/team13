package com.stackcorp.astra.summary.job;

import java.time.Instant;

public record PublishedSummary(long fileId, long jobId, Instant publishedAt, SummaryResult result) {
}
