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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.sun.net.httpserver.HttpExchange;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
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
		// 동시 로그인 테스트가 가져오기를 기다리는 로그인을 확실히 붙잡아 두도록 길게 둔다. 막지 않은 가져오기는 금방 끝난다.
		registry.add("astra.school-activities.wait", () -> "10s");
	}

	@AfterAll
	static void stop() {
		SCHOOL.stop();
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DataSource dataSource;

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
		int before = SCHOOL.roomReads.get();
		Cookie session = this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isNoContent())
			.andReturn().getResponse().getCookie("ASTRA_SESSION");
		assertThat(SCHOOL.roomReads.get() - before).isEqualTo(2);
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

		assertThat(SCHOOL.roomReads.get() - before).isEqualTo(2);
	}

	// 로그인은 transaction을 commit하고 DB 연결을 돌려준 뒤에 과목을 맞추고 학교 강의실 가져오기를 기다린다. 연결 pool(10개)보다 많은
	// 로그인이 몰려도 pool을 다 쓰지 않는다. 자리(기본 8개)를 얻은 가져오기의 로그인만 기다리고, 줄에 선 로그인은 바로 응답한다.
	@Test
	void signInsWaitingForTheirImportHoldNoDatabaseConnection() throws Exception {
		int signIns = 12;
		HikariPoolMXBean pool = this.dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean();
		assertThat(signIns).isGreaterThan(pool.getTotalConnections());
		int readsBefore = SCHOOL.roomReads.get();
		CountDownLatch gate = new CountDownLatch(1);
		SCHOOL.roomGate = gate;
		ExecutorService clients = Executors.newFixedThreadPool(signIns);
		try {
			List<Future<Integer>> responses = new ArrayList<>();
			List<String> studentIds = new ArrayList<>();
			for (int i = 0; i < signIns; i++) {
				String studentId = studentId();
				studentIds.add(studentId);
				responses.add(clients.submit(() -> this.mvc.perform(login(studentId, PASSWORD))
					.andReturn()
					.getResponse()
					.getStatus()));
			}
			until(() -> SCHOOL.roomsWaiting.get() == 8
					&& responses.stream().filter(Future::isDone).count() == signIns - 8);

			// 가져오기 8개가 학교 응답을 기다리고 그 로그인 8개가 응답을 기다리는 동안 DB 연결은 쉬고 있다.
			assertThat(responses.stream().filter(Future::isDone).count()).isEqualTo(signIns - 8);
			assertThat(pool.getActiveConnections()).isLessThanOrEqualTo(1);
			assertThat(pool.getThreadsAwaitingConnection()).isZero();
			gate.countDown();
			for (Future<Integer> response : responses) {
				assertThat(response.get(30, TimeUnit.SECONDS)).isEqualTo(204);
			}
			// 줄에 섰던 가져오기도 모두 끝난다. 같은 과목을 동시에 저장해도 학생마다 과목 둘을 모두 저장한다.
			until(() -> SCHOOL.roomReads.get() - readsBefore == signIns * 2
					&& studentIds.stream().allMatch((studentId) -> imported(studentId) != null));
			assertThat(studentIds).allSatisfy((studentId) -> assertThat(imported(studentId)).isEqualTo("2/0"));
		}
		finally {
			SCHOOL.roomGate = null;
			gate.countDown();
			clients.shutdownNow();
		}
	}

	/** @return 끝난 가져오기의 "읽은 과목/실패한 과목". 끝나지 않았으면 null */
	private String imported(String studentId) {
		return this.jdbc.sql("""
				SELECT CONCAT(i.courses_read, '/', i.courses_failed) FROM school_activity_imports i
				  JOIN users u ON u.user_id = i.user_id
				 WHERE u.external_ref = :ref AND i.finished_at IS NOT NULL
				""").param("ref", studentId).query(String.class).optional().orElse(null);
	}

	private static void until(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!condition.getAsBoolean()) {
			assertThat(System.nanoTime()).as("condition within 30s").isLessThan(deadline);
			Thread.sleep(20);
		}
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
		int before = SCHOOL.logins.get();

		this.mvc.perform(login(studentId, PASSWORD)).andExpect(status().isTooManyRequests());

		assertThat(SCHOOL.logins.get()).isEqualTo(before);
		this.mvc.perform(login(studentId(), PASSWORD)).andExpect(status().isNoContent());
	}

	/**
	 * 앞단 proxy 뒤에서는 모든 연결이 proxy 주소에서 온다. 주소 단위 실패 한도가 proxy 주소로 세면 한 사람의 실패가 모두의 학교
	 * 로그인을 막는다. 믿는 proxy에서 온 요청은 X-Forwarded-For의 브라우저 주소마다 센다. IPv6는 가입자 하나가 /64를 받아 요청마다
	 * 주소를 바꿀 수 있으므로 /64 하나를 한 주소로 센다. 기록에는 주소 그대로 남는다.
	 */
	@Test
	void failureLimitCountsEachClientBehindTheTrustedProxy() throws Exception {
		String proxy = "10.40.2.31";
		String attacker = randomSlash64();
		String neighbour = randomSlash64();
		for (int i = 0; i < SchoolPasswordController.ADDRESS_FAILURES; i++) {
			this.mvc.perform(behind(proxy, attacker + Integer.toHexString(i + 1), login(studentId(), "wrong")))
				.andExpect(status().isUnauthorized());
		}
		int before = SCHOOL.logins.get();

		// 같은 /64의 새 주소도 한도를 다 쓴 주소다.
		this.mvc.perform(behind(proxy, attacker + "beef", login(studentId(), PASSWORD)))
			.andExpect(status().isTooManyRequests());
		assertThat(SCHOOL.logins.get()).isEqualTo(before);

		// 같은 proxy를 거친 다른 /64의 사람, 그리고 왼쪽에 공격자 주소를 넣어 보낸 그 사람은 막히지 않는다.
		MvcResult signedIn = this.mvc.perform(behind(proxy, neighbour + "1", login(studentId(), PASSWORD)))
			.andExpect(status().isNoContent())
			.andReturn();
		this.mvc.perform(behind(proxy, attacker + "1, " + neighbour + "2", login(studentId(), PASSWORD)))
			.andExpect(status().isNoContent());
		assertThat(this.jdbc.sql("SELECT ip_address FROM user_sessions WHERE session_token_hash = SHA2(:token, 256)")
			.param("token", signedIn.getResponse().getCookie("ASTRA_SESSION").getValue())
			.query(String.class)
			.single()).isEqualTo(neighbour.replace("::", ":0:0:0:") + "1");
	}

	// "2001:db8:xxxx:yyyy::" 뒤에 interface 부분을 붙여 쓴다.
	private static String randomSlash64() {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		return "2001:db8:" + Integer.toHexString(random.nextInt(1, 0xffff)) + ":"
				+ Integer.toHexString(random.nextInt(1, 0xffff)) + "::";
	}

	private static MockHttpServletRequestBuilder behind(String proxy, String forwardedFor,
			MockHttpServletRequestBuilder request) {
		return request.header("X-Forwarded-For", forwardedFor).with((r) -> {
			r.setRemoteAddr(proxy);
			return r;
		});
	}

	@Test
	void malformedStudentIdNeverReachesTheSchool() throws Exception {
		int before = SCHOOL.logins.get();

		this.mvc.perform(login("../x", PASSWORD)).andExpect(status().isBadRequest());
		this.mvc.perform(login(studentId(), "")).andExpect(status().isBadRequest());

		assertThat(SCHOOL.logins.get()).isEqualTo(before);
	}

	@Test
	void advertisesTheMethod() throws Exception {
		assertThat(ApiClient.json(this.mvc.perform(get("/api/auth/methods"))).path("methods").toString())
			.contains("eclassPassword");
	}

	private MockHttpServletRequestBuilder login(String studentId, String password) throws Exception {
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

		final AtomicInteger logins = new AtomicInteger();

		final AtomicInteger roomReads = new AtomicInteger();

		/** 있으면 강의실 화면 요청이 이 latch가 열릴 때까지 기다린다. */
		volatile CountDownLatch roomGate;

		final AtomicInteger roomsWaiting = new AtomicInteger();

		private final AtomicInteger sessions = new AtomicInteger();

		/** 학교 session(sid 쿠키)마다 묶인 과목 키. 학교처럼 과목은 session에 묶인다. */
		private final Map<String, String> boundKeys = new ConcurrentHashMap<>();

		private final ExecutorService threads;

		private FakeSchool(HttpServer server, ExecutorService threads) {
			this.server = server;
			this.threads = threads;
		}

		void stop() {
			this.server.stop(0);
			this.threads.shutdownNow();
		}

		String origin() {
			return "http://127.0.0.1:" + this.server.getAddress().getPort();
		}

		static FakeSchool start() {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				// 여러 학생의 로그인과 가져오기가 동시에 오므로 요청마다 thread를 쓴다.
				ExecutorService threads = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
				server.setExecutor(threads);
				FakeSchool school = new FakeSchool(server, threads);
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
				String cookies = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"));
				boolean signedIn = cookies.contains("auth=ok");
				Matcher sid = SID.matcher(cookies);
				String session = sid.find() ? sid.group(1) : "";
				switch (path) {
					case "/ilos/index.acl" -> redirect(exchange, "/ilos/main/member/login_form.acl");
					case "/ilos/main/member/login_form.acl" -> {
						exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=anon; Path=/");
						send(exchange, 200, LOGIN_FORM);
					}
					case "/ilos/lo/login.acl" -> {
						this.logins.incrementAndGet();
						Map<String, String> form = form(new String(exchange.getRequestBody().readAllBytes(),
								StandardCharsets.UTF_8));
						if (PASSWORD.equals(form.get("usr_pwd")) && "/ilos/main/main_form.acl".equals(form.get("returnURL"))) {
							exchange.getResponseHeaders().add("Set-Cookie", "auth=ok; Path=/");
							exchange.getResponseHeaders().add("Set-Cookie", "sid=" + this.sessions.incrementAndGet() + "; Path=/");
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
						if (signedIn) {
							this.boundKeys.put(session, form.get("KJKEY"));
						}
						send(exchange, 200, signedIn ? "{\"isError\":false,\"returnURL\":\"/ilos/cls/st/submain/submain_form.acl\"}"
								: "{\"isError\":true,\"message\":\"로그인\"}");
					}
					case "/ilos/cls/st/submain/submain_form.acl" -> {
						CountDownLatch gate = this.roomGate;
						if (gate != null) {
							this.roomsWaiting.incrementAndGet();
							try {
								gate.await();
							}
							catch (InterruptedException ex) {
								Thread.currentThread().interrupt();
							}
							finally {
								this.roomsWaiting.decrementAndGet();
							}
						}
						this.roomReads.incrementAndGet();
						send(exchange, 200, signedIn ? room(this.boundKeys.get(session)) : LOGIN_FORM);
					}
					default -> send(exchange, 404, "");
				}
			}
		}

		private static final Pattern SID = Pattern.compile("sid=([0-9]+)");

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
