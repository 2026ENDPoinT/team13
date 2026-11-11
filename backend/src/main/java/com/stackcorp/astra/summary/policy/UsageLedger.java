package com.stackcorp.astra.summary.policy;

import java.util.EnumMap;
import java.util.Map;

import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * 과목별 일일 LLM 사용량(llm_usage_daily). 사용자와 본문 없이 수만 더한다. 대화를 지워도 사용량은 남아 일일 예산을 우회할 수
 * 없다. 날짜는 DB 시계의 날짜다.
 */
@Repository
public class UsageLedger {

	private final JdbcClient jdbc;

	UsageLedger(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** 끝난 작업이나 질문 하나의 사용량을 더한다. 과목이 없는 작업(V31 이전)은 더하지 않는다. */
	public void add(Long courseId, ProcessingScope scope, int calls, long inputTokens, long outputTokens) {
		if (courseId == null) {
			return;
		}
		this.jdbc.sql("""
				INSERT INTO llm_usage_daily (course_id, usage_date, processing_scope, request_count, call_count,
				                             input_tokens, output_tokens)
				VALUES (:course, CURRENT_DATE, :scope, 1, :calls, :input, :output) AS new
				ON DUPLICATE KEY UPDATE request_count = llm_usage_daily.request_count + 1,
				                        call_count = llm_usage_daily.call_count + new.call_count,
				                        input_tokens = llm_usage_daily.input_tokens + new.input_tokens,
				                        output_tokens = llm_usage_daily.output_tokens + new.output_tokens
				""")
			.param("course", courseId)
			.param("scope", scope.value())
			.param("calls", calls)
			.param("input", inputTokens)
			.param("output", outputTokens)
			.update();
	}

	/** 오늘 그 과목, 그 범위가 쓴 token 수(입력과 출력의 합). */
	public long todayTokens(long courseId, ProcessingScope scope) {
		return this.jdbc.sql("""
				SELECT IFNULL(SUM(input_tokens + output_tokens), 0) FROM llm_usage_daily
				 WHERE course_id = :course AND usage_date = CURRENT_DATE AND processing_scope = :scope
				""").param("course", courseId).param("scope", scope.value()).query(Long.class).single();
	}

	/** 과목의 범위별 누적 사용량. 쓰지 않은 범위는 0이다. */
	public Map<ProcessingScope, Total> totals(long courseId) {
		Map<ProcessingScope, Total> totals = new EnumMap<>(ProcessingScope.class);
		for (ProcessingScope scope : ProcessingScope.values()) {
			totals.put(scope, new Total(0, 0, 0, 0));
		}
		this.jdbc.sql("""
				SELECT processing_scope, SUM(request_count), SUM(call_count), SUM(input_tokens), SUM(output_tokens)
				  FROM llm_usage_daily WHERE course_id = :course GROUP BY processing_scope
				""").param("course", courseId).query((RowCallbackHandler) (rs) -> {
			Total total = new Total(rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5));
			ProcessingScope.of(rs.getString(1)).ifPresent((s) -> totals.put(s, total));
		});
		return totals;
	}

	public record Total(long requests, long calls, long inputTokens, long outputTokens) {
	}

}
