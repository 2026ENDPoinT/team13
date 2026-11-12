package com.stackcorp.astra.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.shared.RequestIds;

/**
 * 응답의 X-Request-Id로 그 요청이 남긴 감사 기록을 찾는다.
 */
public final class AuditRows {

	private AuditRows() {
	}

	public static List<Row> of(JdbcClient jdbc, ResultActions result) {
		String requestId = result.andReturn().getResponse().getHeader(RequestIds.HEADER);
		return jdbc.sql("""
				SELECT actor_user_id, session_id, operation, object_type, object_id, course_id, result_code
				  FROM audit_events WHERE request_id = :id
				""")
			.param("id", requestId)
			.query((rs, n) -> new Row(rs.getObject(1, Long.class), rs.getObject(2, Long.class), rs.getString(3),
					rs.getString(4), rs.getObject(5, Long.class), rs.getObject(6, Long.class), rs.getString(7)))
			.list();
	}

	/** 그 요청이 남긴 기록이 하나뿐임을 확인하고 돌려준다. */
	public static Row single(JdbcClient jdbc, ResultActions result) {
		List<Row> rows = of(jdbc, result);
		assertThat(rows).hasSize(1);
		return rows.getFirst();
	}

	public record Row(Long actor, Long session, String operation, String objectType, Long objectId, Long course,
			String result) {
	}

}
