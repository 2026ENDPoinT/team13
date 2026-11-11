package com.stackcorp.astra.shared;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.stackcorp.astra.shared.metrics.MetricTags;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 사용자와 연산 단위의 요청 제한(SC-09). IP로 제한하지 않는다. 교내 NAT 하나가 학교 전체를 막기 때문이다. 로그인 전이라 사용자가
 * 없는 break-glass 로그인만 예외로 브라우저 주소 단위 제한({@link #checkAddress})을 쓴다.
 * 고정 구간 counter를 이 process의 메모리에 둔다. api 인스턴스가 2대가 되면 저장소를 Valkey로 바꾼다(architecture.md 8절).
 * 거부한 요청 수를 연산별로 센다(astra.ratelimit.rejections). 사용자는 tag에 넣지 않는다.
 */
@Component
public class RateLimiter {

	private static final int SWEEP_AT = 10_000;

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	private final LongSupplier nanoTime;

	private final MeterRegistry meters;

	@Autowired
	public RateLimiter(MeterRegistry meters) {
		this(System::nanoTime, meters);
	}

	RateLimiter(LongSupplier nanoTime, MeterRegistry meters) {
		this.nanoTime = nanoTime;
		this.meters = meters;
	}

	/**
	 * @param operation 연산 이름. 예: upload
	 * @throws RateLimitedException window 안에 limit번을 넘었을 때
	 */
	public void check(String operation, long userId, int limit, Duration window) {
		count(operation, operation + ":" + userId, limit, window);
	}

	/**
	 * 로그인 전이라 사용자가 없는 명령의 주소 단위 제한. 같은 NAT를 쓰는 사용자끼리 서로 막을 수 있으므로 사용자 단위 제한을 쓸 수 있는
	 * 명령에는 쓰지 않는다.
	 * @param address 브라우저 주소(identity의 ClientAddresses)
	 * @throws RateLimitedException window 안에 limit번을 넘었을 때
	 */
	public void checkAddress(String operation, String address, int limit, Duration window) {
		count(operation, operation + "@" + address, limit, window);
	}

	private void count(String operation, String subject, int limit, Duration window) {
		long now = this.nanoTime.getAsLong();
		long length = window.toNanos();
		if (this.windows.size() > SWEEP_AT) {
			this.windows.values().removeIf((w) -> now - w.startedAt >= w.length);
		}
		Window current = this.windows.compute(subject, (key, w) -> {
			if (w == null || now - w.startedAt >= w.length) {
				return new Window(now, length, 1);
			}
			return new Window(w.startedAt, w.length, w.count + 1);
		});
		if (current.count > limit) {
			Counter.builder("astra.ratelimit.rejections")
				.description("사용자·연산 요청 제한으로 거부한 요청 수")
				.tag("operation", MetricTags.code(operation))
				.register(this.meters)
				.increment();
			long remaining = current.length - (now - current.startedAt);
			throw new RateLimitedException(Duration.ofNanos(Math.max(remaining, 1_000_000_000L)));
		}
	}

	private record Window(long startedAt, long length, int count) {
	}

}
