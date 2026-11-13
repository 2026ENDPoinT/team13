package com.stackcorp.astra.shared;

import java.time.Duration;

public class RateLimitedException extends RuntimeException {

	private final Duration retryAfter;

	RateLimitedException(Duration retryAfter) {
		super("요청이 너무 많습니다.");
		this.retryAfter = retryAfter;
	}

	public Duration retryAfter() {
		return this.retryAfter;
	}

}
