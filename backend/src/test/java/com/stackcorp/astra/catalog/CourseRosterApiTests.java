package com.stackcorp.astra.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;

/**
 * 담당자의 수강생 명단. 그 과목의 담당자만 보고, 명단은 강의실 권한과 같은 규칙(MembershipRule)의 수강생이며, 화면이 보이는 항목만 담는다.
 */
class CourseRosterApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Test
	void instructorsPageSortAndSearchTheirCoursesStudents() throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 6);
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		long ta = this.data.user("STUDENT");
		this.jdbc.sql("INSERT INTO course_instructors (course_id, user_id, instructor_role) VALUES (:c, :u, 'TA')")
			.param("c", course)
			.param("u", ta)
			.update();
		long kim = person("김민수", "2026" + tag + "3");
		long lee = person("이서연", "2026" + tag + "1");
		long park = person("박지훈", "2026" + tag + "2");
		this.data.enroll(course, kim);
		this.data.enroll(course, lee);
		enrollment(course, park, "AUDIT", "AUDITOR", "ENROLLED");
		long dropped = person("최취소", "2026" + tag + "4");
		enrollment(course, dropped, "NONREGULAR", "STUDENT", "CANCELLED");
		long pending = person("정대기", "2026" + tag + "5");
		enrollment(course, pending, "AUDIT", "AUDITOR", "REQUESTED");
		ApiClient professor = client(prof);
		String path = "/api/courses/" + course + "/students";

		JsonNode page = ApiClient.json(professor.get(path)
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[*].name").value(contains("김민수", "박지훈", "이서연")))
			.andExpect(jsonPath("$.items[1].studentNumber").value("2026" + tag + "2"))
			.andExpect(jsonPath("$.items[1].kind").value("AUDIT"))
			.andExpect(jsonPath("$.items[1].role").value("AUDITOR"))
			.andExpect(jsonPath("$.items[0].kind").value("REGISTRAR")));
		// 화면이 보이는 항목만 담는다. 학과, 학년, 연락처는 없다.
		assertThat(page.get("items").get(0).properties().stream().map(Map.Entry::getKey).toList())
			.containsExactlyInAnyOrder("studentId", "name", "studentNumber", "kind", "role", "lastAccessAt",
					"progressPercent");
		// 학습을 시작하지 않았고 영상 콘텐츠가 없는 과목이다.
		assertThat(page.get("items").get(0).get("lastAccessAt").isNull()).isTrue();
		assertThat(page.get("items").get(0).get("progressPercent").isNull()).isTrue();

		professor.get(path + "?sort=studentNumber&order=desc")
			.andExpect(jsonPath("$.items[*].studentId").value(contains((int) kim, (int) park, (int) lee)));
		professor.get(path + "?sort=studentNumber&size=2&page=2")
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.items[*].studentId").value(contains((int) kim)));
		professor.get(path + "?q=서연").andExpect(jsonPath("$.items[*].studentId").value(contains((int) lee)));
		professor.get(path + "?q=" + tag + "2")
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[*].studentId").value(contains((int) park)));
		client(ta).get(path).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3));

		for (String bad : List.of("?sort=email", "?order=up", "?size=101", "?size=0", "?page=0", "?q=" + "가".repeat(51))) {
			professor.get(path + bad).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("invalid_request"));
		}
	}

	@Test
	void onlyThatCoursesInstructorsSeeTheRoster() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		long student = this.data.user("STUDENT");
		long classmate = this.data.user("STUDENT");
		this.data.enroll(course, student);
		this.data.enroll(course, classmate);
		long otherCourse = this.data.course();
		long otherProf = this.data.user("PROFESSOR");
		this.data.instructor(otherCourse, otherProf);
		String path = "/api/courses/" + course + "/students";

		// 학생은 다른 학생의 학번을 어떤 경로로도 받지 못한다.
		client(student).get(path).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("not_found"));
		client(classmate).get(path).andExpect(status().isNotFound());
		client(this.data.user("STUDENT")).get(path).andExpect(status().isNotFound());
		client(otherProf).get(path).andExpect(status().isNotFound());
		client(prof).get("/api/courses/" + otherCourse + "/students").andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).get(path).andExpect(status().isUnauthorized());

		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c").param("c", course).update();
		client(prof).get(path).andExpect(status().isNotFound());
	}

	@Test
	void rosterShowsLastLearningAndProgressOverLecturesOpenToStudents() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		long active = person("가학습", "2026" + UUID.randomUUID().toString().substring(0, 6));
		long idle = person("나미학습", "2026" + UUID.randomUUID().toString().substring(0, 6));
		this.data.enroll(course, active);
		this.data.enroll(course, idle);
		long open = this.data.activity(course, "lecture_weeks", true, null, null);
		long watched = item(open, 1);
		item(open, 2);
		long hidden = item(this.data.activity(course, "lecture_weeks", false, null, null), 1);
		long later = item(this.data.activity(course, "lecture_weeks", true, "2999-01-01 00:00:00", null), 1);
		// 열린 콘텐츠 둘 가운데 하나를 채웠다. 닫힌 콘텐츠의 충족은 비율에 넣지 않는다.
		for (long met : new long[] { watched, hidden, later }) {
			this.jdbc.sql("""
					INSERT INTO online_lecture_item_progress (user_id, online_lecture_item_id, credited_in_sec, met_at)
					VALUES (:u, :i, 60, '2026-11-01 09:00:00')
					""").param("u", active).param("i", met).update();
		}
		this.jdbc.sql("""
				INSERT INTO course_continue_pointers (user_id, course_id, activity_id, menu_code, recorded_at)
				VALUES (:u, :c, :a, 'lecture_weeks', '2026-11-01 08:59:00.250')
				""").param("u", active).param("c", course).param("a", open).update();

		JsonNode items = ApiClient.json(client(prof).get("/api/courses/" + course + "/students")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.items[*].studentId").value(contains((int) active, (int) idle)))).get("items");
		assertThat(LocalDateTime.parse(items.get(0).get("lastAccessAt").asString()))
			.isEqualTo(LocalDateTime.of(2026, 11, 1, 8, 59, 0, 250_000_000));
		assertThat(items.get(0).get("progressPercent").asInt()).isEqualTo(50);
		assertThat(items.get(1).get("lastAccessAt").isNull()).isTrue();
		assertThat(items.get(1).get("progressPercent").asInt()).isZero();
	}

	private long item(long activityId, int linkSeq) {
		return this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path, duration_sec, required_sec)
				VALUES (:a, :seq, '강의', 'video', :path, 300, 60)
				""", Map.of("a", activityId, "seq", linkSeq, "path",
				"vod/lecture-" + UUID.randomUUID().toString().substring(0, 8) + "/main.m3u8"));
	}

	private ApiClient client(long userId) {
		return new ApiClient(this.mvc, this.data.session(userId));
	}

	private long person(String name, String number) {
		return this.data.insert("INSERT INTO users (external_ref, user_name, user_role) VALUES (:ref, :name, 'STUDENT')",
				Map.of("ref", number, "name", name));
	}

	private void enrollment(long course, long user, String kind, String role, String status) {
		this.jdbc.sql("""
				INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status)
				VALUES (:c, :u, :kind, :role, :status)
				""").param("c", course).param("u", user).param("kind", kind).param("role", role).param("status", status).update();
	}

}
