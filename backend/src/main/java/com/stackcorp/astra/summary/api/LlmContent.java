package com.stackcorp.astra.summary.api;

public sealed interface LlmContent {

	record Text(String text) implements LlmContent {
	}

	/** data는 인코딩하지 않은 이미지 바이트다. */
	record Image(String mediaType, byte[] data) implements LlmContent {
	}

	/**
	 * 모델이 요청한 도구 호출. assistant message에만 둔다.
	 *
	 * @param arguments 모델이 보낸 인자 JSON 문자열 그대로. 신뢰하지 않는 입력이다
	 * @param extraContent 제공자가 호출에 붙인 확장 JSON(예: Gemini의 thought_signature) 그대로. 다음 요청에 같은 값을
	 * 돌려보내야 하는 제공자가 있다. 없으면 null
	 */
	record ToolCall(String id, String name, String arguments, String extraContent) implements LlmContent {

		public ToolCall(String id, String name, String arguments) {
			this(id, name, arguments, null);
		}

	}

	/**
	 * 도구 호출 하나의 결과. tool message에만 둔다.
	 *
	 * @param callId 결과가 답하는 {@link ToolCall#id()}
	 * @param error 도구가 호출을 거절했거나 실패했는지
	 */
	record ToolResult(String callId, String text, boolean error) implements LlmContent {
	}

}
