package com.stackcorp.astra.summary.api;

import java.util.Map;

/**
 * 모델에 알리는 도구 하나.
 *
 * @param parameters 인자의 JSON Schema
 */
public record LlmTool(String name, String description, Map<String, Object> parameters) {

	public LlmTool {
		parameters = Map.copyOf(parameters);
	}

}
