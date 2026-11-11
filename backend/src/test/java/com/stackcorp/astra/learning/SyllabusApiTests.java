package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.shared.audit.AuditRows;

import jakarta.servlet.http.Cookie;

/**
 * 강의계획서(EP-0218, EP-0219 대체 계약). 구성원은 전체를, 구성원이 아닌 사람은 정규과목의 공개 항목만 본다. 담당자만 고친다.
 */
class SyllabusApiTests extends IntegrationTest {

	private static final String CONTENT = """
			{"classTimeRoom":"목(6 ~ 8) / 미래관-208","overview":"관계형 모델과 SQL.\\n트랜잭션.","objectives":"설계",
			 "outcomeRelevance":"L3","textbooks":"교재","equipment":"노트북","gradingPolicy":"중간 30%, 기말 30%",
			 "weeks":[{"weekNo":1,"content":"소개","note":"대면"},{"weekNo":2,"content":"<script>alert(1)</script>"}]}
			""";

	private static final String CARD = """
			{"department":"컴퓨터공학과","email":"prof@seoultech.ac.kr","officeHours":"Upon Request","phone":"02-970-0000",
			 "office":"미래관 219","homepage":"https://example.ac.kr/prof"}
			""";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	long prof;

	String profSession;

	ApiClient professor;

	ApiClient student;

	ApiClient visitor;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, this.prof);
		this.data.enroll(this.course, stu);
		this.profSession = this.data.session(this.prof);
		this.professor = new ApiClient(this.mvc, this.profSession);
		this.student = new ApiClient(this.mvc, this.data.session(stu));
		this.visitor = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	@Test
	void instructorEditsAndMembersReadEverything() throws Exception {
		this.student.get(path(this.course))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"0\""))
			.andExpect(jsonPath("$.courseName").value("Database"))
			.andExpect(jsonPath("$.overview").doesNotExist())
			.andExpect(jsonPath("$.instructors[0].userId").value(this.prof))
			.andExpect(jsonPath("$.weeks.length()").value(0));
		put(this.professor, path(this.course), null, CONTENT).andExpect(status().isPreconditionRequired());
		// 수정은 바뀐 계획서를 GET과 같은 모양으로 돌려주고 ETag가 새 version이다. 다시 읽지 않아도 된다.
		ResultActions saved = put(this.professor, path(this.course), "\"0\"", CONTENT).andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.overview").value("관계형 모델과 SQL.\n트랜잭션."))
			.andExpect(jsonPath("$.weeks.length()").value(2))
			.andExpect(jsonPath("$.instructors[0].userId").value(this.prof));
		assertThat(AuditRows.single(this.jdbc, saved).operation()).isEqualTo("syllabus.update");
		put(this.professor, path(this.course), "\"0\"", CONTENT).andExpect(status().isConflict());
		put(this.professor, path(this.course) + "/instructors/me", "\"0\"", CARD).andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"1\""))
			.andExpect(jsonPath("$.userId").value(this.prof))
			.andExpect(jsonPath("$.name").value("테스트 PROFESSOR"))
			.andExpect(jsonPath("$.office").value("미래관 219"))
			.andExpect(jsonPath("$.version").value(1));
		put(this.professor, path(this.course) + "/instructors/me", "\"0\"", CARD).andExpect(status().isConflict());

		// 본문은 일반 텍스트 그대로 돌아온다.
		this.student.get(path(this.course))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.overview").value("관계형 모델과 SQL.\n트랜잭션."))
			.andExpect(jsonPath("$.outcomeRelevance").value("L3"))
			.andExpect(jsonPath("$.weeks[1].content").value("<script>alert(1)</script>"))
			.andExpect(jsonPath("$.weeks[1].note").doesNotExist())
			.andExpect(jsonPath("$.instructors[0].email").value("prof@seoultech.ac.kr"))
			.andExpect(jsonPath("$.instructors[0].department").value("컴퓨터공학과"));
		// 주별 계획은 통째로 바뀐다.
		put(this.professor, path(this.course), "\"1\"", "{\"weeks\":[{\"weekNo\":16,\"content\":\"기말\"}]}")
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"2\""))
			.andExpect(jsonPath("$.weeks.length()").value(1));
		this.professor.get(path(this.course))
			.andExpect(jsonPath("$.weeks.length()").value(1))
			.andExpect(jsonPath("$.weeks[0].weekNo").value(16))
			.andExpect(jsonPath("$.overview").doesNotExist());
	}

	@Test
	void visitorsSeeOnlyPublicFieldsOfRegularCourses() throws Exception {
		put(this.professor, path(this.course), "\"0\"", CONTENT).andExpect(status().isOk());
		put(this.professor, path(this.course) + "/instructors/me", "\"0\"", CARD).andExpect(status().isOk());
		this.visitor.get(path(this.course))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist("ETag"))
			.andExpect(jsonPath("$.overview").value("관계형 모델과 SQL.\n트랜잭션."))
			.andExpect(jsonPath("$.weeks.length()").value(2))
			.andExpect(jsonPath("$.instructors[0].name").value("테스트 PROFESSOR"))
			.andExpect(jsonPath("$.instructors[0].department").value("컴퓨터공학과"))
			.andExpect(jsonPath("$.instructors[0].email").doesNotExist())
			.andExpect(jsonPath("$.instructors[0].phone").doesNotExist())
			.andExpect(jsonPath("$.instructors[0].office").doesNotExist())
			.andExpect(jsonPath("$.instructors[0].homepage").doesNotExist())
			.andExpect(jsonPath("$.instructors[0].userId").value(this.prof))
			.andExpect(jsonPath("$.instructors[0].officeHours").doesNotExist())
			.andExpect(jsonPath("$.version").doesNotExist());
		// 비정규 과정과 폐강 과목의 계획서는 구성원이 아니면 없다.
		long nonRegular = this.data.insert("INSERT INTO courses (course_type, course_name) VALUES ('비정규', '특강')", Map.of());
		this.data.instructor(nonRegular, this.prof);
		this.visitor.get(path(nonRegular)).andExpect(status().isNotFound());
		this.professor.get(path(nonRegular)).andExpect(status().isOk());
		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c").param("c", this.course).update();
		this.visitor.get(path(this.course)).andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).get(path(nonRegular)).andExpect(status().isUnauthorized());
	}

	@Test
	void onlyAllowlistedPlainTextFieldsAreAccepted() throws Exception {
		// 과목명, 학점 같은 학사 값은 계획서로 고치지 않는다.
		put(this.professor, path(this.course), "\"0\"", "{\"credit\":4}").andExpect(status().isBadRequest());
		put(this.professor, path(this.course), "\"0\"", "{\"courseName\":\"바꾼 이름\"}").andExpect(status().isBadRequest());
		put(this.professor, path(this.course), "\"0\"", "{\"outcomeRelevance\":\"L9\"}").andExpect(status().isBadRequest());
		put(this.professor, path(this.course), "\"0\"", "{\"overview\":\"" + "가".repeat(4001) + "\"}")
			.andExpect(status().isBadRequest());
		put(this.professor, path(this.course), "\"0\"", "{\"overview\":\"a\\u0001b\"}").andExpect(status().isBadRequest());
		put(this.professor, path(this.course), "\"0\"", "{\"weeks\":[{\"weekNo\":1},{\"weekNo\":1}]}")
			.andExpect(status().isBadRequest());
		put(this.professor, path(this.course), "\"0\"", "{\"weeks\":[{\"weekNo\":17}]}").andExpect(status().isBadRequest());
		put(this.professor, path(this.course) + "/instructors/me", "\"0\"", "{\"homepage\":\"javascript:alert(1)\"}")
			.andExpect(status().isBadRequest());
		put(this.professor, path(this.course) + "/instructors/me", "\"0\"", "{\"email\":\"not an email\"}")
			.andExpect(status().isBadRequest());
		put(this.professor, path(this.course) + "/instructors/me", "\"0\"", "{\"userId\":1,\"email\":\"a@b\"}")
			.andExpect(status().isBadRequest());
		this.professor.get(path(this.course)).andExpect(header().string("ETag", "\"0\""));
	}

	@Test
	void authorizationMatrix() throws Exception {
		for (ApiClient other : java.util.List.of(this.student, this.visitor)) {
			put(other, path(this.course), "\"0\"", CONTENT).andExpect(status().isNotFound());
			put(other, path(this.course) + "/instructors/me", "\"0\"", CARD).andExpect(status().isNotFound());
		}
		new ApiClient(this.mvc, null).get(path(this.course)).andExpect(status().isUnauthorized());
		new ApiClient(this.mvc, null)
			.send(MockMvcRequestBuilders.put(path(this.course))
				.contentType(MediaType.APPLICATION_JSON)
				.content(CONTENT)
				.header("If-Match", "\"0\""))
			.andExpect(status().isUnauthorized());
		this.mvc.perform(MockMvcRequestBuilders.put(path(this.course))
			.cookie(new Cookie("ASTRA_SESSION", this.profSession))
			.contentType(MediaType.APPLICATION_JSON)
			.content(CONTENT)
			.header("If-Match", "\"0\"")).andExpect(status().isForbidden());
		// 다른 과목의 담당자는 이 과목의 계획서를 고치지 못한다.
		long other = this.data.course();
		long otherProf = this.data.user("PROFESSOR");
		this.data.instructor(other, otherProf);
		put(new ApiClient(this.mvc, this.data.session(otherProf)), path(this.course), "\"0\"", CONTENT)
			.andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM course_syllabi WHERE course_id = :c")
			.param("c", this.course)
			.query(Integer.class)
			.single()).isZero();
	}

	/**
	 * 강사소개는 과목의 담당자 명단(course_instructors)만 보인다(화면 41). 조교로 수강만 확정된 사람은 과목의 INSTRUCTOR라 서술 항목은
	 * 고치지만, 명단에 없으므로 자기 강사 정보를 쓰면 어디에도 보이지 않는다. 그래서 쓰기도 명단과 같게 막는다.
	 */
	@Test
	void onlyListedInstructorsWriteTheCardThatTheSyllabusShows() throws Exception {
		long ta = this.data.user("STUDENT");
		this.jdbc.sql("""
				INSERT INTO course_enrollments (course_id, user_id, enrollment_kind, enrollment_role, enrollment_status)
				VALUES (:c, :u, 'REGISTRAR', 'TA', 'ENROLLED')
				""").param("c", this.course).param("u", ta).update();
		ApiClient assistant = new ApiClient(this.mvc, this.data.session(ta));

		put(assistant, path(this.course), "\"0\"", CONTENT).andExpect(status().isOk());
		put(assistant, path(this.course) + "/instructors/me", "\"0\"", CARD).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_listed_instructor"));
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM syllabus_instructors WHERE user_id = :u")
			.param("u", ta)
			.query(Integer.class)
			.single()).isZero();
		this.student.get(path(this.course))
			.andExpect(jsonPath("$.instructors.length()").value(1))
			.andExpect(jsonPath("$.instructors[0].userId").value(this.prof));

		// 명단에 오르면 쓰고, 쓴 강사 정보가 계획서에 보인다.
		this.data.instructor(this.course, ta);
		put(assistant, path(this.course) + "/instructors/me", "\"0\"", CARD).andExpect(status().isOk());
		this.student.get(path(this.course))
			.andExpect(jsonPath("$.instructors[?(@.userId == %d)].office", ta).value("미래관 219"));
	}

	private ResultActions put(ApiClient client, String path, String ifMatch, String body) throws Exception {
		var request = MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(body);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return client.send(request);
	}

	private static String path(long courseId) {
		return "/api/courses/" + courseId + "/syllabus";
	}

}
