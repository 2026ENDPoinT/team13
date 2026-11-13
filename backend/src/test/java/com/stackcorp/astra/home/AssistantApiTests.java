package com.stackcorp.astra.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.api.LlmResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 화면 AI 도우미 {@code POST /api/assistant}(ADR-0044). 모델은 가짜다. 규칙 답, 모델 답, 모델 답 검사, 행동 주소, 본문 상한, 감사 기록을
 * 본다. 전체 한도는 RateLimiterTests, 스위치는 AssistantSwitchTests와 LlmDisabledTests, LlmNotConfiguredTests가 본다.
 */
class AssistantApiTests extends IntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SCHOOL_URL = "https://eclass.seoultech.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=77&gubun=test&KJKEY=A1";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	FakeLlm llm;

	long student;

	long course;

	ApiClient client;

	@BeforeEach
	void setUp() {
		this.llm.reset(FakeLlm::honest);
		this.student = this.data.user("STUDENT");
		this.course = course("데이터베이스", "DB101");
		this.data.enroll(this.course, this.student);
		this.client = new ApiClient(this.mvc, this.data.session(this.student));
	}

	@Test
	void signedOutRequestIsRejected() throws Exception {
		new ApiClient(this.mvc, null).postJson("/api/assistant", "{\"text\":\"할 일 목록 보여줘\"}")
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("unauthorized"));
	}

	@Test
	void roomCommandIsAnsweredByRuleWithoutTheModel() throws Exception {
		ask("데이터베이스 강의실로 이동").andExpect(status().isOk())
			.andExpect(jsonPath("$.source").value("rule"))
			.andExpect(jsonPath("$.actions.length()").value(1))
			.andExpect(jsonPath("$.actions[0].href").value("/ilos/cls/st/submain/submain_form.acl?kj=c" + this.course))
			.andExpect(jsonPath("$.actions[0].external").value(false))
			.andExpect(jsonPath("$.actions[0].label").value("데이터베이스 강의실"));
		// 줄임말(course-match.ts)도 규칙으로 찾는다.
		ask("데베 강의실").andExpect(jsonPath("$.actions[0].href").value("/ilos/cls/st/submain/submain_form.acl?kj=c" + this.course));
		assertThat(this.llm.log).isEmpty();
	}

	@Test
	void sameNamedCoursesAreTellApartByTheirKey() throws Exception {
		long other = course("데이터베이스", "DB102");
		this.data.enroll(other, this.student);

		ask("데이터베이스 강의실로 이동").andExpect(jsonPath("$.source").value("rule"))
			.andExpect(jsonPath("$.actions.length()").value(2));
		ask("데이터베이스 (c" + other + ") 강의실로 이동").andExpect(jsonPath("$.actions.length()").value(1))
			.andExpect(jsonPath("$.actions[0].href").value("/ilos/cls/st/submain/submain_form.acl?kj=c" + other));
	}

	@Test
	void todoListResolvesEachItemKindToWhereTheScreenOpensIt() throws Exception {
		long report = activity(this.course, "report", "과제 1", "CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY");
		long video = activity(this.course, "lecture_weeks", "3주차 강의", "CURRENT_TIMESTAMP(3) + INTERVAL 2 DAY");
		long quizActivity = activity(this.course, "test", "중간 퀴즈", "CURRENT_TIMESTAMP(3) + INTERVAL 3 DAY");
		long quiz = this.data.insert("INSERT INTO quizzes (activity_id) VALUES (:a)", Map.of("a", quizActivity));
		school(this.course, "77", "학교 시험", "CURRENT_TIMESTAMP(3) + INTERVAL 4 DAY");

		JsonNode body = json(ask("할 일 목록 보여줘").andExpect(status().isOk()));

		assertThat(body.path("source").stringValue()).isEqualTo("rule");
		assertThat(hrefs(body)).containsExactly(
				"/ilos/cls/st/activity/activity_form.acl?m=report&act=" + report + "&kj=c" + this.course,
				"/ilos/cls/st/activity/activity_form.acl?m=lecture_weeks&n=srv-" + video + "&kj=c" + this.course,
				"/ilos/cls/st/activity/activity_form.acl?m=test&n=srv-" + quiz + "&kj=c" + this.course, SCHOOL_URL);
		assertThat(body.path("actions").path(3).path("external").booleanValue()).isTrue();
		assertThat(body.path("actions").path(0).path("external").booleanValue()).isFalse();
		assertThat(body.path("actions").path(0).path("label").stringValue()).isEqualTo("데이터베이스 · 과제 1");
		assertThat(this.llm.log).isEmpty();
	}

	@Test
	void todayDeadlinesAndNextItemAreRules() throws Exception {
		// 오늘 안에 마감한다(자정 직전에 돌아도 오늘이다).
		activity(this.course, "report", "오늘 과제",
				"LEAST(CURRENT_TIMESTAMP(3) + INTERVAL 1 HOUR, TIMESTAMP(CURRENT_DATE, '23:59:59.999'))");
		ask("다음 할 일 열어줘").andExpect(jsonPath("$.source").value("rule"))
			.andExpect(jsonPath("$.actions[0].label").value("데이터베이스 · 오늘 과제"));
		ask("데이터베이스 다음 할 일").andExpect(jsonPath("$.source").value("rule"))
			.andExpect(jsonPath("$.actions.length()").value(1));
		ask("오늘 마감 뭐 있어?").andExpect(jsonPath("$.source").value("rule"));
		assertThat(this.llm.log).isEmpty();
	}

	@Test
	void modelAnswerKeepsOnlyTheIdsItWasGivenAndSeesOnlyTheCallersData() throws Exception {
		long report = activity(this.course, "report", "정규화 과제", "CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY");
		long stranger = this.data.user("STUDENT");
		long strangerCourse = course("비밀 과목", "SEC999");
		this.data.enroll(strangerCourse, stranger);
		activity(strangerCourse, "report", "남의 과제", "CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY");
		this.llm.reset((r) -> reply("{\"intent\":\"open\",\"targets\":[\"A1\",\"A99\",\"C7\",\"" + report
				+ "\"],\"say\":\"정규화 과제를 열게요.\"}"));

		JsonNode body = json(ask("디비 숙제 어디 있지", Map.of("courseId", this.course)).andExpect(status().isOk()));

		assertThat(body.path("source").stringValue()).isEqualTo("model");
		assertThat(body.path("say").stringValue()).isEqualTo("정규화 과제를 열게요.");
		assertThat(hrefs(body))
			.containsExactly("/ilos/cls/st/activity/activity_form.acl?m=report&act=" + report + "&kj=c" + this.course);
		LlmRequest sent = this.llm.log.getFirst();
		assertThat(sent.purpose()).isEqualTo(LlmRequest.Purpose.ASSISTANT);
		assertThat(sent.jsonObject()).isTrue();
		assertThat(sent.timeout()).hasSeconds(20);
		String transcript = FakeLlm.transcript(sent);
		assertThat(transcript).contains("정규화 과제", "데이터베이스", "\"course\":\"C1\"")
			.doesNotContain("비밀 과목", "남의 과제", "SEC999");
	}

	@Test
	void modelAnswerOutsideTheContractIsRejected() throws Exception {
		activity(this.course, "report", "과제", "CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY");
		for (String answer : List.of("{\"intent\":\"submit\",\"targets\":[\"A1\"],\"say\":\"제출할게요\"}",
				"{\"intent\":\"open\",\"targets\":[\"A1\"],\"say\":\"" + "가".repeat(301) + "\"}",
				"{\"intent\":\"open\",\"targets\":\"A1\",\"say\":\"열게요\"}", "그냥 글")) {
			this.llm.reset((r) -> reply(answer));
			ask("디비 숙제 어디 있지").andExpect(status().isBadGateway())
				.andExpect(jsonPath("$.code").value("assistant_invalid_response"));
		}
		// 목록에 없는 id만 고르면 열 곳 없이 답한다.
		this.llm.reset((r) -> reply("```json\n{\"intent\":\"open\",\"targets\":[\"A42\"],\"say\":\"열게요\"}\n```"));
		ask("디비 숙제 어디 있지").andExpect(status().isOk())
			.andExpect(jsonPath("$.actions.length()").value(0))
			.andExpect(jsonPath("$.say").value("맞는 항목이 없습니다"));
	}

	@Test
	void modelFailureAndTimeoutHaveStableCodes() throws Exception {
		this.llm.reset((r) -> {
			throw new LlmException(LlmException.Code.SERVER, "down");
		});
		ask("디비 숙제 어디 있지").andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("assistant_upstream_error"));
		this.llm.reset((r) -> {
			throw new LlmException(LlmException.Code.NETWORK, "slow", new TimeoutException());
		});
		ask("디비 숙제 어디 있지").andExpect(status().isGatewayTimeout())
			.andExpect(jsonPath("$.code").value("assistant_timeout"));
	}

	@Test
	void requestShapeAndBodySizeAreChecked() throws Exception {
		this.client.postJson("/api/assistant", "{\"text\":\"" + "가".repeat(6000) + "\"}")
			.andExpect(status().isContentTooLarge())
			.andExpect(jsonPath("$.code").value("body_too_large"));
		for (String invalid : List.of("{\"text\":\"안녕\",\"courses\":[]}", "{\"text\":\"  \"}",
				"{\"text\":\"" + "가".repeat(501) + "\"}", "{\"text\":\"안녕\",\"context\":{\"courseId\":\"c1\"}}",
				"{\"text\":\"안녕\",\"context\":{\"history\":[{\"text\":\"a\"}]}}", "not json")) {
			this.client.postJson("/api/assistant", invalid)
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_request"));
		}
		this.client
			.send(MockMvcRequestBuilders.post("/api/assistant").contentType(MediaType.TEXT_PLAIN).content("{\"text\":\"안녕\"}"))
			.andExpect(status().isBadRequest());
	}

	// 사용자별 모델 한도(기본 분당 10)와 요청 한도(기본 분당 30, 규칙 답 포함). 이 사용자만의 counter라 다른 테스트와 섞이지 않는다.
	@Test
	void perUserLimitsAnswer429WithRetryAfter() throws Exception {
		this.llm.reset((r) -> reply("{\"intent\":\"none\",\"targets\":[],\"say\":\"없어요\"}"));
		for (int i = 0; i < 10; i++) {
			ask("아무거나 찾아줘").andExpect(status().isOk()).andExpect(jsonPath("$.source").value("model"));
		}
		limited(ask("아무거나 찾아줘"));
		// 규칙 답은 모델 한도를 쓰지 않는다.
		for (int i = 0; i < 19; i++) {
			ask("할 일 목록 보여줘").andExpect(status().isOk()).andExpect(jsonPath("$.source").value("rule"));
		}
		// 31번째 요청은 요청 한도에 걸린다.
		limited(ask("할 일 목록 보여줘"));
		assertThat(this.llm.log).hasSize(10);
	}

	private static void limited(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		result.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("rate_limited"))
			.andExpect(header().exists("Retry-After"));
	}

	@Test
	void eachRequestLeavesOneAuditEventWithoutText() throws Exception {
		this.llm.reset((r) -> reply("{\"intent\":\"clarify\",\"targets\":[],\"say\":\"무엇을 열까요?\"}"));
		ask("비밀문장 알려줘").andExpect(status().isOk()).andExpect(jsonPath("$.say").value("무엇을 열까요?"));
		ask("할 일 목록 보여줘").andExpect(status().isOk());

		List<Map<String, Object>> events = this.jdbc
			.sql("SELECT * FROM audit_events WHERE actor_user_id = :u AND operation = 'assistant.ask' ORDER BY audit_event_id")
			.param("u", this.student)
			.query()
			.listOfRows();
		assertThat(events).hasSize(2);
		assertThat(events).allSatisfy((e) -> {
			assertThat(e.get("result_code")).isEqualTo("ok");
			assertThat(e.get("object_type")).isNull();
			assertThat(e.get("session_id")).isNotNull();
			assertThat(e.values().toString()).doesNotContain("비밀문장", "무엇을 열까요", "할 일");
		});
	}

	private org.springframework.test.web.servlet.ResultActions ask(String text) throws Exception {
		return ask(text, null);
	}

	private org.springframework.test.web.servlet.ResultActions ask(String text, Map<String, Object> context)
			throws Exception {
		Map<String, Object> body = context == null ? Map.of("text", text) : Map.of("text", text, "context", context);
		return this.client.postJson("/api/assistant", JSON.writeValueAsString(body));
	}

	private long course(String name, String code) {
		return this.data.insert(
				"INSERT INTO courses (course_type, course_name, course_code) VALUES ('정규', :name, :code)",
				Map.of("name", name, "code", code));
	}

	private long activity(long courseId, String type, String title, String closeAt) {
		long week = this.data.insert("INSERT INTO course_weeks (course_id, week_no) VALUES (:c, :n)",
				Map.of("c", courseId, "n", TestData.weekNo()));
		return this.data.insert("""
				INSERT INTO learning_activities (course_id, course_week_id, activity_type, title, published_yn, close_at)
				VALUES (:c, :w, :type, :title, 1, %s)
				""".formatted(closeAt), Map.of("c", courseId, "w", week, "type", type, "title", title));
	}

	private void school(long courseId, String item, String title, String closeAt) {
		this.data.insert("""
				INSERT INTO school_activities (course_id, school_type, school_item_id, week_no, sort_order, title, close_at,
				                               school_url, synced_at)
				VALUES (:c, 'test', :item, 1, 0, :title, %s, :url, CURRENT_TIMESTAMP(3))
				""".formatted(closeAt), Map.of("c", courseId, "item", item, "title", title, "url", SCHOOL_URL));
	}

	private static LlmResponse reply(String text) {
		return new LlmResponse(text, LlmResponse.StopReason.END, new LlmResponse.Usage(100, 20));
	}

	private static JsonNode json(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		return ApiClient.json(result);
	}

	private static List<String> hrefs(JsonNode body) {
		return body.path("actions").valueStream().map((a) -> a.path("href").stringValue()).toList();
	}

}
