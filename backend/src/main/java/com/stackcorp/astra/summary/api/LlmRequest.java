package com.stackcorp.astra.summary.api;

import java.util.List;

public record LlmRequest(Purpose purpose, String system, List<LlmMessage> messages, int maxOutputTokens) {

	public enum Purpose {
		PAGES, STORY, REPAIR
	}

	public LlmRequest {
		messages = List.copyOf(messages);
	}

}
