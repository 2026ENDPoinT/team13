package com.stackcorp.astra.shared;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.stereotype.Component;

/**
 * 사용자와 연산 단위의 요청 제한(SC-09). IP로 제한하지 않는다. 교내 NAT 하나가 학교 전체를 막기 때문이다.
 * 고정 구간 counter를 이 process의 메모리에 둔다. api 인스턴스가 2대가 되면 저장소를 Valkey로 바꾼다(architecture.md 8절).
 */
@Component
public class RateLimiter {

	private static final int SWEEP_AT = 10_000;

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	private final LongSupplier nanoTime;

	public RateLimiter() {
		this(System::nanoTime);
	}

	RateLimiter(LongSupplier nanoTime) {
		this.nanoTime = nanoTime;
	}

	/**
	 * @param operation 연산 이름. 예: upload
	 * @throws RateLimitedException window 안에 limit번을 넘었을 때
	 */
	public void check(String operation, long userId, int limit, Duration window) {
		long now = this.nanoTime.getAsLong();
		long length = window.toNanos();
		if (this.windows.size() > SWEEP_AT) {
			this.windows.values().removeIf((w) -> now - w.startedAt >= w.length);
		}
		Window current = this.windows.compute(operation + ":" + userId, (key, w) -> {
			if (w == null || now - w.startedAt >= w.length) {
				return new Window(now, length, 1);
			}
			return new Window(w.startedAt, w.length, w.count + 1);
		});
		if (current.count > limit) {
			long remaining = current.length - (now - current.startedAt);
			throw new RateLimitedException(Duration.ofNanos(Math.max(remaining, 1_000_000_000L)));
		}
	}

	private record Window(long startedAt, long length, int count) {
	}

}
