package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class RateLimiterTests {

	@Test
	void rejectsBeyondLimitPerUserAndOperationUntilWindowEnds() {
		AtomicLong now = new AtomicLong();
		RateLimiter limiter = new RateLimiter(now::get);
		Duration window = Duration.ofMinutes(10);
		limiter.check("upload", 1, 2, window);
		limiter.check("upload", 1, 2, window);
		now.set(Duration.ofMinutes(4).toNanos());
		assertThatThrownBy(() -> limiter.check("upload", 1, 2, window)).isInstanceOfSatisfying(RateLimitedException.class,
				(ex) -> assertThat(ex.retryAfter()).isEqualTo(Duration.ofMinutes(6)));
		// 다른 사용자와 다른 연산은 따로 센다.
		limiter.check("upload", 2, 2, window);
		limiter.check("summary-generate", 1, 2, window);
		now.set(window.toNanos());
		limiter.check("upload", 1, 2, window);
	}

}
