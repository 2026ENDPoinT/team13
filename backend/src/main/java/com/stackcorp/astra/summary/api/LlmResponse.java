package com.stackcorp.astra.summary.api;

import java.util.List;

/**
 * @param text 모델이 쓴 글. 도구만 부른 응답이면 빈 문자열일 수 있다
 * @param toolCalls 모델이 요청한 도구 호출. 도구를 주지 않은 요청에서는 비어 있다
 */
public record LlmResponse(String text, StopReason stopReason, Usage usage, List<LlmContent.ToolCall> toolCalls) {

	public enum StopReason {
		END, MAX_TOKENS, TOOL_USE, OTHER
	}

	public record Usage(long inputTokens, long outputTokens) {

		public static final Usage NONE = new Usage(0, 0);

	}

	public LlmResponse {
		toolCalls = toolCalls == null ? List.of() : List.copyOf(toolCalls);
	}

	public LlmResponse(String text, StopReason stopReason, Usage usage) {
		this(text, stopReason, usage, List.of());
	}

}
