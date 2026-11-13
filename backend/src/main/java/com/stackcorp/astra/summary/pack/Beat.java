package com.stackcorp.astra.summary.pack;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 자료 흐름의 한 단계. from과 to는 이 단계가 다루는 첫 쪽과 마지막 쪽이다. tradeoff, extra, bridge는 없을 수 있다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Beat(String id, int from, int to, String title, String problem, String answer, String tradeoff,
		String extra, String bridge) {
}
