package com.stackcorp.astra.shared;

import org.springframework.http.server.observation.DefaultServerRequestObservationConvention;
import org.springframework.http.server.observation.ServerRequestObservationContext;

import com.stackcorp.astra.shared.metrics.MetricTags;

import io.micrometer.common.KeyValue;
import io.micrometer.common.KeyValues;

/**
 * HTTP 요청 지표(http.server.requests)와 trace span에 남기는 값(architecture.md 9절, SC-07, ADR-0034).
 * <ul>
 * <li>지표 tag: method, 처리한 경로 pattern(uri), 상태, outcome, 예외 class 이름에 problem JSON의 code를 더한다. 실제 경로 값, 사용자,
 * 과목 ID는 tag에 없다. 인증 단계에서 끊겨 pattern이 없는 요청은 uri가 UNKNOWN이다.</li>
 * <li>span에만 남는 값: 서버가 만든 requestId. 기본으로 남던 실제 경로(http.url)는 뺀다. query string, header, cookie, 본문은 처음부터
 * 남지 않는다.</li>
 * </ul>
 */
class HttpObservationConvention extends DefaultServerRequestObservationConvention {

	/** {@link ProblemRequestIds}가 응답한 problem JSON의 code를 넣는 request attribute. */
	static final String PROBLEM_CODE = HttpObservationConvention.class.getName() + ".code";

	@Override
	public KeyValues getLowCardinalityKeyValues(ServerRequestObservationContext context) {
		Object code = context.getCarrier().getAttribute(PROBLEM_CODE);
		return super.getLowCardinalityKeyValues(context)
			.and(KeyValue.of("code", code instanceof String s ? MetricTags.code(s) : MetricTags.NONE));
	}

	@Override
	public KeyValues getHighCardinalityKeyValues(ServerRequestObservationContext context) {
		Object id = context.getCarrier().getAttribute(RequestIds.ATTRIBUTE);
		return id instanceof String requestId ? KeyValues.of("request.id", requestId) : KeyValues.empty();
	}

}
