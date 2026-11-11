package com.stackcorp.astra.summary.api;

public sealed interface LlmContent {

	record Text(String text) implements LlmContent {
	}

	/** data는 인코딩하지 않은 이미지 바이트다. */
	record Image(String mediaType, byte[] data) implements LlmContent {
	}

}
