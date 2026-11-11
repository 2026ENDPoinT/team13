package com.stackcorp.astra.shared.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.core.env.Environment;

/**
 * 기관 시간대(ADR-0039). DB의 DATETIME 값과 API의 offset 없는 일시({@code LocalDateTime})는 모두 이 시간대의 벽시계 시각이다.
 * DB session 시간대와 JVM 기본 시간대를 이 값에 맞추고({@link InstitutionTimeEnvironment}), 시작할 때 셋이 같은지 본다
 * ({@link InstitutionTimeCheck}). {@code Instant} 값은 시간대와 관계없는 절대 시각이다.
 * <p>
 * 지금부터 offset이 바뀌지 않는 시간대만 받는다. 서머타임이 있는 시간대는 벽시계 시각이 겹치거나 비는 시간이 생겨 DATETIME으로
 * 순서를 정할 수 없으므로 시작하지 않는다.
 *
 * @param zone IANA 시간대 ID(예: {@code Asia/Seoul}) 또는 고정 offset(예: {@code +09:00})
 */
@ConfigurationProperties("astra.time")
public record InstitutionTime(@DefaultValue("Asia/Seoul") ZoneId zone) {

	public InstitutionTime {
		if (zone.getRules().nextTransition(Instant.now()) != null) {
			throw new IllegalArgumentException("astra.time.zone=" + zone.getId()
					+ "은 앞으로 offset이 바뀌는 시간대입니다. 서머타임이 없는 시간대나 고정 offset만 쓸 수 있습니다(ADR-0039).");
		}
	}

	/** 환경에서 직접 읽는다. bean이 생기기 전(JVM 기본 시간대와 DB 연결 설정을 정할 때)에 쓴다. */
	static InstitutionTime from(Environment environment) {
		return Binder.get(environment).bindOrCreate("astra.time", InstitutionTime.class);
	}

	/** 지금과 앞으로의 UTC offset. 앞으로 바뀌지 않는 시간대만 받으므로 한 값이다. */
	public ZoneOffset offset() {
		return this.zone.getRules().getOffset(Instant.now());
	}

}
