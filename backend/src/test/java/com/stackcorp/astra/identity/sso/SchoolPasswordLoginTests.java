package com.stackcorp.astra.identity.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import jakarta.servlet.http.Cookie;

/**
 * 학번·학교 e-Class 비밀번호 로그인. 같은 process의 가짜 학교 e-Class(ILOS 로그인 화면과 같은 흐름)를 주소로 설정한 context에서
 * 실행한다.
 */
@ExtendWith(OutputCaptureExtension.class)
class SchoolPasswordLoginTests extends IntegrationTest {

	static final String PASSWORD = "school-secret!42";

	static final FakeSchool SCHOOL = FakeSchool.start();

	@DynamicPropertySource
	static void school(DynamicPropertyRegistry registry) {
		registry.add("astra.eclass-login.base-url", SCHOOL::origin);
	}

	@AfterAll
	static void stop() {
		SCHOOL.server.stop(0);
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void up() {
		SCHOOL.down = false;
		SCHOOL.name = "이진우";
		SCHOOL.courseCards = FakeSchool.COURSES;
	}

	@Test
	void firstLoginCreatesTheStudentFromTheSchoolProfile(CapturedOutput output) throws Exception {
		String studentId = studentId();

		MvcResult result = this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent()).andReturn();

		Cookie session = result.getResponse().getCookie("ASTRA_SESSION");
		assertThat(session).isNotNull();
		assertThat(session.isHttpOnly()).isTrue();
		assertThat(result.getResponse().getCookie("XSRF-TOKEN")).isNotNull();
		this.mvc.perform(get("/api/me").cookie(session))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("이진우"))
			.andExpect(jsonPath("$.role").value("STUDENT"));
		Map<String, Object> row = this.jdbc
			.sql("SELECT user_role, department_name, grade, account_status FROM users WHERE external_ref = :ref")
			.param("ref", studentId)
			.query()
			.singleRow();
		assertThat(row).containsEntry("user_role", "STUDENT")
			.containsEntry("department_name", "컴퓨터공학과")
			.containsEntry("account_status", "ACTIVE");
		assertThat(((Number) row.get("grade")).intValue()).isEqualTo(3);
		assertThat(output.getAll()).doesNotContain(PASSWORD);
	}

	@Test
	void laterLoginReusesTheUserAndRefreshesTheProfile() throws Exception {
		String studentId = studentId();
		this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent());
		SCHOOL.name = "이진우2";

		this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent());

		assertThat(this.jdbc.sql("SELECT user_name FROM users WHERE external_ref = :ref")
			.param("ref", studentId)
			.query(String.class)
			.list()).containsExactly("이진우2");
	}

	@Test
	void schoolCoursesReachTheStudentsCourseApiAndRefreshWithoutDuplicates() throws Exception {
		String studentId = studentId();
		Cookie session = this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent())
			.andReturn().getResponse().getCookie("ASTRA_SESSION");
		var courses = ApiClient.json(this.mvc.perform(get("/api/me/courses").cookie(session)));
		assertThat(courses.size()).isEqualTo(2);
		var regular = courses.findValues("name");
		assertThat(regular.stream().map(node -> node.asText()).toList()).contains("학교 선형대수", "학교 안전교육");
		var course = java.util.stream.StreamSupport.stream(courses.spliterator(), false)
			.filter(node -> node.path("name").asText().equals("학교 선형대수")).findFirst().orElseThrow();
		assertThat(course.path("code").asText()).isEqualTo("109829");
		assertThat(course.path("section").asText()).isEqualTo("11001");
		assertThat(course.path("instructors").get(0).asText()).isEqualTo("담당교수");
		assertThat(course.path("credit").decimalValue()).isEqualByComparingTo("3");
		assertThat(course.path("schedules").get(0).path("dayOfWeek").asInt()).isEqualTo(4);
		assertThat(course.path("schedules").get(0).path("periodEnd").asInt()).isEqualTo(3);
		assertThat(course.path("coverTemplate").asText()).isEqualTo("dynamic_13");
		var special = java.util.stream.StreamSupport.stream(courses.spliterator(), false)
			.filter(node -> node.path("type").asText().equals("NONREGULAR")).findFirst().orElseThrow();
		assertThat(special.path("learningPeriod").asText()).isEqualTo("2026.05.13~2026.12.21");
		assertThat(special.path("lectureType").asText()).isEqualTo("이러닝수업");

		this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/me/courses").cookie(session)).andExpect(jsonPath("$.length()").value(2));
		SCHOOL.courseCards = "";
		this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/me/courses").cookie(session)).andExpect(jsonPath("$.length()").value(2));
		SCHOOL.courseCards = FakeSchool.COURSES.substring(0, FakeSchool.COURSES.indexOf("<a class=\"sub_open\" kj=\"비"));
		this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent());
		this.mvc.perform(get("/api/me/courses").cookie(session))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("학교 선형대수"));
	}

	@Test
	void signInImportsEachCoursesSchoolRoomIntoTheWeekListOnce() throws Exception {
		String studentId = studentId();
		int before = SCHOOL.roomReads;
		Cookie session = this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent())
			.andReturn().getResponse().getCookie("ASTRA_SESSION");
		assertThat(SCHOOL.roomReads - before).isEqualTo(2);
		var courses = ApiClient.json(this.mvc.perform(get("/api/me/courses").cookie(session)));
		long regular = java.util.stream.StreamSupport.stream(courses.spliterator(), false)
			.filter(node -> node.path("type").asText().equals("REGULAR")).findFirst().orElseThrow()
			.path("courseId").asLong();

		var weeks = ApiClient.json(this.mvc.perform(get("/api/courses/" + regular + "/weeks").cookie(session))).path("weeks");

		assertThat(weeks.size()).isEqualTo(2);
		assertThat(weeks.get(0).path("startsOn").asText()).isEqualTo("2026-09-01");
		var video = weeks.get(0).path("activities").get(0);
		assertThat(video.path("activityId").asText()).startsWith("school-");
		assertThat(video.path("origin").asText()).isEqualTo("school");
		assertThat(video.path("type").asText()).isEqualTo("video");
		assertThat(video.path("schoolType").asText()).isEqualTo("lecture_weeks");
		assertThat(video.path("title").asText()).isEqualTo("학교 1주 영상");
		assertThat(video.path("phase").asText()).isEqualTo("closed");
		assertThat(video.path("done").asBoolean()).isTrue();
		assertThat(video.path("attendance").asText()).isEqualTo("1 주 1차시");
		assertThat(video.path("learningTimeSec").asInt()).isEqualTo(1505);
		assertThat(video.path("schoolUrl").asText()).isEqualTo(SCHOOL.origin()
				+ "/ilos/mp/todo_list_connect.acl?SEQ=501&gubun=lecture_weeks&KJKEY=A2026310982911001");
		var report = weeks.get(1).path("activities").get(0);
		assertThat(report.path("type").asText()).isEqualTo("report");
		assertThat(report.path("schoolKind").asText()).isEqualTo("report1");
		assertThat(report.path("closeAt").asText()).startsWith("2026-12-31T23:59");
		assertThat(report.path("done").asBoolean()).isFalse();

		this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent());

		assertThat(SCHOOL.roomReads - before).isEqualTo(2);
	}

	@Test
	void wrongPasswordIsRejectedWithoutCreatingAUser() throws Exception {
		String studentId = studentId();

		this.mvc.perform(login(studentId, "wrong"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("invalid_credentials"));

		assertThat(users(studentId)).isZero();
	}

	@Test
	void inactiveAccountCannotSignIn() throws Exception {
		String studentId = studentId();
		this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent());
		this.jdbc.sql("UPDATE users SET account_status = 'SUSPENDED' WHERE external_ref = :ref")
			.param("ref", studentId)
			.update();

		this.mvc.perform(login(studentId, PASSWORD))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value("rejected"));
	}

	@Test
	void unreachableSchoolIsBadGateway() throws Exception {
		SCHOOL.down = true;
		String studentId = studentId();

		this.mvc.perform(login(studentId, PASSWORD))
			.andExpect(status().isBadGateway())
			.andExpect(jsonPath("$.code").value("school_unavailable"));

		assertThat(users(studentId)).isZero();
	}

	@Test
	void successfulLoginsNeverUseUpTheLimit() throws Exception {
		String studentId = studentId();

		for (int i = 0; i <= SchoolPasswordController.STUDENT_FAILURES + 1; i++) {
			this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent());
		}
	}

	@Test
	void repeatedWrongPasswordsStopReachingTheSchool() throws Exception {
		String studentId = studentId();
		for (int i = 0; i < SchoolPasswordController.STUDENT_FAILURES; i++) {
			this.mvc.perform(login(studentId, "wrong")).andExpect(status().isUnauthorized());
		}
		int before = SCHOOL.logins;

		this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isTooManyRequests());

		assertThat(SCHOOL.logins).isEqualTo(before);
		this.mvc.perform(login(studentId(), PASSWORD)).andExpect(status().isNoContent());
	}

	@Test
	void malformedStudentIdNeverReachesTheSchool() throws Exception {
		int before = SCHOOL.logins;

		this.mvc.perform(login("../x", PASSWORD)).andExpect(status().isBadRequest());
		this.mvc.perform(login(studentId(), "")).andExpect(status().isBadRequest());

		assertThat(SCHOOL.logins).isEqualTo(before);
	}

	@Test
	void advertisesTheMethod() throws Exception {
		assertThat(ApiClient.json(this.mvc.perform(get("/api/auth/methods"))).path("methods").toString())
			.contains("eclassPassword");
	}

	private org.springframework.test.web.servlet.RequestBuilder login(String studentId, String password)
			throws Exception {
		Cookie csrf = this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return post(SchoolPasswordController.PATH).cookie(csrf)
			.header("X-XSRF-TOKEN", csrf.getValue())
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"studentId\":\"" + studentId + "\",\"password\":\"" + password + "\"}");
	}

	private int users(String studentId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM users WHERE external_ref = :ref")
			.param("ref", studentId)
			.query(Integer.class)
			.single();
	}

	private static String studentId() {
		return Integer.toString(ThreadLocalRandom.current().nextInt(10_000_000, 99_999_999));
	}

	/** 서울과기대 e-Class(ILOS)의 로그인 흐름을 줄인 가짜 서버. 비밀번호가 {@link #PASSWORD}인 학번은 모두 로그인된다. */
	static final class FakeSchool {

		final HttpServer server;

		volatile boolean down;

		volatile String name = "이진우";

		volatile String courseCards = COURSES;

		volatile int logins;

		volatile int roomReads;

		volatile String boundKey;

		private FakeSchool(HttpServer server) {
			this.server = server;
		}

		String origin() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		static FakeSchool start() {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				FakeSchool school = new FakeSchool(server);
				server.createContext("/", school::handle);
				server.start();
				return school;
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}

		private void handle(HttpExchange exchange) throws IOException {
			try (exchange) {
				if (this.down) {
					send(exchange, 503, "down");
					return;
				}
				String path = exchange.getRequestURI().getPath();
				boolean signedIn = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie")).contains("auth=ok");
				switch (path) {
					case "/ilos/index.acl" -> redirect(exchange, "/ilos/main/member/login_form.acl");
					case "/ilos/main/member/login_form.acl" -> {
						exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=anon; Path=/");
						send(exchange, 200, LOGIN_FORM);
					}
					case "/ilos/lo/login.acl" -> {
						this.logins++;
						Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(),
								StandardCharsets.UTF_8));
						if (PASSWORD.equals(form.get("usr_pwd")) && "/ilos/main/main_form.acl".equals(form.get("returnURL"))) {
							exchange.getResponseHeaders().add("Set-Cookie", "auth=ok; Path=/");
							send(exchange, 200, "<script>top.location.href=\"/ilos/main/main_form.acl\";</script>");
						}
						else {
							// 학교는 틀린 비밀번호에 로그인 화면이 아니라 부모 문서의 오류 문구를 켜는 script만 준다.
							send(exchange, 200, """
									<script>$("#usr_id, #usr_pwd", parent.document).parents("label").addClass("error");
									$("#err_message", parent.document).text("Login information is incorrect.").show();</script>
									""");
						}
					}
					case "/ilos/main/main_form.acl" -> {
						if (signedIn) {
							send(exchange, 200, "<div class=\"user_info\"><span class=\"user_name\">" + this.name
									+ "</span></div><a href=\"/ilos/lo/logout.acl\">로그아웃</a>" + this.courseCards);
						}
						else {
							redirect(exchange, "/ilos/main/member/login_form.acl");
						}
					}
					case "/ilos/mp/myinfo_pop_form.acl" -> send(exchange, 200, signedIn
							? "<div class=\"user_info\"><span class=\"user_name\">" + this.name
									+ "</span></div><div class=\"mp_myinfo_user_info\"><p class=\"dept_grade\">컴퓨터공학과 3Grade</p></div>"
							: LOGIN_FORM);
					case "/ilos/cls/st/co/eclass_room2.acl" -> {
						Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(),
								StandardCharsets.UTF_8));
						this.boundKey = signedIn ? form.get("KJKEY") : null;
						send(exchange, 200, signedIn ? "{\"isError\":false,\"returnURL\":\"/ilos/cls/st/submain/submain_form.acl\"}"
								: "{\"isError\":true,\"message\":\"로그인\"}");
					}
					case "/ilos/cls/st/submain/submain_form.acl" -> {
						this.roomReads++;
						send(exchange, 200, signedIn ? room(this.boundKey) : LOGIN_FORM);
					}
					default -> send(exchange, 404, "");
				}
			}
		}

		private static final String COURSES = """
				<a class="sub_open" kj="A2026310982911001" kj_year="2026" kj_term="3">
				  <div class="card_background_cover" style="background-image:url(/cover/dynamic_13.png?h=150)"></div>
				  <div class="card_title">학교 선형대수</div><div class="card_info">109829-11001</div>
				  <div class="card_text">담당교수</div>
				  <div class="card_bottom"><span class="info_txt">목(1 ~ 3)</span><span class="info_txt">3학점</span></div>
				</a>
				<a class="sub_open" kj="비2026B35601" kj_year="2026" kj_term="B">
				  <div class="card_title">학교 안전교육</div><div class="card_info">-01</div>
				  <div class="card_text">교육담당</div>
				  <div class="card_bottom"><span class="info_txt">2026.05.13~2026.12.21</span>
				  <span class="info_txt">이러닝수업</span></div>
				</a>
				""";

		/** 강의실 첫 화면. 정규과목은 2주(1주 영상, 2주 과제), 비정규과목은 1회차 설문. */
		static String room(String key) {
			if (key == null) {
				return "<p>과목을 선택하세요.</p>";
			}
			String activities = key.startsWith("A") ? """
					<div id="week_list_wrap_1" class="week_list_container"><div class="week_period">9월 1일 ~ 9월 7일</div>
					<a class="week_activity week_activity_lecture_weeks" href="/ilos/cls/st/activity/activity_form.acl?m=lecture_weeks&amp;n=501">
					<span class="activity_title">학교 1주 영상</span>
					<div class="activity_info"><div class="activity_info_title">출석</div><div class="activity_info_text">1 주 1차시</div></div>
					<div class="activity_info"><div class="activity_info_title">학습시간</div><div class="activity_info_text">25:05</div></div>
					<div class="activity_info"><div class="activity_info_title">마감</div><div class="activity_info_text">만료됨</div></div>
					<div class="submit_check_outer"><div class="submit_check"></div></div></a></div>
					<div id="week_list_wrap_2" class="week_list_container"><div class="week_period">9월 8일 ~ 9월 14일</div>
					<a class="week_activity week_activity_report1" href="/ilos/cls/st/activity/activity_form.acl?m=report&amp;n=502">
					<span class="activity_title">학교 과제</span>
					<div class="activity_info"><div class="activity_info_title">마감</div><div class="activity_info_text">12월 31일 (목) 오후 11:59</div></div></a></div>
					""" : """
					<div id="week_list_wrap_1" class="week_list_container"><div class="week_period"></div>
					<a class="week_activity week_activity_survey" href="/ilos/cls/st/activity/activity_form.acl?m=survey&amp;n=1">
					<span class="activity_title">학교 설문</span></a></div>
					""";
			return "<input type=\"hidden\" id=\"KJ_KEY\" value=\"" + key + "\"><div class=\"activity_list_container\">"
					+ activities + "</div>";
		}

		private static final String LOGIN_FORM = """
				<form id="myform" method="post" action="/ilos/lo/login.acl">
				<input type="hidden" name="returnURL" value="/ilos/main/main_form.acl">
				<input type="text" name="usr_id"><input type="password" name="usr_pwd">
				<input type="submit" value="로그인">
				</form>
				""";

		private static Map<String, String> form(String body) {
			Map<String, String> fields = new HashMap<>();
			for (String pair : body.split("&")) {
				int equals = pair.indexOf('=');
				if (equals > 0) {
					fields.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
							URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
				}
			}
			return fields;
		}

		private static void redirect(HttpExchange exchange, String location) throws IOException {
			exchange.getResponseHeaders().add("Location", location);
			exchange.sendResponseHeaders(302, -1);
		}

		private static void send(HttpExchange exchange, int status, String html) throws IOException {
			byte[] body = html.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
			exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
			if (body.length > 0) {
				exchange.getResponseBody().write(body);
			}
		}

	}

}
