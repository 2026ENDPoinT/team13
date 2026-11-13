package com.stackcorp.astra.summary.chat;

import java.time.Instant;

/**
 * 질문 하나와 그 답의 상태. 본문은 object storage에 있다.
 */
public record Exchange(long id, long chatId, ExchangeState state, String errorCode, int calls, int toolCalls,
		long inputTokens, long outputTokens, Instant createdAt, Instant startedAt, Instant finishedAt) {
}
