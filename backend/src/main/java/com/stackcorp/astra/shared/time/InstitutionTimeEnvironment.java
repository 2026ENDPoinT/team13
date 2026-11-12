package com.stackcorp.astra.shared.time;

import java.util.Map;
import java.util.TimeZone;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 설정 파일을 읽은 직후, bean과 로그 설정이 생기기 전에 기관 시간대를 JVM과 DB 연결에 고정한다(ADR-0039). api, worker, 로컬 실행,
 * 테스트가 모두 {@code SpringApplication}으로 뜨므로 이 한 곳이 모든 실행에 적용된다. META-INF/spring.factories에 등록한다.
 * <ul>
 * <li>JVM 기본 시간대를 기관 시간대로 바꾼다. {@code Timestamp}와 {@code Instant} 사이의 변환이 이 값을 쓴다.</li>
 * <li>MySQL Connector/J의 연결 속성으로 연결 시간대를 기관 offset으로 정하고 session에도 보낸다({@code connectionTimeZone},
 * {@code forceConnectionTimeZoneToSession}). pool의 연결마다 {@code SET SESSION time_zone='+09:00'}이 실행되어
 * {@code CURRENT_TIMESTAMP}, {@code CURRENT_DATE}, DATETIME 기본값이 기관 벽시계가 된다. 이름 시간대가 아니라 offset을 보내므로 DB에
 * 시간대 table이 없어도 된다. JDBC URL에 넣지 않으므로 환경 변수로 받은 URL이나 Testcontainers의 URL에도 적용된다.</li>
 * </ul>
 * 연결 속성은 가장 낮은 우선순위로 더한다. 다른 설정이 이를 덮으면 시작할 때 {@link InstitutionTimeCheck}가 거부한다.
 */
class InstitutionTimeEnvironment implements EnvironmentPostProcessor, Ordered {

	static final String CONNECTION_PROPERTIES = "spring.datasource.hikari.data-source-properties.";

	@Override
	public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
		InstitutionTime time = InstitutionTime.from(environment);
		TimeZone.setDefault(TimeZone.getTimeZone(time.zone()));
		environment.getPropertySources()
			.addLast(new MapPropertySource("astraInstitutionTime",
					Map.of(CONNECTION_PROPERTIES + "connectionTimeZone", connectionTimeZone(time),
							CONNECTION_PROPERTIES + "forceConnectionTimeZoneToSession", "true")));
	}

	/** {@code GMT+09:00} 형식. Connector/J가 고정 offset으로 읽어 session에 {@code +09:00}을 보낸다. */
	static String connectionTimeZone(InstitutionTime time) {
		return "GMT" + time.offset().getId().replace("Z", "+00:00");
	}

	// 설정 파일(ConfigDataEnvironmentPostProcessor)과 명령행 인자를 읽은 뒤에 실행한다.
	@Override
	public int getOrder() {
		return Ordered.LOWEST_PRECEDENCE;
	}

}
