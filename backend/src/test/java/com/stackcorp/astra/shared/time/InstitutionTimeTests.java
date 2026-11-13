package com.stackcorp.astra.shared.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;

/**
 * 시간 모델(ADR-0039). 테스트의 MySQL container는 기본값(UTC) 그대로다. 그래도 서버 시각, 마감 판정, 날짜 경계는 기관 시간대
 * (기본 Asia/Seoul)의 벽시계를 따르고, {@code Instant}는 참 시각이다. 기대값은 JVM 기본 시간대가 아니라 기관 시간대를 직접 써서 만든다.
 */
class InstitutionTimeTests extends IntegrationTest {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	InstitutionTime time;

	@Autowired
	JdbcConnectionDetails database;

	@Autowired
	PlatformTransactionManager transactions;

	@Test
	void serverTimeIsTheInstitutionWallClockAndClientsLearnTheZone() throws Exception {
		ApiClient client = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		LocalDateTime serverTime = LocalDateTime
			.parse(ApiClient.json(client.get("/api/me/calendar").andExpect(status().isOk())).get("serverTime").asString());
		assertThat(Duration.between(LocalDateTime.now(SEOUL), serverTime).abs()).isLessThan(Duration.ofSeconds(10));
		this.mvc.perform(MockMvcRequestBuilders.get("/api/auth/methods"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.timeZone").value("Asia/Seoul"));
	}

	@Test
	void deadlinesEnteredAsInstitutionWallClockOpenAndCloseOnTime() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long student = this.data.user("STUDENT");
		this.data.instructor(course, prof);
		this.data.enroll(course, student);
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		ApiClient learner = new ApiClient(this.mvc, this.data.session(student));
		LocalDateTime now = LocalDateTime.now(SEOUL).truncatedTo(ChronoUnit.SECONDS);

		long open = assignment(professor, course, now.minusMinutes(10), now.plusMinutes(1));
		long closed = assignment(professor, course, now.minusMinutes(10), now.minusMinutes(1));
		learner.get("/api/courses/" + course + "/assignments/" + open).andExpect(jsonPath("$.phase").value("open"));
		learner.get("/api/courses/" + course + "/assignments/" + closed).andExpect(jsonPath("$.phase").value("closed"));

		// Z가 붙은 값(UTC)을 기관 시각으로 바꿔 읽지 않는다.
		professor.postJson("/api/courses/" + course + "/assignments",
				"{\"weekNo\":1,\"title\":\"UTC\",\"closeAt\":\"2999-01-01T00:00:00Z\",\"maxScore\":1}")
			.andExpect(status().isBadRequest());
	}

	@Test
	void instantFieldsAreTrueInstantsStoredAsInstitutionWallClock() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		JsonNode material = ApiClient.json(new ApiClient(this.mvc, this.data.session(prof))
			.postJson("/api/courses/" + course + "/materials", "{\"weekNo\":1,\"title\":\"자료\",\"published\":false}")
			.andExpect(status().isCreated()));
		Instant createdAt = Instant.parse(material.get("createdAt").asString());
		assertThat(Duration.between(Instant.now(), createdAt).abs()).isLessThan(Duration.ofSeconds(10));
		LocalDateTime stored = this.jdbc.sql("SELECT created_at FROM learning_activities WHERE activity_id = :id")
			.param("id", material.get("activityId").asLong())
			.query(LocalDateTime.class)
			.single();
		assertThat(stored).isEqualTo(LocalDateTime.ofInstant(createdAt, SEOUL));
	}

	@Test
	void todayChangesAtInstitutionMidnight() {
		long user = this.data.user("STUDENT");
		ApiClient client = new ApiClient(this.mvc, this.data.session(user));
		LocalDate today = LocalDate.now(SEOUL);
		Instant midnight = today.atStartOfDay(SEOUL).toInstant();
		assertThat(calendarDayAt(client, midnight.minusSeconds(1))).isEqualTo(today.minusDays(1));
		assertThat(calendarDayAt(client, midnight)).isEqualTo(today);
	}

	/**
	 * DB 시계를 그 순간으로 옮겨 캘린더의 오늘과 {@code CURRENT_DATE}를 읽는다. 같은 thread의 transaction에 묶인 연결 하나에서만
	 * 시계를 옮기고, 끝나면 되돌린 뒤 rollback한다.
	 */
	private LocalDate calendarDayAt(ApiClient client, Instant at) {
		return new TransactionTemplate(this.transactions).execute((tx) -> {
			tx.setRollbackOnly();
			this.jdbc.sql("SET TIMESTAMP = :at").param("at", at.getEpochSecond()).update();
			try {
				LocalDate today = LocalDate.parse(ApiClient.json(client.get("/api/me/calendar").andExpect(status().isOk()))
					.get("today")
					.asString());
				assertThat(this.jdbc.sql("SELECT CURRENT_DATE").query(LocalDate.class).single()).isEqualTo(today);
				return today;
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			finally {
				this.jdbc.sql("SET TIMESTAMP = DEFAULT").update();
			}
		});
	}

	@Test
	void startupCheckRejectsASessionOrJvmInAnotherZone() {
		assertThat(InstitutionTimeCheck.verify(this.jdbc, this.time, SEOUL).sessionOffset())
			.isEqualTo(ZoneOffset.ofHours(9));

		// 시간대 연결 속성이 없는 연결은 container 기본값(UTC)이다. RDS parameter group이나 JDBC URL이 바뀐 경우와 같다.
		JdbcClient plain = JdbcClient.create(new DriverManagerDataSource(this.database.getJdbcUrl(),
				this.database.getUsername(), this.database.getPassword()));
		assertThatIllegalStateException().isThrownBy(() -> InstitutionTimeCheck.verify(plain, this.time, SEOUL))
			.withMessageContaining("astra.time.zone=Asia/Seoul (+09:00)")
			.withMessageContaining("JVM default=Asia/Seoul (+09:00)")
			.withMessageContaining("(Z)");

		assertThatIllegalStateException()
			.isThrownBy(() -> InstitutionTimeCheck.verify(this.jdbc, this.time, ZoneOffset.UTC))
			.withMessageContaining("JVM default=Z (Z)");
	}

	@Test
	void zonesWhoseOffsetStillChangesAreRejected() {
		assertThatIllegalArgumentException().isThrownBy(() -> new InstitutionTime(ZoneId.of("Europe/Berlin")))
			.withMessageContaining("Europe/Berlin");
		assertThat(new InstitutionTime(ZoneId.of("+09:00")).offset()).isEqualTo(ZoneOffset.ofHours(9));
		assertThat(InstitutionTimeEnvironment.connectionTimeZone(new InstitutionTime(ZoneOffset.UTC)))
			.isEqualTo("GMT+00:00");
	}

	private static long assignment(ApiClient professor, long course, LocalDateTime openAt, LocalDateTime closeAt)
			throws Exception {
		String body = "{\"weekNo\":1,\"title\":\"마감\",\"openAt\":\"%s\",\"closeAt\":\"%s\",\"maxScore\":1}"
			.formatted(openAt, closeAt);
		return ApiClient.json(professor.postJson("/api/courses/" + course + "/assignments", body)
			.andExpect(status().isCreated())).get("assignment").get("assignmentId").asLong();
	}

}
