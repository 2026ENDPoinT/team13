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
	 */
	record ToolCall(String id, String name, String arguments) implements LlmContent {
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
