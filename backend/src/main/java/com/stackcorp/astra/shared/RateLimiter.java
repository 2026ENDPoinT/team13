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
 * 없는 로그인만 예외로 브라우저 주소 단위 제한을 쓴다: break-glass 로그인({@link #checkAddress}), 학교 비밀번호 로그인의 실패
 * ({@link #checkFailures}, 학번 단위 한도와 함께). 주소는 identity의 ClientAddresses가 정한다.
 * 고정 구간 counter를 이 process의 메모리에 둔다. api 인스턴스가 2대가 되면 저장소를 Valkey로 바꾼다(architecture.md 8절).
 * 거부한 요청 수를 연산별로 센다(astra.ratelimit.rejections). 사용자는 tag에 넣지 않는다.
 */
@Component
public class RateLimiter {

	private static final int SWEEP_AT = 10_000;

	private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

	// 다음에 끝난 window를 치울 크기. 치운 뒤에도 살아 있는 window가 많으면 그 두 배로 올려, 증가마다 map 전체를 훑지 않게 한다.
	private volatile int sweepAt = SWEEP_AT;

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
	 * 모든 사용자의 요청을 합해 세는 제한. 사용자 단위 제한({@link #check})과 함께 써서 여럿이 나눠 쓰는 외부 자원(예: 모델 제공자의
	 * 요청 한도)을 지킨다.
	 * @throws RateLimitedException window 안에 모두 합해 limit번을 넘었을 때
	 */
	public void checkTotal(String operation, int limit, Duration window) {
		count(operation, operation + "#total", limit, window);
	}

	/**
	 * 로그인 전이라 사용자가 없는 명령의 주소 단위 제한. 같은 NAT를 쓰는 사용자끼리 서로 막을 수 있으므로 사용자 단위 제한을 쓸 수 있는
	 * 명령에는 쓰지 않는다.
	 * @param address 브라우저 주소의 제한 key(identity의 ClientAddresses.limitKey. IPv6는 /64)
	 * @throws RateLimitedException window 안에 limit번을 넘었을 때
	 */
	public void checkAddress(String operation, String address, int limit, Duration window) {
		count(operation, operation + "@" + address, limit, window);
	}

	/**
	 * 실패만 세는 명령의 앞 단계. window 안에 이미 limit번 실패했으면 거부한다. 세지 않는다. 실패는 {@link #recordFailure}로 센다.
	 * 성공한 요청이 한도를 쓰지 않아, 같은 주소(NAT, 앞단 proxy)를 쓰는 정상 사용자끼리 서로 막지 않는다.
	 * @param subject 세는 대상. 예: 브라우저 주소의 제한 key, 학번
	 * @throws RateLimitedException 이미 limit번 실패했을 때
	 */
	public void checkFailures(String operation, String subject, int limit) {
		long now = this.nanoTime.getAsLong();
		Window current = this.windows.get(operation + "!" + subject);
		if (current != null && now - current.startedAt < current.length && current.count >= limit) {
			reject(operation, current, now);
		}
	}

	/** {@link #checkFailures}가 보는 실패를 하나 센다. 거부하지 않는다. */
	public void recordFailure(String operation, String subject, Duration window) {
		increment(operation + "!" + subject, window);
	}

	private void count(String operation, String subject, int limit, Duration window) {
		Window current = increment(subject, window);
		if (current.count > limit) {
			reject(operation, current, this.nanoTime.getAsLong());
		}
	}

	private Window increment(String subject, Duration window) {
		long now = this.nanoTime.getAsLong();
		long length = window.toNanos();
		if (this.windows.size() > this.sweepAt) {
			this.windows.values().removeIf((w) -> now - w.startedAt >= w.length);
			this.sweepAt = Math.max(SWEEP_AT, this.windows.size() * 2);
		}
		return this.windows.compute(subject, (key, w) -> {
			if (w == null || now - w.startedAt >= w.length) {
				return new Window(now, length, 1);
			}
			return new Window(w.startedAt, w.length, w.count + 1);
		});
	}

	private void reject(String operation, Window current, long now) {
		Counter.builder("astra.ratelimit.rejections")
			.description("사용자·연산 요청 제한으로 거부한 요청 수")
			.tag("operation", MetricTags.code(operation))
			.register(this.meters)
			.increment();
		long remaining = current.length - (now - current.startedAt);
		throw new RateLimitedException(Duration.ofNanos(Math.max(remaining, 1_000_000_000L)));
	}

	private record Window(long startedAt, long length, int count) {
	}

}
