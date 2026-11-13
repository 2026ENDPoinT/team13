package com.stackcorp.astra.summary.api;

import java.util.List;

/**
 * USER는 text와 image, ASSISTANT는 text와 {@link LlmContent.ToolCall}, TOOL은 {@link LlmContent.ToolResult}만 담는다.
 */
public record LlmMessage(Role role, List<LlmContent> content) {

	public enum Role {
		USER, ASSISTANT, TOOL
	}

	public LlmMessage {
		content = List.copyOf(content);
	}

}
