package com.stackcorp.astra.catalog;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * 내 신청 목록. 본인의 비정규 수강신청과 청강 신청만, 처리 대기·확정·반려·취소를 모두 최근 신청 순으로 돌려준다.
 */
class MyApplicationsApiTests extends IntegrationTest {

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
	void listsOwnApplicationsInEveryStateNewestFirst() throws Exception {
		long manager = this.data.user("PROFESSOR");
		long manual = this.fixtures.openOffering("승인 과정", "MANUAL", 10);
		this.fixtures.manager(manual, manager, "MANAGER");
		long rejectedCourse = this.fixtures.openOffering("반려될 과정", "MANUAL", 10);
		this.fixtures.manager(rejectedCourse, manager, "MANAGER");
		long auto = this.fixtures.openOffering("자동 과정", "AUTO", 10);
		long regular = this.fixtures.regular(this.fixtures.term("청강 학기"), "운영체제", "109100", "21001", "20031106",
				"3", true);
		this.fixtures.manager(regular, manager, "PROFESSOR");
		long gone = this.fixtures.openOffering("폐강될 과정", "AUTO", 10);
		long registrar = this.data.course();
		long studentId = this.data.user("STUDENT");
		this.data.enroll(registrar, studentId);
		ApiClient student = client(studentId);
		ApiClient other = client(this.data.user("STUDENT"));

		long rejected = id(apply(student, rejectedCourse));
		client(manager).postJson("/api/courses/" + rejectedCourse + "/enrollments/" + rejected + "/decision",
				"{\"decision\":\"REJECT\",\"version\":1}").andExpect(status().isOk());
		long cancelled = id(apply(student, auto));
		student.post("/api/enrollments/" + cancelled + "/cancel").andExpect(status().isOk());
		long audit = id(student.postJson("/api/catalog/courses/" + regular + "/audit-requests",
				"{\"reason\":\"전공 준비\"}").andExpect(status().isCreated()));
		long pending = id(apply(student, manual));
		id(apply(student, gone));
		this.fixtures.update("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c", gone);
		apply(other, manual);

		student.get("/api/me/applications")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.serverTime").isNotEmpty())
			.andExpect(jsonPath("$.items[*].enrollmentId").value(contains((int) pending, (int) audit, (int) cancelled,
					(int) rejected)))
			.andExpect(jsonPath("$.items[0].status").value("PENDING"))
			.andExpect(jsonPath("$.items[0].cancellable").value(true))
			.andExpect(jsonPath("$.items[0].course.courseId").value((int) manual))
			.andExpect(jsonPath("$.items[0].course.type").value("NONREGULAR"))
			.andExpect(jsonPath("$.items[0].course.offering.roundLabel").value("[2026년 1차수]"))
			.andExpect(jsonPath("$.items[1].kind").value("AUDIT"))
			.andExpect(jsonPath("$.items[1].status").value("REQUESTED"))
			.andExpect(jsonPath("$.items[1].reason").value("전공 준비"))
			.andExpect(jsonPath("$.items[1].course.type").value("REGULAR"))
			.andExpect(jsonPath("$.items[1].course.term.name").value("청강 학기"))
			.andExpect(jsonPath("$.items[2].status").value("CANCELLED"))
			.andExpect(jsonPath("$.items[2].cancelledAt").isNotEmpty())
			.andExpect(jsonPath("$.items[2].cancellable").value(false))
			.andExpect(jsonPath("$.items[3].status").value("REJECTED"))
			.andExpect(jsonPath("$.items[3].decidedAt").isNotEmpty());
		// 처리를 기다리는 신청은 아직 구성원이 아니라 내 과목에는 없다.
		student.get("/api/me/courses").andExpect(jsonPath("$[?(@.courseId == %d)]", manual).isEmpty());

		student.get("/api/me/applications?kind=AUDIT")
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[*].enrollmentId").value(contains((int) audit)));
		student.get("/api/me/applications?size=2&page=2")
			.andExpect(jsonPath("$.total").value(4))
			.andExpect(jsonPath("$.items[*].enrollmentId").value(contains((int) cancelled, (int) rejected)));
		other.get("/api/me/applications").andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].course.courseId").value((int) manual));

		for (String bad : new String[] { "?kind=REGISTRAR", "?size=51", "?page=0" }) {
			student.get("/api/me/applications" + bad).andExpect(status().isBadRequest());
		}
		new ApiClient(this.mvc, null).get("/api/me/applications").andExpect(status().isUnauthorized());
	}

	private ApiClient client(long userId) {
		return new ApiClient(this.mvc, this.data.session(userId));
	}

	private ResultActions apply(ApiClient client, long course) throws Exception {
		return client.send(post("/api/catalog/offerings/" + course + "/enrollments")
			.header("Idempotency-Key", "apply-" + UUID.randomUUID()));
	}

	private static long id(ResultActions result) throws Exception {
		return ApiClient.json(result).get("enrollmentId").asLong();
	}

}
