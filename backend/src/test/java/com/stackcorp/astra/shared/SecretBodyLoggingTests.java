package com.stackcorp.astra.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

/**
 * 장애를 볼 때 흔히 켜는 Spring MVC 본문 로그(TRACE면 자르지 않고 전부 남긴다)에도 비밀번호, TOTP, 학습 인증 코드, 스마트출석 PIN과
 * QR token이 나가지 않는다(SC-07). 로그는 CloudWatch로 나간다.
 */
@ExtendWith(OutputCaptureExtension.class)
class SecretBodyLoggingTests extends IntegrationTest {

	private static final String BODY_LOGGER = "org.springframework.web.servlet.mvc.method.annotation";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	LoggingSystem logging;

	@BeforeEach
	void traceBodies() {
		this.logging.setLogLevel(BODY_LOGGER, LogLevel.TRACE);
	}

	@AfterEach
	void restore() {
		this.logging.setLogLevel(BODY_LOGGER, null);
	}

	@Test
	void requestAndResponseBodiesCarryingSecretsAreNotLogged(CapturedOutput output) throws Exception {
		ApiClient anonymous = new ApiClient(this.mvc, null);
		anonymous.postJson("/api/auth/break-glass",
				"{\"username\":\"nobody\",\"password\":\"pw-secret-4711\",\"code\":\"471147\"}")
			.andExpect(status().isUnauthorized());

		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(course, prof);
		this.data.enroll(course, stu);
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		ApiClient student = new ApiClient(this.mvc, this.data.session(stu));
		student.postJson("/api/me/learning-auth/challenges/" + "A".repeat(43) + "/verify", "{\"code\":\"code-4712\"}")
			.andExpect(status().isNotFound());

		long session = ApiClient.json(professor
			.postJson("/api/courses/" + course + "/attendance-sessions",
					"{\"weekNo\":1,\"sessionNo\":1,\"classDate\":\"2026-09-17\",\"startTime\":\"14:00\",\"endTime\":\"15:00\",\"method\":\"manual\"}")
			.andExpect(status().isCreated())).get("sessionId").asLong();
		String pin = ApiClient.json(professor
			.postJson("/api/courses/" + course + "/attendance-sessions/" + session + "/smart-rounds",
					"{\"method\":\"pin\"}")
			.andExpect(status().isCreated())).get("pin").asString();
		long qrSession = ApiClient.json(professor
			.postJson("/api/courses/" + course + "/attendance-sessions",
					"{\"weekNo\":1,\"sessionNo\":2,\"classDate\":\"2026-09-17\",\"startTime\":\"15:00\",\"endTime\":\"16:00\",\"method\":\"manual\"}")
			.andExpect(status().isCreated())).get("sessionId").asLong();
		String qrToken = ApiClient.json(professor
			.postJson("/api/courses/" + course + "/attendance-sessions/" + qrSession + "/smart-rounds",
					"{\"method\":\"qr\"}")
			.andExpect(status().isCreated())).get("qrToken").asString();
		professor.postJson("/api/courses/" + course + "/smart-rounds/1/check",
				"{\"pin\":\"930471\",\"qrToken\":\"qr-secret-4713\"}")
			.andExpect(status().isNotFound());

		// 본문 로그가 실제로 켜져 있었다.
		assertThat(output.getAll()).contains("BreakGlassRequest[]", "VerifyChallenge[]", "CheckBody[]", "RoundView[");
		assertThat(output.getAll()).doesNotContain("pw-secret-4711", "471147", "code-4712", "930471", "qr-secret-4713",
				pin, qrToken);
	}

}
