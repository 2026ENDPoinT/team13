package com.stackcorp.astra.summary.api;

public record LlmResponse(String text, StopReason stopReason, Usage usage) {

	public enum StopReason {
		END, MAX_TOKENS, OTHER
	}

	public record Usage(long inputTokens, long outputTokens) {

		public static final Usage NONE = new Usage(0, 0);

	}

}
