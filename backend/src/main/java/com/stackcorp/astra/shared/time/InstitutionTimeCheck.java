package com.stackcorp.astra.shared.time;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * 시작할 때 기관 시간대, JVM 기본 시간대, DB session의 지금 offset이 같은지 본다(ADR-0039). 다르면 시작하지 않는다. RDS parameter
 * group, JDBC URL, JVM option 중 하나가 시간대를 바꿔도 마감 판정이 몇 시간씩 어긋난 채 뜨지 않는다. 세 값은 맞든 틀리든 로그와 오류
 * 문장에 남긴다. 비밀값은 없다. web server와 worker 일정은 모든 bean이 생긴 뒤에 시작하므로 이 판정보다 먼저 요청을 받지 않는다.
 */
@Component
@EnableConfigurationProperties(InstitutionTime.class)
class InstitutionTimeCheck implements SmartInitializingSingleton {

	private static final Logger log = LoggerFactory.getLogger(InstitutionTimeCheck.class);

	private final JdbcClient jdbc;

	private final InstitutionTime time;

	InstitutionTimeCheck(JdbcClient jdbc, InstitutionTime time) {
		this.jdbc = jdbc;
		this.time = time;
	}

	@Override
	public void afterSingletonsInstantiated() {
		Observed observed = verify(this.jdbc, this.time, ZoneId.systemDefault());
		log.info("Time zones agree: {}", observed);
	}

	/**
	 * @param jvmZone JVM 기본 시간대
	 * @throws IllegalStateException 셋 중 하나라도 다르면
	 */
	static Observed verify(JdbcClient jdbc, InstitutionTime time, ZoneId jvmZone) {
		Observed observed = jdbc
			.sql("SELECT @@session.time_zone, TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(), NOW())")
			.query((rs, n) -> new Observed(time.zone(), time.offset(), jvmZone, rs.getString(1),
					ZoneOffset.ofTotalSeconds(rs.getInt(2))))
			.single();
		boolean jvmAgrees = jvmZone.getRules().equals(time.zone().getRules());
		if (!jvmAgrees || !observed.sessionOffset().equals(time.offset())) {
			throw new IllegalStateException("시간대가 맞지 않아 시작하지 않습니다: " + observed
					+ ". JVM 기본 시간대와 DB session offset은 astra.time.zone과 같아야 합니다(ADR-0039).");
		}
		return observed;
	}

	/**
	 * @param sessionTimeZone DB의 {@code @@session.time_zone} 값
	 * @param sessionOffset DB session의 지금 시각과 UTC 시각의 차
	 */
	record Observed(ZoneId zone, ZoneOffset offset, ZoneId jvmZone, String sessionTimeZone, ZoneOffset sessionOffset) {

		@Override
		public String toString() {
			return "astra.time.zone=" + this.zone.getId() + " (" + this.offset.getId() + "), JVM default="
					+ this.jvmZone.getId() + " (" + this.jvmZone.getRules().getOffset(Instant.now()).getId()
					+ "), DB session time_zone=" + this.sessionTimeZone + " (" + this.sessionOffset.getId() + ")";
		}

	}

}
