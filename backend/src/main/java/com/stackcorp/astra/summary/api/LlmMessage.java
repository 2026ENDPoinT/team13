package com.stackcorp.astra.summary.api;

import java.util.List;

public record LlmMessage(Role role, List<LlmContent> content) {

	public enum Role {
		USER, ASSISTANT
	}

	public LlmMessage {
		content = List.copyOf(content);
	}

}
