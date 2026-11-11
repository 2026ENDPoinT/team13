package com.stackcorp.astra.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 비정규 수강신청, 청강 신청, 취소, 승인·반려(CHG-03, ADR-0026). 조회는 신청을 만들지 않고, 신청은 기간·대상·중복·정원을 한
 * transaction에서 판정하며, 좌석 카운터는 언제나 좌석을 점유한 수강 행 수와 같다.
 */
class EnrollmentFlowTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	CatalogFixtures fixtures;

	@BeforeEach
	void fixtures() {
		this.fixtures = new CatalogFixtures(this.jdbc, this.data);
	}

	@Test
	void autoApprovalEnrollsOnTheCommandOnlyAndAccessFollowsTheLedger() throws Exception {
		long course = this.fixtures.openOffering("자동 승인 과정", "AUTO", 10);
		long studentId = this.data.user("STUDENT");
		ApiClient student = client(studentId);

		// 상세와 신청 미리보기를 몇 번 열어도 신청이 생기지 않는다(CHG-03).
		for (int i = 0; i < 10; i++) {
			student.get("/api/catalog/offerings/" + course)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.viewer.eligible").value(true))
				.andExpect(jsonPath("$.viewer.application").isEmpty());
		}
		assertThat(this.fixtures.rows(course)).isZero();
		student.get("/api/courses/" + course).andExpect(status().isNotFound());

		long first = ApiClient.json(apply(student, course, "apply-1").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ENROLLED"))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.kind").value("NONREGULAR"))).get("enrollmentId").asLong();
		assertThat(this.fixtures.held(course)).isEqualTo(1);
		assertThat(this.fixtures.events(first)).containsExactly("APPLY:->ENROLLED");
		assertThat(this.fixtures.audits("enrollment.apply", first)).isEqualTo(1);

		// 확정되는 즉시 강의실 권한이 생긴다.
		student.get("/api/courses/" + course).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("STUDENT"));
		student.get("/api/courses/" + course + "/weeks").andExpect(status().isOk());
		student.get("/api/me/courses?type=NONREGULAR")
			.andExpect(jsonPath("$[?(@.courseId == %d)].enrollment.kind", course).value("NONREGULAR"))
			.andExpect(jsonPath("$[?(@.courseId == %d)].enrollment.cancellable", course).value(true))
			.andExpect(jsonPath("$[?(@.courseId == %d)].offering.roundLabel", course).value("[2026년 1차수]"));
		student.get("/api/catalog/offerings/" + course)
			.andExpect(jsonPath("$.offering.enrolled").value(1))
			.andExpect(jsonPath("$.viewer.application.status").value("ENROLLED"))
			.andExpect(jsonPath("$.viewer.reasons[0]").value("already_applied"));

		// 취소는 좌석을 한 번만 돌려주고 행을 지우지 않는다. 두 번째 취소는 같은 결과다.
		for (int i = 0; i < 2; i++) {
			student.post("/api/enrollments/" + first + "/cancel")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CANCELLED"))
				.andExpect(jsonPath("$.version").value(2));
			assertThat(this.fixtures.held(course)).isZero();
		}
		assertThat(this.fixtures.events(first)).containsExactly("APPLY:->ENROLLED", "CANCEL:ENROLLED>CANCELLED");
		assertThat(this.fixtures.status(first)).isEqualTo("CANCELLED");

		// 취소하면 모든 경로에서 권한이 사라진다.
		student.get("/api/courses/" + course).andExpect(status().isNotFound());
		student.get("/api/courses/" + course + "/weeks").andExpect(status().isNotFound());
		student.get("/api/courses/" + course + "/notices").andExpect(status().isNotFound());
		student.get("/api/me/courses").andExpect(jsonPath("$[?(@.courseId == %d)]", course).isEmpty());

		// 다시 신청하면 새 행이 생기고 이전 행은 이력으로 남는다.
		long second = ApiClient.json(apply(student, course, "apply-2").andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("ENROLLED"))).get("enrollmentId").asLong();
		assertThat(second).isNotEqualTo(first);
		assertThat(this.fixtures.rows(course)).isEqualTo(2);
		assertThat(this.fixtures.held(course)).isEqualTo(1);
		assertThat(this.fixtures.seatHolders(course)).isEqualTo(1);
		student.get("/api/courses/" + course).andExpect(status().isOk());
	}

	@Test
	void sameKeyReplaysTheReceiptAndAnotherKeyIsADuplicateApplication() throws Exception {
		long course = this.fixtures.openOffering("중복 신청 과정", "AUTO", null);
		long other = this.fixtures.openOffering("다른 과정", "AUTO", null);
		ApiClient student = client(this.data.user("STUDENT"));

		long id = ApiClient.json(apply(student, course, "same-key").andExpect(status().isCreated()))
			.get("enrollmentId")
			.asLong();
		apply(student, course, "same-key").andExpect(status().isCreated())
			.andExpect(jsonPath("$.enrollmentId").value(id))
			.andExpect(jsonPath("$.status").value("ENROLLED"));
		assertThat(this.fixtures.events(id)).hasSize(1);
		apply(student, course, "other-key").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("already_applied"));
		apply(student, other, "same-key").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		apply(student, course, null).andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("idempotency_key_required"));
		apply(student, course, "bad key!").andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		assertThat(this.fixtures.rows(course)).isEqualTo(1);
		assertThat(this.fixtures.held(course)).isEqualTo(1);
		assertThat(this.fixtures.rows(other)).isZero();
	}

	@Test
	void applicationWindowUsesTheDatabaseClockWithInclusiveOpenAndExclusiveClose() throws Exception {
		LocalDateTime now = this.fixtures.dbNow();
		String at = "'" + now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")) + "'";
		long opensNow = this.fixtures.offering("방금 열린 과정", "AUTO", null, at, CatalogFixtures.CLOSE_LATER,
				List.of("3"));
		long closesNow = this.fixtures.offering("방금 닫힌 과정", "AUTO", null, CatalogFixtures.OPEN_NOW, at, List.of("3"));
		long upcoming = this.fixtures.offering("접수 예정 과정", "AUTO", null,
				"DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR)", CatalogFixtures.CLOSE_LATER, List.of("3"));
		long stopped = this.fixtures.openOffering("운영 종료 과정", "AUTO", null);
		this.fixtures.update("UPDATE non_regular_offerings SET offering_status = 'CLOSED' WHERE course_id = :c", stopped);
		ApiClient student = client(this.data.user("STUDENT"));

		apply(student, opensNow, "w1").andExpect(status().isCreated());
		apply(student, closesNow, "w2").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("application_closed"));
		apply(student, upcoming, "w3").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_open_yet"));
		apply(student, stopped, "w4").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("application_closed"));
		student.get("/api/catalog/offerings/" + upcoming)
			.andExpect(jsonPath("$.offering.state").value("UPCOMING"))
			.andExpect(jsonPath("$.viewer.eligible").value(false))
			.andExpect(jsonPath("$.viewer.reasons[0]").value("not_open_yet"));
		student.get("/api/catalog/offerings/" + closesNow).andExpect(jsonPath("$.offering.state").value("CLOSED"));
		assertThat(this.fixtures.rows(closesNow) + this.fixtures.rows(upcoming) + this.fixtures.rows(stopped)).isZero();
	}

	@Test
	void onlyTargetAudiencesApplyAndInstructorsCannotApplyToTheirOwnOffering() throws Exception {
		long staffOnly = this.fixtures.offering("교직원 과정", "AUTO", null, CatalogFixtures.OPEN_NOW,
				CatalogFixtures.CLOSE_LATER, List.of("1", "2", "5"));
		long noTargets = this.fixtures.offering("대상 없는 과정", "AUTO", null, CatalogFixtures.OPEN_NOW,
				CatalogFixtures.CLOSE_LATER, List.of());
		long everyone = this.fixtures.offering("전체 과정", "AUTO", null, CatalogFixtures.OPEN_NOW,
				CatalogFixtures.CLOSE_LATER, List.of("1", "2", "3", "5"));
		ApiClient student = client(this.data.user("STUDENT"));
		long managerId = this.data.user("STAFF");
		this.fixtures.manager(everyone, managerId, "MANAGER");
		ApiClient manager = client(managerId);
		ApiClient staff = client(this.data.user("STAFF"));

		student.get("/api/catalog/offerings/" + staffOnly)
			.andExpect(jsonPath("$.viewer.eligible").value(false))
			.andExpect(jsonPath("$.viewer.reasons[0]").value("not_target"));
		apply(student, staffOnly, "t1").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_target"));
		apply(student, noTargets, "t2").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_target"));
		manager.get("/api/catalog/offerings/" + everyone).andExpect(jsonPath("$.viewer.reasons[0]").value("own_course"));
		apply(manager, everyone, "t3").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("own_course"));
		assertThat(this.fixtures.rows(staffOnly) + this.fixtures.rows(noTargets) + this.fixtures.rows(everyone)).isZero();

		apply(staff, staffOnly, "t4").andExpect(status().isCreated());
	}

	@Test
	void concurrentApplicantsTakeExactlyTheRemainingSeatsOnNeighbouringOfferings() throws Exception {
		int capacity = 3;
		int perOffering = 10;
		long first = this.fixtures.openOffering("정원 경쟁 과정 1", "AUTO", capacity);
		long second = this.fixtures.openOffering("정원 경쟁 과정 2", "AUTO", capacity);
		List<ApiClient> students = new ArrayList<>();
		for (int i = 0; i < perOffering * 2; i++) {
			students.add(client(this.data.user("STUDENT")));
		}

		List<MockHttpServletResponse> responses = race(perOffering * 2,
				(n) -> apply(students.get(n), n % 2 == 0 ? first : second, "race-" + n).andReturn().getResponse());

		assertThat(responses.stream().filter((r) -> r.getStatus() == 201)).hasSize(capacity * 2);
		assertThat(responses.stream().filter((r) -> r.getStatus() == 409))
			.hasSize(perOffering * 2 - capacity * 2)
			.allSatisfy((r) -> assertThat(ApiClient.json(r).get("code").asString()).isEqualTo("seat_full"));
		for (long course : List.of(first, second)) {
			assertThat(this.fixtures.held(course)).isEqualTo(capacity);
			assertThat(this.fixtures.seatHolders(course)).isEqualTo(capacity);
			assertThat(this.fixtures.rows(course)).isEqualTo(capacity);
		}
	}

	@Test
	void oneStudentRacingWithDifferentKeysGetsOneEnrollment() throws Exception {
		long course = this.fixtures.openOffering("동시 중복 신청 과정", "AUTO", null);
		ApiClient student = client(this.data.user("STUDENT"));

		List<MockHttpServletResponse> responses = race(6,
				(n) -> apply(student, course, "dup-" + n).andReturn().getResponse());

		assertThat(responses.stream().filter((r) -> r.getStatus() == 201)).hasSize(1);
		assertThat(responses.stream().filter((r) -> r.getStatus() == 409)).hasSize(5)
			.allSatisfy((r) -> assertThat(ApiClient.json(r).get("code").asString()).isEqualTo("already_applied"));
		assertThat(this.fixtures.rows(course)).isEqualTo(1);
		assertThat(this.fixtures.held(course)).isEqualTo(1);
	}

	@Test
	void approvalTakesTheSeatAtDecisionTimeAndEveryTransitionKeepsTheCounter() throws Exception {
		long course = this.fixtures.openOffering("승인 과정", "MANUAL", 1);
		long managerId = this.data.user("STAFF");
		this.fixtures.manager(course, managerId, "MANAGER");
		ApiClient manager = client(managerId);
		ApiClient first = client(this.data.user("STUDENT"));
		ApiClient second = client(this.data.user("STUDENT"));
		ApiClient third = client(this.data.user("STUDENT"));

		long a = id(apply(first, course, "m1").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));
		long b = id(apply(second, course, "m2").andExpect(status().isCreated()));
		assertThat(this.fixtures.held(course)).isZero();
		first.get("/api/courses/" + course).andExpect(status().isNotFound());
		manager.get("/api/courses/" + course + "/enrollment-requests")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(2))
			.andExpect(jsonPath("$.items[0].enrollmentId").value(a))
			.andExpect(jsonPath("$.items[0].studentName").value("테스트 STUDENT"))
			.andExpect(jsonPath("$.items[0].kind").value("NONREGULAR"));

		decide(manager, course, a, "APPROVE", 1).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ENROLLED"))
			.andExpect(jsonPath("$.version").value(2));
		assertThat(this.fixtures.held(course)).isEqualTo(1);
		assertThat(this.fixtures.events(a)).containsExactly("APPLY:->PENDING", "APPROVE:PENDING>ENROLLED");
		assertThat(this.fixtures.audits("enrollment.approve", a)).isEqualTo(1);
		first.get("/api/courses/" + course).andExpect(status().isOk());

		// 정원이 찼으면 승인하지 못하고 신청은 PENDING에 남는다.
		decide(manager, course, b, "APPROVE", 1).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("seat_full"));
		assertThat(this.fixtures.status(b)).isEqualTo("PENDING");
		decide(manager, course, b, "REJECT", 5).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		decide(manager, course, b, "REJECT", 1).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
		decide(manager, course, b, "APPROVE", 2).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_pending"));
		assertThat(this.fixtures.held(course)).isEqualTo(1);
		second.post("/api/enrollments/" + b + "/cancel").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_cancellable"));

		// 반려된 학생은 다시 신청하고, 자리가 나면 승인된다.
		long b2 = id(apply(second, course, "m3").andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("PENDING")));
		first.post("/api/enrollments/" + a + "/cancel").andExpect(status().isOk());
		assertThat(this.fixtures.held(course)).isZero();
		decide(manager, course, b2, "APPROVE", 1).andExpect(status().isOk());
		assertThat(this.fixtures.held(course)).isEqualTo(1);

		// 승인 전 취소는 좌석에 영향이 없다.
		long c = id(apply(third, course, "m4").andExpect(status().isCreated()));
		third.post("/api/enrollments/" + c + "/cancel").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
		assertThat(this.fixtures.events(c)).containsExactly("APPLY:->PENDING", "CANCEL:PENDING>CANCELLED");
		assertThat(this.fixtures.held(course)).isEqualTo(1);
		assertThat(this.fixtures.seatHolders(course)).isEqualTo(1);
	}

	@Test
	void studentCancelAndApprovalRacingOnTheSamePendingApplicationLetOnlyOneWin() throws Exception {
		for (int round = 0; round < 4; round++) {
			long course = this.fixtures.openOffering("승인 경쟁 과정 " + round, "MANUAL", 5);
			// 확정 뒤 취소를 막아, 두 명령이 둘 다 성공할 수 없게 한다.
			this.fixtures.update("UPDATE non_regular_offerings SET cancel_allowed = 0 WHERE course_id = :c", course);
			long managerId = this.data.user("PROFESSOR");
			this.fixtures.manager(course, managerId, "PROFESSOR");
			ApiClient manager = client(managerId);
			ApiClient student = client(this.data.user("STUDENT"));
			long id = id(apply(student, course, "r" + round).andExpect(status().isCreated()));

			List<MockHttpServletResponse> responses = race(2,
					(n) -> (n == 0 ? student.post("/api/enrollments/" + id + "/cancel")
							: decide(manager, course, id, "APPROVE", 1)).andReturn().getResponse());

			assertThat(responses.stream().filter((r) -> r.getStatus() == 200)).hasSize(1);
			assertThat(responses.stream().filter((r) -> r.getStatus() == 409)).hasSize(1);
			String finalStatus = this.fixtures.status(id);
			assertThat(finalStatus).isIn("CANCELLED", "ENROLLED");
			assertThat(this.fixtures.held(course)).isEqualTo(finalStatus.equals("ENROLLED") ? 1 : 0);
			assertThat(this.fixtures.seatHolders(course)).isEqualTo(this.fixtures.held(course));
			assertThat(this.fixtures.events(id)).hasSize(2);
		}
	}

	@Test
	void auditRequestsAreDecidedByTheCourseProfessor() throws Exception {
		long term = this.fixtures.term("청강 학기");
		long course = this.fixtures.regular(term, "데이터베이스", "109834", "21001", "D1", "3", true);
		long professorId = this.data.user("PROFESSOR");
		this.data.instructor(course, professorId);
		long taId = this.data.user("TA");
		this.fixtures.manager(course, taId, "TA");
		ApiClient professor = client(professorId);
		ApiClient ta = client(taId);
		ApiClient student = client(this.data.user("STUDENT"));

		long id = id(student.postJson("/api/catalog/courses/" + course + "/audit-requests", "{\"reason\":\"복수전공 준비\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.status").value("REQUESTED"))
			.andExpect(jsonPath("$.kind").value("AUDIT")));
		student.postJson("/api/catalog/courses/" + course + "/audit-requests", "{\"reason\":\"다시\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("already_applied"));
		student.get("/api/catalog/courses/" + course)
			.andExpect(jsonPath("$.viewer.audit.status").value("REQUESTED"))
			.andExpect(jsonPath("$.viewer.audit.reason").value("복수전공 준비"))
			.andExpect(jsonPath("$.viewer.canRequestAudit").value(false));
		student.get("/api/courses/" + course).andExpect(status().isNotFound());

		student.send(put("/api/enrollments/" + id + "/reason").contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"전공 심화\",\"version\":1}")).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(2));
		student.send(put("/api/enrollments/" + id + "/reason").contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"늦은 수정\",\"version\":1}")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));

		ta.get("/api/courses/" + course + "/enrollment-requests").andExpect(status().isNotFound());
		decide(ta, course, id, "APPROVE", 2).andExpect(status().isNotFound());
		professor.get("/api/courses/" + course + "/enrollment-requests")
			.andExpect(jsonPath("$.items[0].reason").value("전공 심화"))
			.andExpect(jsonPath("$.items[0].status").value("REQUESTED"));
		decide(professor, course, id, "APPROVE", 2).andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value("ENROLLED"));
		assertThat(this.fixtures.events(id)).containsExactly("APPLY:->REQUESTED", "APPROVE:REQUESTED>ENROLLED");

		// 승인된 청강생은 학생으로 강의실에 들어간다.
		student.get("/api/courses/" + course).andExpect(status().isOk()).andExpect(jsonPath("$.role").value("STUDENT"));
		student.get("/api/me/courses?type=REGULAR")
			.andExpect(jsonPath("$[?(@.courseId == %d)].enrollment.kind", course).value("AUDIT"));
		student.send(put("/api/enrollments/" + id + "/reason").contentType(MediaType.APPLICATION_JSON)
			.content("{\"reason\":\"승인 뒤 수정\",\"version\":3}")).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_editable"));
		student.post("/api/enrollments/" + id + "/cancel").andExpect(status().isOk());
		student.get("/api/courses/" + course).andExpect(status().isNotFound());

		// 반려된 청강은 취소할 것이 없다.
		ApiClient other = client(this.data.user("STUDENT"));
		long rejected = id(other.postJson("/api/catalog/courses/" + course + "/audit-requests", "{\"reason\":\"관심\"}"));
		decide(professor, course, rejected, "REJECT", 1).andExpect(jsonPath("$.status").value("REJECTED"));
		other.post("/api/enrollments/" + rejected + "/cancel").andExpect(status().isConflict());
	}

	@Test
	void auditRequestsNeedAnAuditableCourseAPlainReasonAndANonMember() throws Exception {
		long term = this.fixtures.term("청강 불가 학기");
		long closed = this.fixtures.regular(term, "운영체제", "109100", "21001", "D1", "3", false);
		long open = this.fixtures.regular(term, "컴파일러", "109200", "21001", "D1", "3", true);
		long enrolledId = this.data.user("STUDENT");
		this.data.enroll(open, enrolledId);
		ApiClient enrolled = client(enrolledId);
		ApiClient student = client(this.data.user("STUDENT"));
		String path = "/api/catalog/courses/" + open + "/audit-requests";

		student.postJson("/api/catalog/courses/" + closed + "/audit-requests", "{\"reason\":\"관심\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("audit_not_allowed"));
		enrolled.postJson(path, "{\"reason\":\"관심\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("already_member"));
		for (String bad : new String[] { "{\"reason\":\"  \"}", "{}", "{\"reason\":\"a\\u0007b\"}",
				"{\"reason\":\"" + "가".repeat(1001) + "\"}", "{\"reason\":\"x\",\"userId\":1}" }) {
			student.postJson(path, bad).andExpect(status().isBadRequest());
		}
		long offering = this.fixtures.openOffering("비정규", "AUTO", null);
		student.postJson("/api/catalog/courses/" + offering + "/audit-requests", "{\"reason\":\"관심\"}")
			.andExpect(status().isNotFound());
		assertThat(this.fixtures.rows(closed)).isZero();
		assertThat(this.fixtures.rows(open)).isEqualTo(1);
	}

	@Test
	void commandsFollowTheAuthorizationMatrix() throws Exception {
		long course = this.fixtures.openOffering("권한 과정", "MANUAL", null);
		long otherCourse = this.fixtures.openOffering("다른 권한 과정", "MANUAL", null);
		long managerId = this.data.user("STAFF");
		this.fixtures.manager(course, managerId, "MANAGER");
		long otherManagerId = this.data.user("STAFF");
		this.fixtures.manager(otherCourse, otherManagerId, "MANAGER");
		long ownerId = this.data.user("STUDENT");
		ApiClient owner = client(ownerId);
		ApiClient classmate = client(this.data.user("STUDENT"));
		ApiClient otherManager = client(otherManagerId);
		long id = id(apply(owner, course, "auth-1").andExpect(status().isCreated()));

		// 익명은 401, CSRF token이 없으면 403이고 아무것도 바뀌지 않는다.
		apply(new ApiClient(this.mvc, null), course, "anon").andExpect(status().isUnauthorized());
		Cookie session = new Cookie("ASTRA_SESSION", this.data.session(this.data.user("STUDENT")));
		this.mvc.perform(post("/api/catalog/offerings/" + course + "/enrollments").cookie(session)
			.header("Idempotency-Key", "no-csrf")).andExpect(status().isForbidden());
		this.mvc.perform(post("/api/enrollments/" + id + "/cancel").cookie(new Cookie("ASTRA_SESSION", this.data.session(ownerId))))
			.andExpect(status().isForbidden());
		assertThat(this.fixtures.status(id)).isEqualTo("PENDING");
		assertThat(this.fixtures.rows(course)).isEqualTo(1);

		// 다른 학생의 신청, 다른 과정 담당자, 경로의 과목과 맞지 않는 신청은 404다.
		classmate.post("/api/enrollments/" + id + "/cancel").andExpect(status().isNotFound());
		decide(classmate, course, id, "APPROVE", 1).andExpect(status().isNotFound());
		classmate.get("/api/courses/" + course + "/enrollment-requests").andExpect(status().isNotFound());
		decide(otherManager, otherCourse, id, "APPROVE", 1).andExpect(status().isNotFound());
		otherManager.get("/api/courses/" + course + "/enrollment-requests").andExpect(status().isNotFound());
		assertThat(this.fixtures.status(id)).isEqualTo("PENDING");

		// 학사 원장 수강은 여기서 취소하지 못한다. 정규 과목에는 비정규 신청 경로가 없다.
		long term = this.fixtures.term("권한 학기");
		long regular = this.fixtures.regular(term, "정규 과목", "109300", "21001", "D1", "3", true);
		this.data.enroll(regular, ownerId);
		long registrar = this.jdbc.sql("SELECT enrollment_id FROM course_enrollments WHERE course_id = :c")
			.param("c", regular)
			.query(Long.class)
			.single();
		owner.post("/api/enrollments/" + registrar + "/cancel").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_cancellable"));
		apply(owner, regular, "auth-2").andExpect(status().isNotFound());
		apply(owner, 999_999_999L, "auth-3").andExpect(status().isNotFound());
		decide(client(managerId), course, id, "MAYBE", 1).andExpect(status().isBadRequest());
	}

	@Test
	void enrolledStudentsCancelOnlyWhileTheOfferingAllowsIt() throws Exception {
		long closedCancel = this.fixtures.openOffering("취소 마감 과정", "AUTO", 5);
		this.fixtures.update("""
				UPDATE non_regular_offerings SET cancel_close_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
				 WHERE course_id = :c
				""", closedCancel);
		long noCancel = this.fixtures.openOffering("취소 불가 과정", "AUTO", 5);
		this.fixtures.update("UPDATE non_regular_offerings SET cancel_allowed = 0 WHERE course_id = :c", noCancel);
		ApiClient student = client(this.data.user("STUDENT"));
		long a = id(apply(student, closedCancel, "c1"));
		long b = id(apply(student, noCancel, "c2"));

		student.get("/api/catalog/offerings/" + closedCancel).andExpect(jsonPath("$.viewer.application.cancellable").value(false));
		student.post("/api/enrollments/" + a + "/cancel").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("cancel_closed"));
		student.post("/api/enrollments/" + b + "/cancel").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_cancellable"));
		assertThat(this.fixtures.held(closedCancel)).isEqualTo(1);
		assertThat(this.fixtures.held(noCancel)).isEqualTo(1);
		assertThat(this.fixtures.status(a)).isEqualTo("ENROLLED");
	}

	private ApiClient client(long userId) {
		return new ApiClient(this.mvc, this.data.session(userId));
	}

	private ResultActions apply(ApiClient client, long course, String key) throws Exception {
		var request = post("/api/catalog/offerings/" + course + "/enrollments");
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return client.send(request);
	}

	private ResultActions decide(ApiClient client, long course, long enrollment, String decision, int version)
			throws Exception {
		return client.postJson("/api/courses/" + course + "/enrollments/" + enrollment + "/decision",
				"{\"decision\":\"" + decision + "\",\"version\":" + version + "}");
	}

	private static long id(ResultActions result) throws Exception {
		return ApiClient.json(result).get("enrollmentId").asLong();
	}

	private List<MockHttpServletResponse> race(int threads, Attempt attempt) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<MockHttpServletResponse>> futures = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int n = i;
				Callable<MockHttpServletResponse> call = () -> {
					start.await();
					return attempt.run(n);
				};
				futures.add(pool.submit(call));
			}
			start.countDown();
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : futures) {
				responses.add(future.get(60, TimeUnit.SECONDS));
			}
			return responses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@FunctionalInterface
	private interface Attempt {

		MockHttpServletResponse run(int n) throws Exception;

	}

}
