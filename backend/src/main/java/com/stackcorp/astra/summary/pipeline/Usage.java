package com.stackcorp.astra.summary.pipeline;

/**
 * 작업 하나가 쓴 모델 호출 수와 token 수.
 */
public record Usage(long inputTokens, long outputTokens, int calls) {
}
