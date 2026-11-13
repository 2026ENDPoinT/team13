package com.stackcorp.astra.summary.api;

import java.util.List;

/**
 * @param tools 모델이 부를 수 있는 도구. 비어 있으면 도구 없이 답만 받는다
 */
public record LlmRequest(Purpose purpose, String system, List<LlmMessage> messages, int maxOutputTokens,
		List<LlmTool> tools) {

	/** 요청의 쓰임. 제공자에 보내지 않는다. RECORDING은 학습실 녹음 요약(files의 TextModel port, ADR-0041)이다. */
	public enum Purpose {
		PAGES, STORY, REPAIR, CHAT, RECORDING
	}

	public LlmRequest {
		messages = List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
	}

	public LlmRequest(Purpose purpose, String system, List<LlmMessage> messages, int maxOutputTokens) {
		this(purpose, system, messages, maxOutputTokens, List.of());
	}

}
