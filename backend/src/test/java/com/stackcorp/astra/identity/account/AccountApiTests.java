package com.stackcorp.astra.identity.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.identity.session.SessionStore;
import com.stackcorp.astra.identity.session.SessionStore.IssuedSession;

import tools.jackson.databind.JsonNode;

/**
 * 내정보, 개인설정, 로그인 session, 등록 기기, 로그인 기록(화면 09, 10, 11, 13). 모든 경로는 요청한 사용자의 것만 읽고 바꾼다.
 * 다른 사람의 ID를 경로에 넣어도 404이고 상대의 상태는 그대로다. 재인증 경로는 {@code identity.stepup.StepUpTests}가 본다.
 */
class AccountApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestData data;

	@Autowired
	SessionStore sessions;

	long me;

	long other;

	ApiClient client;

	ApiClient otherClient;

	@BeforeEach
	void users() {
		this.me = this.data.user("STUDENT");
		this.other = this.data.user("STUDENT");
		this.jdbc.sql("UPDATE users SET department_name = '컴퓨터공학과', grade = 3 WHERE user_id = :id")
			.param("id", this.me)
			.update();
		this.client = new ApiClient(this.mvc, this.data.session(this.me));
		this.otherClient = new ApiClient(this.mvc, this.data.session(this.other));
	}

	@Test
	void showsOwnProfileAndSavesOnlyTheAvatar() throws Exception {
		this.jdbc.sql("""
				INSERT INTO user_contacts (user_id, phone_number, email_address, contact_source)
				VALUES (:id, '010-1234-5678', 'me@school.example', 'ACADEMIC')
				""").param("id", this.me).update();

		this.client.get("/api/me/profile")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.userId").value(this.me))
			.andExpect(jsonPath("$.departmentName").value("컴퓨터공학과"))
			.andExpect(jsonPath("$.grade").value(3))
			.andExpect(jsonPath("$.contact.phone").value("010-1234-5678"))
			.andExpect(jsonPath("$.contact.source").value("academic"))
			.andExpect(jsonPath("$.contact.editable").value(false))
			.andExpect(jsonPath("$.avatar.type").value("color"))
			.andExpect(jsonPath("$.avatar.version").value(0));
		this.otherClient.get("/api/me/profile")
			.andExpect(jsonPath("$.userId").value(this.other))
			.andExpect(jsonPath("$.contact.phone").doesNotExist());

		profile(this.client, "{\"avatarColor\":\"cobalt\",\"shortName\":\"길동\",\"version\":0}").andExpect(status().isOk())
			.andExpect(jsonPath("$.avatar.color").value("cobalt"))
			.andExpect(jsonPath("$.avatar.shortName").value("길동"))
			.andExpect(jsonPath("$.avatar.version").value(1));
		// 다른 창이 먼저 저장했으면 덮어쓰지 않는다.
		profile(this.client, "{\"avatarColor\":\"tomato\",\"shortName\":\"AB\",\"version\":0}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		profile(this.client, "{\"avatarColor\":\"tomato\",\"shortName\":\"AB\",\"version\":3}")
			.andExpect(status().isConflict());
		// 학사 원천 값과 사용자 지정 필드는 받지 않는다.
		profile(this.client, "{\"avatarColor\":\"tomato\",\"shortName\":\"AB\",\"version\":1,\"name\":\"남\"}")
			.andExpect(status().isBadRequest());
		profile(this.client, "{\"avatarColor\":\"tomato\",\"shortName\":\"AB\",\"version\":1,\"userId\":" + this.other
				+ "}")
			.andExpect(status().isBadRequest());

		this.client.get("/api/me/profile")
			.andExpect(jsonPath("$.avatar.color").value("cobalt"))
			.andExpect(jsonPath("$.avatar.version").value(1));
		this.otherClient.get("/api/me/profile").andExpect(jsonPath("$.avatar.version").value(0));
	}

	@Test
	void validatesAvatarColorAndShortName() throws Exception {
		List<String> rejected = List.of("{\"avatarColor\":\"red\",\"shortName\":\"AB\",\"version\":0}",
				"{\"avatarColor\":null,\"shortName\":\"AB\",\"version\":0}",
				"{\"avatarColor\":\"cobalt\",\"shortName\":\"ABC\",\"version\":0}",
				"{\"avatarColor\":\"cobalt\",\"shortName\":\"홍길동\",\"version\":0}",
				"{\"avatarColor\":\"cobalt\",\"shortName\":\"<b\",\"version\":0}",
				"{\"avatarColor\":\"cobalt\",\"shortName\":\"A\\\"\",\"version\":0}",
				"{\"avatarColor\":\"cobalt\",\"shortName\":\"A B\",\"version\":0}",
				"{\"avatarColor\":\"cobalt\",\"shortName\":\"A\\u0007\",\"version\":0}",
				"{\"avatarColor\":\"cobalt\",\"shortName\":\"A\\u202e\",\"version\":0}",
				"{\"avatarColor\":\"cobalt\",\"shortName\":\"AB\"}",
				"{\"avatarColor\":\"cobalt\",\"shortName\":\"AB\",\"version\":-1}");
		for (String body : rejected) {
			profile(this.client, body).andExpect(status().isBadRequest());
		}
		this.client.get("/api/me/profile").andExpect(jsonPath("$.avatar.version").value(0));

		// 글자 단위는 grapheme이다. 이모지 ZWJ 묶음 하나와 글자 하나는 2글자다.
		profile(this.client, "{\"avatarColor\":\"light-purple\",\"shortName\":\"\\ud83d\\udc68\\u200d\\ud83d\\udc69\\u200d\\ud83d\\udc67A\",\"version\":0}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.avatar.version").value(1));
		profile(this.client, "{\"avatarColor\":\"birch-tree\",\"shortName\":\"\",\"version\":1}").andExpect(status().isOk())
			.andExpect(jsonPath("$.avatar.shortName").doesNotExist());
	}

	@Test
	void savesPreferencesFromAllowlistOnly() throws Exception {
		this.client.get("/api/me/preferences")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.language").value("ko"))
			.andExpect(jsonPath("$.timeFormat").value("12"))
			.andExpect(jsonPath("$.theme").value("system"))
			.andExpect(jsonPath("$.doNotDisturb.enabled").value(false))
			.andExpect(jsonPath("$.version").value(0));

		preferences(this.client, preferences("en", "24", "dark", true, "\"22:00\"", "\"07:30\"", 0))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.language").value("en"))
			.andExpect(jsonPath("$.doNotDisturb.start").value("22:00"))
			.andExpect(jsonPath("$.doNotDisturb.end").value("07:30"))
			.andExpect(jsonPath("$.version").value(1));

		List<String> rejected = List.of(preferences("xx", "24", "dark", false, "null", "null", 1),
				preferences("en", "13", "dark", false, "null", "null", 1),
				preferences("en", "24", "<script>", false, "null", "null", 1),
				preferences("en", "24", "dark", true, "\"22:00\"", "null", 1),
				preferences("en", "24", "dark", true, "\"22:00\"", "\"22:00\"", 1),
				preferences("en", "24", "dark", true, "\"24:00\"", "\"07:00\"", 1),
				preferences("en", "24", "dark", true, "\"7:00\"", "\"08:00\"", 1),
				"{\"language\":\"en\",\"timeFormat\":\"24\",\"theme\":\"dark\",\"doNotDisturb\":{\"enabled\":false},\"version\":1,\"userId\":1}");
		for (String body : rejected) {
			preferences(this.client, body).andExpect(status().isBadRequest());
		}
		preferences(this.client, preferences("ko", "12", "light", false, "null", "null", 0))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));

		this.client.get("/api/me/preferences")
			.andExpect(jsonPath("$.language").value("en"))
			.andExpect(jsonPath("$.theme").value("dark"))
			.andExpect(jsonPath("$.version").value(1));
		this.otherClient.get("/api/me/preferences")
			.andExpect(jsonPath("$.language").value("ko"))
			.andExpect(jsonPath("$.version").value(0));
	}

	@Test
	void listsAndRevokesOnlyOwnSessions() throws Exception {
		IssuedSession second = this.sessions.create(this.me, null, "10.0.0.2", "Second browser");
		long current = latestSession(this.me, second.sessionId());
		long otherSession = latestSession(this.other, -1);

		JsonNode list = ApiClient.json(this.client.get("/api/me/sessions").andExpect(status().isOk()));
		assertThat(list.get("sessions")).hasSize(2);
		assertThat(list.get("sessions").findValuesAsString("userAgent")).contains("Second browser");
		assertThat(sessionIds(list)).containsExactlyInAnyOrder(current, second.sessionId()).doesNotContain(otherSession);

		this.client.post("/api/me/sessions/" + otherSession + "/revoke").andExpect(status().isNotFound());
		this.otherClient.get("/api/me").andExpect(status().isOk());
		this.client.post("/api/me/sessions/" + current + "/revoke")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("current_session"));
		this.client.post("/api/me/sessions/" + Long.MAX_VALUE + "/revoke").andExpect(status().isNotFound());

		this.client.post("/api/me/sessions/" + second.sessionId() + "/revoke").andExpect(status().isNoContent());
		// 폐기한 session은 다음 요청부터 인증되지 않는다.
		new ApiClient(this.mvc, second.token()).get("/api/me").andExpect(status().isUnauthorized());
		this.client.post("/api/me/sessions/" + second.sessionId() + "/revoke").andExpect(status().isNotFound());
		this.client.get("/api/me").andExpect(status().isOk());
		assertThat(sessionIds(ApiClient.json(this.client.get("/api/me/sessions")))).containsExactly(current);
		assertThat(this.jdbc.sql("SELECT revoke_reason FROM user_sessions WHERE session_id = :id")
			.param("id", second.sessionId())
			.query(String.class)
			.single()).isEqualTo("USER_REVOKED");
	}

	@Test
	void revokingAllOtherSessionsRequiresRecentReauthentication() throws Exception {
		IssuedSession second = this.sessions.create(this.me, null, "10.0.0.2", "Second browser");

		this.client.post("/api/me/sessions/revoke-others")
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value("step_up_required"));

		new ApiClient(this.mvc, second.token()).get("/api/me").andExpect(status().isOk());
	}

	@Test
	void registersBrowserDeviceAndRevokesItWithItsSessions() throws Exception {
		IssuedSession first = this.sessions.create(this.me, null, "10.0.0.3", "Laptop");
		IssuedSession again = this.sessions.create(this.me, first.deviceUuid(), "10.0.0.3", "Laptop");
		assertThat(again.deviceUuid()).isEqualTo(first.deviceUuid());
		// 다른 사용자의 기기 값을 보내도 그 기기에 묶이지 않는다.
		IssuedSession foreign = this.sessions.create(this.other, first.deviceUuid(), "10.0.0.9", "Laptop");
		assertThat(foreign.deviceUuid()).isNotEqualTo(first.deviceUuid());

		JsonNode devices = ApiClient.json(this.client.get("/api/me/devices").andExpect(status().isOk()));
		assertThat(devices.get("items").findValuesAsString("deviceId")).contains(first.deviceUuid())
			.doesNotContain(foreign.deviceUuid());
		assertThat(devices.get("items").findValuesAsString("clientType")).containsOnly("browser");

		this.client.delete("/api/me/devices/" + foreign.deviceUuid()).andExpect(status().isNotFound());
		this.client.delete("/api/me/devices/not-a-device").andExpect(status().isNotFound());
		new ApiClient(this.mvc, foreign.token()).get("/api/me").andExpect(status().isOk());

		this.client.delete("/api/me/devices/" + first.deviceUuid())
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.revokedSessions").value(2))
			.andExpect(jsonPath("$.currentSessionRevoked").value(false));
		new ApiClient(this.mvc, first.token()).get("/api/me").andExpect(status().isUnauthorized());
		new ApiClient(this.mvc, again.token()).get("/api/me").andExpect(status().isUnauthorized());
		this.client.delete("/api/me/devices/" + first.deviceUuid()).andExpect(status().isNotFound());
		// 폐기한 기기의 값으로 다시 로그인하면 새 기기가 된다.
		assertThat(this.sessions.create(this.me, first.deviceUuid(), "10.0.0.3", "Laptop").deviceUuid())
			.isNotEqualTo(first.deviceUuid());
	}

	@Test
	void revokingTheCurrentDeviceEndsTheCurrentSession() throws Exception {
		JsonNode devices = ApiClient.json(this.client.get("/api/me/devices"));
		String currentDevice = null;
		for (JsonNode device : devices.get("items")) {
			if (device.get("current").asBoolean()) {
				currentDevice = device.get("deviceId").asString();
			}
		}
		assertThat(currentDevice).isNotNull();

		MockHttpServletResponse response = this.client.delete("/api/me/devices/" + currentDevice)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.currentSessionRevoked").value(true))
			.andReturn()
			.getResponse();

		assertThat(response.getHeader("Set-Cookie")).startsWith("ASTRA_SESSION=;").contains("Max-Age=0");
		this.client.get("/api/me").andExpect(status().isUnauthorized());
		this.otherClient.get("/api/me").andExpect(status().isOk());
	}

	@Test
	void pagesOwnLoginHistoryOfTheLastSevenDays() throws Exception {
		for (int i = 0; i < 5; i++) {
			loginEvent(this.me, "SUCCESS", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL " + i + " HOUR)");
		}
		loginEvent(this.me, "FAILURE", "CURRENT_TIMESTAMP(3)");
		loginEvent(this.me, "SUCCESS", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 8 DAY)");
		loginEvent(this.other, "SUCCESS", "CURRENT_TIMESTAMP(3)");

		JsonNode first = ApiClient.json(this.client.get("/api/me/login-events?size=4").andExpect(status().isOk()));
		assertThat(first.get("items")).hasSize(4);
		assertThat(first.get("items").get(0).get("success").asBoolean()).isFalse();
		assertThat(first.get("items").get(0).has("failureReason")).isFalse();
		String cursor = first.get("nextCursor").asString();
		JsonNode second = ApiClient
			.json(this.client.get("/api/me/login-events?size=4&cursor=" + cursor).andExpect(status().isOk()));
		assertThat(second.get("items")).hasSize(2);
		assertThat(second.get("nextCursor").isNull()).isTrue();
		List<String> ips = new ArrayList<>();
		first.get("items").forEach((e) -> ips.add(e.get("ipAddress").asString()));
		second.get("items").forEach((e) -> ips.add(e.get("ipAddress").asString()));
		assertThat(ips).containsOnly("198.51.100." + (this.me % 200));

		this.client.get("/api/me/login-events?size=0").andExpect(status().isBadRequest());
		this.client.get("/api/me/login-events?size=101").andExpect(status().isBadRequest());
		this.client.get("/api/me/login-events?cursor=abc").andExpect(status().isBadRequest());
		this.client.get("/api/me/login-events?cursor=-1").andExpect(status().isBadRequest());
	}

	@Test
	void requiresSessionAndCsrf() throws Exception {
		ApiClient anonymous = new ApiClient(this.mvc, null);
		for (String path : List.of("/api/me/profile", "/api/me/preferences", "/api/me/sessions", "/api/me/devices",
				"/api/me/login-events", "/api/me/learning-auth")) {
			anonymous.get(path).andExpect(status().isUnauthorized());
		}
		this.mvc
			.perform(put("/api/me/preferences").cookie(new jakarta.servlet.http.Cookie("ASTRA_SESSION",
					this.data.session(this.me)))
				.contentType(MediaType.APPLICATION_JSON)
				.content(preferences("en", "24", "dark", false, "null", "null", 0)))
			.andExpect(status().isForbidden());
		this.mvc.perform(get("/api/me/preferences")).andExpect(status().isUnauthorized());
		this.client.get("/api/me/preferences").andExpect(jsonPath("$.version").value(0));
	}

	private ResultActions profile(ApiClient client, String body) throws Exception {
		return client.send(put("/api/me/profile").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions preferences(ApiClient client, String body) throws Exception {
		return client.send(put("/api/me/preferences").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private static String preferences(String language, String time, String theme, boolean dnd, String start,
			String end, int version) {
		return "{\"language\":\"" + language + "\",\"timeFormat\":\"" + time + "\",\"theme\":\"" + theme
				+ "\",\"doNotDisturb\":{\"enabled\":" + dnd + ",\"start\":" + start + ",\"end\":" + end
				+ "},\"version\":" + version + "}";
	}

	private void loginEvent(long userId, String result, String at) {
		this.data.insert("INSERT INTO login_events (user_id, ip_address, client_environment, login_result, logged_in_at) "
				+ "VALUES (:user, :ip, 'PC', :result, " + at + ")",
				Map.of("user", userId, "ip", "198.51.100." + (userId % 200), "result", result));
	}

	private long latestSession(long userId, long except) {
		return this.jdbc.sql("""
				SELECT MAX(session_id) FROM user_sessions WHERE user_id = :user AND session_id <> :except
				""").param("user", userId).param("except", except).query(Long.class).single();
	}

	private static List<Long> sessionIds(JsonNode list) {
		List<Long> ids = new ArrayList<>();
		list.get("sessions").forEach((s) -> ids.add(s.get("sessionId").asLong()));
		return ids;
	}

}
