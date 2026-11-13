package com.stackcorp.astra.summary.api;

import java.time.Duration;
import java.util.List;

/**
 * @param tools 모델이 부를 수 있는 도구. 비어 있으면 도구 없이 답만 받는다
 * @param jsonObject 답을 JSON 객체 하나로 받도록 제공자에 요청한다(Chat Completions {@code response_format} {@code json_object}).
 * 제공자가 무시할 수 있으므로 부르는 쪽은 답을 그대로 검사한다
 * @param timeout 이 요청 하나의 제한 시간(동시 요청 자리를 기다리는 시간 포함). null이면 adapter 설정({@code astra.llm.timeout})이다
 * @param temperature 표본 온도. null이면 adapter 기본값이다
 * @param reasoningEffort 추론 정도(Chat Completions {@code reasoning_effort}, 예: none). null이면 보내지 않는다. 추론 token이 출력
 * 상한을 먹어 답이 잘리지 않게 할 때 쓴다
 */
public record LlmRequest(Purpose purpose, String system, List<LlmMessage> messages, int maxOutputTokens,
		List<LlmTool> tools, boolean jsonObject, Duration timeout, Double temperature, String reasoningEffort) {

	/**
	 * 요청의 쓰임. 제공자에 보내지 않는다. RECORDING은 학습실 녹음 요약(files의 TextModel port, ADR-0041), ASSISTANT는 화면의 AI
	 * 도우미(ADR-0044)다.
	 */
	public enum Purpose {
		PAGES, STORY, REPAIR, CHAT, RECORDING, ASSISTANT
	}

	public LlmRequest {
		messages = List.copyOf(messages);
		tools = tools == null ? List.of() : List.copyOf(tools);
		if (timeout != null && (timeout.isNegative() || timeout.isZero())) {
			throw new IllegalArgumentException("timeout은 0보다 커야 합니다.");
		}
	}

	public LlmRequest(Purpose purpose, String system, List<LlmMessage> messages, int maxOutputTokens,
			List<LlmTool> tools) {
		this(purpose, system, messages, maxOutputTokens, tools, false, null, null, null);
	}

	public LlmRequest(Purpose purpose, String system, List<LlmMessage> messages, int maxOutputTokens) {
		this(purpose, system, messages, maxOutputTokens, List.of());
	}

}
