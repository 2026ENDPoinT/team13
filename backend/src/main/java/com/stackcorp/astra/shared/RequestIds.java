package com.stackcorp.astra.shared;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * 요청마다 서버가 만드는 requestId(SC-09). 응답 header, 로그 MDC, problem JSON, 감사 기록에 같은 값을 쓴다.
 * 들어온 요청의 X-Request-Id는 믿지 않는다. 값은 {@link RequestIdFilter}가 security filter보다 먼저 정한다.
 */
public final class RequestIds {

	/** 응답 header 이름. */
	public static final String HEADER = "X-Request-Id";

	/** 로그 MDC key. 로그 pattern의 {@code %X{requestId}}가 이 값을 쓴다. */
	public static final String MDC_KEY = "requestId";

	static final String ATTRIBUTE = RequestIds.class.getName();

	private RequestIds() {
	}

	/** @return 지금 처리 중인 요청의 ID. 요청 밖(worker 작업 등)이면 null */
	public static String current() {
		return MDC.get(MDC_KEY);
	}

	static String generate() {
		return UUID.randomUUID().toString();
	}

}
