package com.stackcorp.astra.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.api.LlmResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 도우미의 계획(ADR-0044 18~20번). 표본 모드의 계획 문장(오늘 할 일 전부, 오늘 마감, 이번 주, 과목, 유형)은 규칙으로, 그 밖은 모델의
 * intent plan으로 만든다. 단계 순서는 orderPlan(영상 → 과제 → 그 밖, 마감 순), 단계 id는 화면 주소의 n 값, mode는 서비스 영상만 auto다.
 */
class AssistantPlanTests extends IntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final String SCHOOL_URL = "https://eclass.seoultech.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=1&gubun=survey&KJKEY=A1";

	private static final String ACT = "/ilos/cls/st/activity/activity_form.acl?";

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
	void todayAllPlanOrdersStepsAndGivesEachKindItsIdAndMode() throws Exception {
		long report = activity(this.course, "report", "정규화 과제", "CURRENT_TIMESTAMP(3) + INTERVAL 2 DAY");
		long assignment = this.data.insert("INSERT INTO assignments (activity_id) VALUES (:a)", Map.of("a", report));
		long video = activity(this.course, "lecture_weeks", "5주차 강의", "CURRENT_TIMESTAMP(3) + INTERVAL 3 DAY");
		long survey = activity(this.course, "survey", "수업 설문", "CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY");
		activity(this.course, "test", "쪽지 시험", "CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY");
		activity(this.course, "report", "기말 과제", "CURRENT_TIMESTAMP(3) + INTERVAL 10 DAY");
		long schoolReport = school(this.course, "report", "21", "학교 과제", "CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY");
		long schoolSurvey = school(this.course, "survey", "22", "학교 설문", "CURRENT_TIMESTAMP(3) + INTERVAL 2 DAY");
		// 다른 학생의 과목 활동은 계획에 들어가지 않는다.
		long stranger = this.data.user("STUDENT");
		long other = course("비밀 과목", "SEC999");
		this.data.enroll(other, stranger);
		activity(other, "report", "남의 과제", "CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY");

		JsonNode body = ask("오늘 할 일 전부 해줘", null);

		assertThat(body.path("source").stringValue()).isEqualTo("rule");
		assertThat(body.path("intent").stringValue()).isEqualTo("plan");
		JsonNode steps = body.path("plan").path("steps");
		String kj = "&kj=c" + this.course;
		// 영상 → 과제(마감 순) → 그 밖(마감 순). 시험과 사흘 뒤 마감은 빠진다.
		assertThat(field(steps, "id")).containsExactly("srv-" + video, "school-" + schoolReport, "srv-" + assignment,
				"srv-" + survey, "school-" + schoolSurvey);
		assertThat(field(steps, "kind")).containsExactly("video", "report", "report", "survey", "survey");
		assertThat(field(steps, "mode")).containsExactly("auto", "confirm", "confirm", "confirm", "confirm");
		assertThat(field(steps, "href")).containsExactly(ACT + "m=lecture_weeks&n=srv-" + video + kj,
				ACT + "m=report&n=school-" + schoolReport + kj, ACT + "m=report&n=srv-" + assignment + kj,
				ACT + "m=survey&act=" + survey + kj, SCHOOL_URL);
		assertThat(steps.valueStream().map((s) -> s.path("external").booleanValue()).toList())
			.containsExactly(false, false, false, false, true);
		assertThat(steps.path(0).path("course").stringValue()).isEqualTo("데이터베이스");
		assertThat(steps.path(0).path("title").stringValue()).isEqualTo("5주차 강의");
		assertThat(steps.path(0).path("due").stringValue()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}");
		// 예전 화면을 위해 앞 다섯 단계를 actions로도 준다.
		assertThat(body.path("actions").valueStream().map((a) -> a.path("href").stringValue()).toList())
			.isEqualTo(field(steps, "href"));
		assertThat(body.toString()).doesNotContain("남의 과제", "비밀 과목");
		assertThat(this.llm.log).isEmpty();
	}

	@Test
	void planPhrasesNarrowByDayWeekCourseAndType() throws Exception {
		long today = activity(this.course, "report", "오늘 과제",
				"LEAST(CURRENT_TIMESTAMP(3) + INTERVAL 1 HOUR, TIMESTAMP(CURRENT_DATE, '23:59:59.999'))");
		long video = activity(this.course, "lecture_weeks", "강의 하나", "CURRENT_TIMESTAMP(3) + INTERVAL 2 DAY");
		long far = activity(this.course, "report", "먼 과제", "CURRENT_TIMESTAMP(3) + INTERVAL 20 DAY");
		long otherCourse = course("기계학습", "ML101");
		this.data.enroll(otherCourse, this.student);
		long ml = activity(otherCourse, "report", "기학 과제", "CURRENT_TIMESTAMP(3) + INTERVAL 2 DAY");

		// 오늘 마감만.
		assertThat(ids(ask("오늘 마감 전부 해줘", null))).containsExactly("srv-" + today);
		// 이번 주(일요일 시작, 토요일까지) 남은 일. 20일 뒤 과제는 빠진다.
		JsonNode week = ask("이번 주 남은 거 다 처리해줘", null);
		assertThat(ids(week)).doesNotContain("srv-" + far).contains("srv-" + today);
		assertThat(week.path("source").stringValue()).isEqualTo("rule");
		// 과목을 말하면 그 과목의 남은 일 전부(기간 제한 없음).
		assertThat(ids(ask("데이터베이스 전부 해줘", null))).containsExactly("srv-" + video, "srv-" + today, "srv-" + far);
		// 줄임말 과목.
		assertThat(ids(ask("기학 전부 해줘", null))).containsExactly("srv-" + ml);
		// "이 과목"은 지금 과목이다.
		assertThat(ids(ask("이 과목 남은 거 다 해줘", Map.of("courseId", otherCourse)))).containsExactly("srv-" + ml);
		// 유형으로 좁힌다.
		assertThat(ids(ask("강의 다 들을래 전부", null))).containsExactly("srv-" + video);
		// 넣을 일이 없으면 계획 없이 답한다.
		JsonNode none = ask("설문 전부 해줘", null);
		assertThat(none.path("intent").stringValue()).isEqualTo("plan");
		assertThat(none.path("plan").isNull()).isTrue();
		assertThat(none.path("actions").size()).isZero();
		assertThat(this.llm.log).isEmpty();
	}

	@Test
	void planIsCappedAtTwelveStepsAndFiveActions() throws Exception {
		for (int i = 0; i < 14; i++) {
			activity(this.course, "report", "과제 " + i, "CURRENT_TIMESTAMP(3) + INTERVAL " + (i + 1) + " HOUR");
		}
		JsonNode body = ask("오늘 할 일 전부 해줘", null);
		assertThat(body.path("plan").path("steps").size()).isEqualTo(12);
		assertThat(body.path("actions").size()).isEqualTo(5);
		assertThat(body.path("plan").path("steps").path(0).path("title").stringValue()).isEqualTo("과제 0");
	}

	@Test
	void modelPlanKeepsOnlyGivenActivitiesAndOrdersThemLikeRules() throws Exception {
		long report = activity(this.course, "report", "정규화 과제", "CURRENT_TIMESTAMP(3) + INTERVAL 1 DAY");
		long video = activity(this.course, "lecture_weeks", "5주차 강의", "CURRENT_TIMESTAMP(3) + INTERVAL 2 DAY");
		// 모델은 과제를 먼저, 모르는 id와 과목 id를 섞어 고른다. 서버가 버리고 영상 → 과제로 다시 놓는다.
		this.llm.reset((r) -> {
			JsonNode input = JSON.readTree(FakeLlm.transcript(r).split("input:\n", 2)[1]);
			List<String> refs = input.path("items")
				.valueStream()
				.map((row) -> row.path(0).stringValue())
				.toList();
			return new LlmResponse(JSON.writeValueAsString(Map.of("intent", "plan", "targets",
					List.of(refs.get(0), "A99", "C1", refs.get(1)), "say", "두 가지를 차례로 해요.")),
					LlmResponse.StopReason.END, new LlmResponse.Usage(10, 5));
		});

		JsonNode body = ask("디비 숙제랑 강의 차례대로 끝내고 싶어", null);

		assertThat(body.path("source").stringValue()).isEqualTo("model");
		assertThat(body.path("intent").stringValue()).isEqualTo("plan");
		assertThat(body.path("say").stringValue()).isEqualTo("두 가지를 차례로 해요.");
		assertThat(ids(body)).containsExactly("srv-" + video, "srv-" + report);
		assertThat(field(body.path("plan").path("steps"), "mode")).containsExactly("auto", "confirm");
	}

	@Test
	void nonPlanAnswersCarryIntentAndNoPlan() throws Exception {
		JsonNode body = ask("데이터베이스 강의실로 이동", null);
		assertThat(body.path("intent").stringValue()).isEqualTo("course");
		assertThat(body.path("plan").isNull()).isTrue();
		assertThat(body.has("plan")).isTrue();
	}

	private JsonNode ask(String text, Map<String, Object> context) throws Exception {
		Map<String, Object> body = context == null ? Map.of("text", text) : Map.of("text", text, "context", context);
		return ApiClient.json(this.client.postJson("/api/assistant", JSON.writeValueAsString(body))
			.andExpect(status().isOk()));
	}

	private static List<String> ids(JsonNode body) {
		return field(body.path("plan").path("steps"), "id");
	}

	private static List<String> field(JsonNode steps, String name) {
		return steps.valueStream().map((s) -> s.path(name).stringValue()).toList();
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

	private long school(long courseId, String type, String item, String title, String closeAt) {
		return this.data.insert("""
				INSERT INTO school_activities (course_id, school_type, school_item_id, week_no, sort_order, title, close_at,
				                               school_url, synced_at)
				VALUES (:c, :type, :item, 1, 0, :title, %s, :url, CURRENT_TIMESTAMP(3))
				""".formatted(closeAt), Map.of("c", courseId, "type", type, "item", item, "title", title, "url", SCHOOL_URL));
	}

}
