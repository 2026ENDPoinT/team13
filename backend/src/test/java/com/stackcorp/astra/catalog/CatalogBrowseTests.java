package com.stackcorp.astra.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;

/**
 * 정규·비정규 카탈로그 조회와 내 과목 목록. 구성원이 아닌 사람에게는 공개 항목만 보이고, 목록은 page 상한과 고정 정렬을 따르며,
 * 검색어의 %, _, \는 글자 그대로 찾는다.
 */
class CatalogBrowseTests extends IntegrationTest {

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
	void regularCatalogPagesFiltersAndSearchesLiterally() throws Exception {
		long term = this.fixtures.term("2026년 2학기");
		long otherTerm = this.fixtures.term("2026년 1학기");
		long percent = this.fixtures.regular(term, "100% 출석 세미나", "100001", "21001", "D1", "1", false);
		long underscore = this.fixtures.regular(term, "A_B 설계", "100002", "21001", "D1", "3", true);
		long lookalike = this.fixtures.regular(term, "AxB 설계", "100003", "21001", "D2", "3", false);
		long backslash = this.fixtures.regular(term, "경로\\구분자", "100004", "21001", "D2", "2", false);
		long fifth = this.fixtures.regular(term, "자료구조", "100005", "31001", "D1", "3", false);
		long cancelled = this.fixtures.regular(term, "폐강 과목", "100006", "21001", "D1", "3", false);
		this.fixtures.update("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c", cancelled);
		this.fixtures.regular(otherTerm, "다른 학기 과목", "100001", "21001", "D1", "3", false);
		ApiClient student = client(this.data.user("STUDENT"));
		String base = "/api/catalog/courses?term=" + term;

		student.get(base + "&size=2")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.total").value(5))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].courseId").value(percent))
			.andExpect(jsonPath("$.items[1].courseId").value(underscore));
		student.get(base + "&size=2&page=3")
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].courseId").value(fifth));
		student.get(base + "&size=2&page=4").andExpect(jsonPath("$.items.length()").value(0));

		// MockMvc가 URL을 인코딩하므로 %, \는 글자 그대로 쓴다.
		assertThat(ids(student.get(base + "&q=%"))).containsExactly(percent);
		assertThat(ids(student.get(base + "&q=_"))).containsExactly(underscore);
		assertThat(ids(student.get(base + "&q=\\"))).containsExactly(backslash);
		assertThat(ids(student.get(base + "&q=B 설계"))).containsExactly(underscore, lookalike);
		assertThat(ids(student.get(base + "&q=100005-31001"))).containsExactly(fifth);
		assertThat(ids(student.get(base + "&department=D2"))).containsExactly(lookalike, backslash);
		assertThat(ids(student.get(base + "&credit=3&credit=1"))).containsExactly(percent, underscore, lookalike, fifth);
		assertThat(ids(student.get(base + "&auditable=true"))).containsExactly(underscore);
		assertThat(ids(student.get(base + "&orgSect=G"))).isEmpty();
		assertThat(ids(student.get(base + "&term=" + otherTerm))).hasSize(6);

		student.get("/api/catalog/filters?term=" + term)
			.andExpect(jsonPath("$.departments.length()").value(2))
			.andExpect(jsonPath("$.terms[?(@.termId == %d)].name", term).value("2026년 2학기"));

		for (String bad : List.of("&size=41", "&page=0", "&orgSect=X", "&department=a b", "&credit=1.25",
				"&q=" + "가".repeat(101))) {
			student.get(base + bad).andExpect(status().isBadRequest());
		}
		this.mvc.perform(get(base)).andExpect(status().isUnauthorized());
	}

	@Test
	void regularDetailShowsOnlyPublicCatalogFieldsToNonMembers() throws Exception {
		long term = this.fixtures.term("공개 범위 학기");
		long course = this.fixtures.regular(term, "데이터베이스", "109834", "21001", "D1", "3", true);
		this.jdbc.sql("""
				INSERT INTO course_schedules (course_id, day_of_week, period_start, period_end, start_time, end_time, room_name)
				VALUES (:c, 4, 6, 8, '14:00', '17:00', '어의관-101')
				""").param("c", course).update();
		long professor = this.fixtures.named("김교수", "PROFESSOR");
		this.data.instructor(course, professor);
		String secretName = "명단" + UUID.randomUUID().toString().substring(0, 6);
		long enrolled = this.fixtures.named(secretName, "STUDENT");
		this.data.enroll(course, enrolled);
		this.data.activity(course, "lecture_material", true, null, null);
		ApiClient outsider = client(this.data.user("STUDENT"));

		String body = outsider.get("/api/catalog/courses/" + course)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.course.instructors[0]").value("김교수"))
			.andExpect(jsonPath("$.course.schedules[0].room").value("어의관-101"))
			.andExpect(jsonPath("$.viewer.role").isEmpty())
			.andExpect(jsonPath("$.viewer.canRequestAudit").value(true))
			.andReturn()
			.getResponse()
			.getContentAsString();
		JsonNode json = ApiClient.json(outsider.get("/api/catalog/courses/" + course));
		assertThat(keys(json)).containsExactlyInAnyOrder("course", "viewer");
		assertThat(keys(json.get("course"))).containsExactlyInAnyOrder("courseId", "key", "name", "code", "section",
				"orgSect", "term", "departmentCode", "departmentName", "credit", "completionType", "gradeYear",
				"lectureType", "auditAllowed", "merged", "instructors", "schedules", "coverTemplate");
		assertThat(body).doesNotContain(secretName).doesNotContain("9주차");

		// 구성원 전용 조회는 여전히 존재를 드러내지 않는다.
		outsider.get("/api/courses/" + course).andExpect(status().isNotFound());
		client(enrolled).get("/api/catalog/courses/" + course)
			.andExpect(jsonPath("$.viewer.role").value("STUDENT"))
			.andExpect(jsonPath("$.viewer.reasons[0]").value("already_member"));
		this.fixtures.update("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c", course);
		outsider.get("/api/catalog/courses/" + course).andExpect(status().isNotFound());
		outsider.get("/api/catalog/courses/999999999").andExpect(status().isNotFound());
		long offering = this.fixtures.openOffering("비정규 과정", "AUTO", null);
		outsider.get("/api/catalog/courses/" + offering).andExpect(status().isNotFound());
		outsider.get("/api/catalog/offerings/" + course).andExpect(status().isNotFound());
	}

	@Test
	void offeringCatalogReportsStateCapacityAndFiltersFromTheDatabaseClock() throws Exception {
		String token = "토큰" + UUID.randomUUID().toString().substring(0, 8);
		long open = this.fixtures.openOffering(token + " 접수중", "AUTO", 30);
		long upcoming = this.fixtures.offering(token + " 접수예정", "MANUAL", null,
				"DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 2 DAY)", "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 9 DAY)",
				List.of("1", "2"));
		long closed = this.fixtures.offering(token + " 마감", "AUTO", null,
				"DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 9 DAY)", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 2 DAY)",
				List.of("3"));
		this.fixtures.update("UPDATE non_regular_offerings SET nonregular_category = 'K' WHERE course_id = :c", closed);
		this.fixtures.update("UPDATE courses SET lecture_type = '플립' WHERE course_id = :c", closed);
		ApiClient student = client(this.data.user("STUDENT"));
		String base = "/api/catalog/offerings?q=" + token;

		student.get(base)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.serverTime").isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.courseId == %d)].state", open).value("OPEN"))
			.andExpect(jsonPath("$.items[?(@.courseId == %d)].capacity", open).value(30))
			.andExpect(jsonPath("$.items[?(@.courseId == %d)].enrolled", open).value(0))
			.andExpect(jsonPath("$.items[?(@.courseId == %d)].state", upcoming).value("UPCOMING"))
			.andExpect(jsonPath("$.items[?(@.courseId == %d)].approvalMode", upcoming).value("MANUAL"))
			.andExpect(jsonPath("$.items[?(@.courseId == %d && @.capacity == null)]", upcoming).isNotEmpty())
			.andExpect(jsonPath("$.items[?(@.courseId == %d)].state", closed).value("CLOSED"));
		// 접수 시작 일시 역순이다.
		assertThat(ids(student.get(base))).containsExactly(upcoming, open, closed);
		assertThat(ids(student.get(base + "&open=true"))).containsExactly(open);
		assertThat(ids(student.get(base + "&audience=2"))).containsExactly(upcoming);
		assertThat(ids(student.get(base + "&division=K"))).containsExactly(closed);
		assertThat(ids(student.get(base + "&lectureType=플립"))).containsExactly(closed);
		assertThat(ids(student.get(base + "&category=56&size=1"))).hasSize(1);
		assertThat(ids(student.get(base + "&q=" + token + "%"))).isEmpty();

		JsonNode detail = ApiClient.json(student.get("/api/catalog/offerings/" + open));
		assertThat(keys(detail)).containsExactlyInAnyOrder("offering", "viewer", "serverTime");
		assertThat(keys(detail.get("offering"))).doesNotContain("introductionHtml", "introduction");
		assertThat(detail.get("offering").get("audiences").get(0).asString()).isEqualTo("3");
		assertThat(detail.get("viewer").get("eligible").asBoolean()).isTrue();

		for (String bad : List.of("&audience=7", "&division=X", "&lectureType=강의", "&category=a'b", "&size=0")) {
			student.get(base + bad).andExpect(status().isBadRequest());
		}
		// 조회는 아무것도 만들지 않고, 명령은 POST에만 있다.
		this.mvc.perform(post("/api/catalog/offerings/" + open)).andExpect(status().isForbidden());
		assertThat(this.fixtures.rows(open)).isZero();
	}

	@Test
	void catalogItemsCarryTheSameCoverTemplateAsMyCourses() throws Exception {
		long term = this.fixtures.term("표지 학기");
		long covered = this.fixtures.regular(term, "표지 있는 과목", "300001", "21001", "D1", "3", false);
		long plain = this.fixtures.regular(term, "표지 없는 과목", "300002", "21001", "D1", "3", false);
		this.fixtures.update("UPDATE courses SET cover_template = 'dynamic_13' WHERE course_id = :c", covered);
		String token = "표지" + UUID.randomUUID().toString().substring(0, 8);
		long coveredOffering = this.fixtures.openOffering(token + " 표지 있는 과정", "AUTO", null);
		long plainOffering = this.fixtures.openOffering(token + " 표지 없는 과정", "AUTO", null);
		this.fixtures.update("UPDATE courses SET cover_template = 'dynamic_7' WHERE course_id = :c", coveredOffering);
		long studentId = this.data.user("STUDENT");
		this.data.enroll(covered, studentId);
		this.data.enroll(plain, studentId);
		ApiClient student = client(studentId);

		Map<Long, JsonNode> regular = byCourse(
				ApiClient.json(student.get("/api/catalog/courses?term=" + term)).get("items"));
		assertThat(regular.get(covered).get("coverTemplate").asString()).isEqualTo("dynamic_13");
		assertThat(regular.get(plain).has("coverTemplate")).isTrue();
		assertThat(regular.get(plain).get("coverTemplate").isNull()).isTrue();

		// 내 과목 목록과 이름, 값, 없을 때의 null이 같다.
		Map<Long, JsonNode> mine = byCourse(ApiClient.json(student.get("/api/me/courses?type=REGULAR&termId=" + term)));
		for (long course : List.of(covered, plain)) {
			assertThat(regular.get(course).get("coverTemplate")).isEqualTo(mine.get(course).get("coverTemplate"));
		}

		Map<Long, JsonNode> offerings = byCourse(
				ApiClient.json(student.get("/api/catalog/offerings?q=" + token)).get("items"));
		assertThat(offerings.get(coveredOffering).get("coverTemplate").asString()).isEqualTo("dynamic_7");
		assertThat(offerings.get(plainOffering).has("coverTemplate")).isTrue();
		assertThat(offerings.get(plainOffering).get("coverTemplate").isNull()).isTrue();

		// 상세는 목록 항목을 그대로 담는다.
		student.get("/api/catalog/courses/" + covered).andExpect(jsonPath("$.course.coverTemplate").value("dynamic_13"));
		student.get("/api/catalog/offerings/" + coveredOffering)
			.andExpect(jsonPath("$.offering.coverTemplate").value("dynamic_7"));
	}

	@Test
	void filtersListCategoriesThatHaveLiveOfferingsInTheChosenDivision() throws Exception {
		String live = "T" + UUID.randomUUID().toString().substring(0, 8);
		String gone = "T" + UUID.randomUUID().toString().substring(0, 8);
		long offering = this.fixtures.openOffering("분류 있는 과정", "AUTO", null);
		this.fixtures.update("UPDATE non_regular_offerings SET nonregular_category = 'C', category_code = '" + live
				+ "', category_name = '건설시스템공학과' WHERE course_id = :c", offering);
		long cancelled = this.fixtures.openOffering("폐강 과정", "AUTO", null);
		this.fixtures.update("UPDATE non_regular_offerings SET nonregular_category = 'C', category_code = '" + gone
				+ "' WHERE course_id = :c", cancelled);
		this.fixtures.update("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c", cancelled);
		// 학사 동기화가 없어 이름을 저장하지 않은 분류와 빈 이름의 분류는 코드를 표시명으로 쓴다.
		String unnamed = "T" + UUID.randomUUID().toString().substring(0, 8);
		long bare = this.fixtures.openOffering("이름 없는 분류 과정", "AUTO", null);
		this.fixtures.update("UPDATE non_regular_offerings SET nonregular_category = 'C', category_code = '" + unnamed
				+ "', category_name = NULL WHERE course_id = :c", bare);
		String blank = "T" + UUID.randomUUID().toString().substring(0, 8);
		long spaces = this.fixtures.openOffering("빈 이름 분류 과정", "AUTO", null);
		this.fixtures.update("UPDATE non_regular_offerings SET nonregular_category = 'C', category_code = '" + blank
				+ "', category_name = '  ' WHERE course_id = :c", spaces);
		ApiClient student = client(this.data.user("STUDENT"));

		student.get("/api/catalog/filters?division=C")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.categories[?(@.code == '%s')].name", live).value("건설시스템공학과"))
			.andExpect(jsonPath("$.categories[?(@.code == '%s')]", gone).isEmpty())
			.andExpect(jsonPath("$.categories[?(@.code == '%s')].name", unnamed).value(unnamed))
			.andExpect(jsonPath("$.categories[?(@.code == '%s')].name", blank).value(blank))
			.andExpect(jsonPath("$.categories[?(@.name == null)]").isEmpty());
		student.get("/api/catalog/filters")
			.andExpect(jsonPath("$.categories[?(@.code == '%s')]", live).isNotEmpty());
		student.get("/api/catalog/filters?division=K")
			.andExpect(jsonPath("$.categories[?(@.code == '%s')]", live).isEmpty());
		// 고른 분류의 코드가 목록 필터와 같은 과정을 고른다.
		assertThat(ids(student.get("/api/catalog/offerings?category=" + live))).containsExactly(offering);
		student.get("/api/catalog/filters?division=X").andExpect(status().isBadRequest());
		new ApiClient(this.mvc, null).get("/api/catalog/filters").andExpect(status().isUnauthorized());
	}

	@Test
	void myCoursesNarrowByTypeTermAndDivisionKeepingTheExistingShape() throws Exception {
		long first = this.fixtures.term("내 학기 1");
		long second = this.fixtures.term("내 학기 2");
		long current = this.fixtures.regular(first, "알고리즘", "200001", "21001", "D1", "3", false);
		long previous = this.fixtures.regular(second, "이산수학", "200002", "21001", "D1", "2", false);
		long offering = this.fixtures.openOffering("비교과 과정", "AUTO", null);
		long studentId = this.data.user("STUDENT");
		this.data.enroll(current, studentId);
		this.data.enroll(previous, studentId);
		ApiClient student = client(studentId);
		student.send(post("/api/catalog/offerings/" + offering + "/enrollments").header("Idempotency-Key", "my-1"))
			.andExpect(status().isCreated());

		JsonNode all = ApiClient.json(student.get("/api/me/courses"));
		assertThat(all).hasSize(3);
		assertThat(keys(all.get(0))).contains("courseId", "key", "name", "code", "section", "term", "role",
				"instructors");
		student.get("/api/me/courses?type=REGULAR&termId=" + first)
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].courseId").value(current))
			.andExpect(jsonPath("$[0].type").value("REGULAR"))
			.andExpect(jsonPath("$[0].term.termId").value(first))
			.andExpect(jsonPath("$[0].enrollment.kind").value("REGISTRAR"))
			.andExpect(jsonPath("$[0].enrollment.cancellable").value(false));
		student.get("/api/me/courses?type=NONREGULAR&division=B")
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].courseId").value(offering))
			.andExpect(jsonPath("$[0].offering.roundYear").value(2026));
		student.get("/api/me/courses?type=NONREGULAR&division=C").andExpect(jsonPath("$.length()").value(0));
		student.get("/api/me/courses?q=이산").andExpect(jsonPath("$.length()").value(1));
		student.get("/api/me/courses?type=OTHER").andExpect(status().isBadRequest());
		student.get("/api/me/courses?division=Z").andExpect(status().isBadRequest());
	}

	private ApiClient client(long userId) {
		return new ApiClient(this.mvc, this.data.session(userId));
	}

	private static List<Long> ids(org.springframework.test.web.servlet.ResultActions result) throws Exception {
		JsonNode items = ApiClient.json(result.andExpect(status().isOk())).get("items");
		List<Long> ids = new ArrayList<>();
		for (JsonNode item : items) {
			ids.add(item.get("courseId").asLong());
		}
		return ids;
	}

	private static Map<Long, JsonNode> byCourse(JsonNode items) {
		Map<Long, JsonNode> result = new HashMap<>();
		for (JsonNode item : items) {
			result.put(item.get("courseId").asLong(), item);
		}
		return result;
	}

	private static Set<String> keys(JsonNode node) {
		return node.properties().stream().map(Map.Entry::getKey).collect(Collectors.toSet());
	}

}
