package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class RateLimiterTests {

	@Test
	void rejectsBeyondLimitPerUserAndOperationUntilWindowEnds() {
		AtomicLong now = new AtomicLong();
		SimpleMeterRegistry meters = new SimpleMeterRegistry();
		RateLimiter limiter = new RateLimiter(now::get, meters);
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
		// 거부한 수는 연산별로만 센다. 사용자는 tag에 없다.
		assertThat(meters.get("astra.ratelimit.rejections").counter().getId().getTags())
			.extracting((tag) -> tag.getKey() + "=" + tag.getValue())
			.containsExactly("operation=upload");
		assertThat(meters.get("astra.ratelimit.rejections").tag("operation", "upload").counter().count()).isEqualTo(1);
	}

}
