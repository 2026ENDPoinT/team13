package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
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
 * 과목 그룹(EP-0039, EP-0040 대체 계약). 담당자가 관리하고, 학생은 자기 그룹과 그 구성원의 이름만 본다.
 */
class CourseGroupApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	long otherCourse;

	long prof;

	long stu;

	long mate;

	long third;

	String profSession;

	ApiClient professor;

	ApiClient student;

	ApiClient classmate;

	ApiClient thirdStudent;

	ApiClient outsider;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.otherCourse = this.data.course();
		this.prof = this.data.user("PROFESSOR");
		this.stu = this.data.user("STUDENT");
		this.mate = this.data.user("STUDENT");
		this.third = this.data.user("STUDENT");
		this.data.instructor(this.course, this.prof);
		this.data.instructor(this.otherCourse, this.prof);
		for (long user : List.of(this.stu, this.mate, this.third)) {
			this.data.enroll(this.course, user);
		}
		this.data.enroll(this.otherCourse, this.stu);
		this.profSession = this.data.session(this.prof);
		this.professor = new ApiClient(this.mvc, this.profSession);
		this.student = new ApiClient(this.mvc, this.data.session(this.stu));
		this.classmate = new ApiClient(this.mvc, this.data.session(this.mate));
		this.thirdStudent = new ApiClient(this.mvc, this.data.session(this.third));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	@Test
	void instructorManagesGroupsAndMembersSeeOnlyTheirOwn() throws Exception {
		long alpha = create("1조").get("groupId").asLong();
		long beta = create("2조").get("groupId").asLong();
		this.professor.postJson(groups(this.course), "{\"name\":\"1조\"}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("group_exists"));
		this.data.photo(this.stu);
		addMember(this.professor, alpha, this.stu).andExpect(status().isOk())
			.andExpect(jsonPath("$.members[0].userId").value(this.stu))
			.andExpect(jsonPath("$.members[0].hasPhoto").value(true));
		addMember(this.professor, alpha, this.stu).andExpect(status().isOk())
			.andExpect(jsonPath("$.members.length()").value(1));
		addMember(this.professor, alpha, this.mate);
		addMember(this.professor, beta, this.third);

		// 학생은 자기 그룹과 그 구성원의 이름만 본다. 학번과 사용자 ID는 없다.
		this.student.get(groups(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("1조"))
			.andExpect(jsonPath("$[0].members.length()").value(2))
			.andExpect(jsonPath("$[0].members[0].name").value("테스트 STUDENT"))
			.andExpect(jsonPath("$[0].members[0].userId").doesNotExist())
			.andExpect(jsonPath("$[0].members[0].studentNo").doesNotExist())
			// 사용자 ID가 없으면 아바타를 부를 수 없으므로 사진 유무도 false다.
			.andExpect(jsonPath("$[0].members[0].hasPhoto").value(false));
		this.thirdStudent.get(groups(this.course))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].name").value("2조"))
			.andExpect(jsonPath("$[0].members.length()").value(1));
		this.professor.get(groups(this.course))
			.andExpect(jsonPath("$.length()").value(2))
			.andExpect(jsonPath("$[0].members[0].studentNo").exists())
			.andExpect(jsonPath("$[0].members[0].userId").value(this.stu))
			.andExpect(jsonPath("$[0].members[0].hasPhoto").value(true))
			.andExpect(jsonPath("$[0].members[1].hasPhoto").value(false));
		// 다른 과목에서는 이 과목의 그룹이 보이지 않는다.
		this.student.get(groups(this.otherCourse)).andExpect(jsonPath("$.length()").value(0));

		ResultActions moved = this.professor.postJson(member(alpha, this.mate) + "/move", "{\"toGroupId\":" + beta + "}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.groupId").value(beta))
			.andExpect(jsonPath("$.members.length()").value(2));
		assertThat(AuditRows.single(this.jdbc, moved).operation()).isEqualTo("course_group.member_move");
		this.professor.postJson(member(alpha, this.mate) + "/move", "{\"toGroupId\":" + beta + "}")
			.andExpect(status().isNotFound());
		this.classmate.get(groups(this.course)).andExpect(jsonPath("$[0].name").value("2조"));
		this.professor.send(MockMvcRequestBuilders.delete(member(beta, this.third))).andExpect(status().isNoContent());
		this.thirdStudent.get(groups(this.course)).andExpect(jsonPath("$.length()").value(0));

		put(group(alpha), "\"1\"", "{\"name\":\"1조(데이터)\",\"description\":\"설명\"}").andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"2\""))
			.andExpect(jsonPath("$.name").value("1조(데이터)"));
		put(group(alpha), "\"1\"", "{\"name\":\"다시\"}").andExpect(status().isConflict());
		put(group(alpha), null, "{\"name\":\"다시\"}").andExpect(status().isPreconditionRequired());
		put(group(alpha), "\"2\"", "{\"name\":\"2조\"}").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("group_exists"));
		put(group(alpha), "\"2\"", "{\"name\":\"<b>\\u0000</b>\"}").andExpect(status().isBadRequest());

		this.professor.send(MockMvcRequestBuilders.delete(group(alpha)).header("If-Match", "\"2\""))
			.andExpect(status().isNoContent());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM course_group_members WHERE course_group_id = :g")
			.param("g", alpha)
			.query(Integer.class)
			.single()).isZero();
		this.student.get(groups(this.course)).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void groupsUsedByAttendanceAreNotDeletedAndMembersMustBeStudents() throws Exception {
		long alpha = create("A반").get("groupId").asLong();
		long session = AttendanceApiTests.sessionId(this.professor
			.postJson(AttendanceApiTests.sessions(this.course), AttendanceApiTests.sessionBody(1, 1, ""))
			.andExpect(status().isCreated()));
		this.professor.postJson(AttendanceApiTests.sessionPath(this.course, session) + "/smart-rounds",
				"{\"method\":\"pin\",\"groupId\":" + alpha + "}")
			.andExpect(status().isCreated());
		this.professor.send(MockMvcRequestBuilders.delete(group(alpha)).header("If-Match", "\"1\""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("group_in_use"));
		long stranger = this.data.user("STUDENT");
		addMember(this.professor, alpha, stranger).andExpect(status().isNotFound());
		addMember(this.professor, alpha, this.prof).andExpect(status().isNotFound());
		long foreign = this.data.insert("INSERT INTO course_groups (course_id, group_name) VALUES (:c, '남의 조')",
				Map.of("c", this.otherCourse));
		this.data.insert("INSERT INTO course_group_members (course_group_id, user_id) VALUES (:g, :u)",
				Map.of("g", foreign, "u", this.stu));
		// 다른 과목의 그룹으로는 옮기지 못하고, 다른 과목 경로로는 이 과목의 그룹에 닿지 않는다.
		addMember(this.professor, alpha, this.mate);
		this.professor.postJson(member(alpha, this.mate) + "/move", "{\"toGroupId\":" + foreign + "}")
			.andExpect(status().isNotFound());
		this.professor
			.send(MockMvcRequestBuilders.put("/api/courses/" + this.otherCourse + "/groups/" + alpha + "/members/" + this.stu))
			.andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM course_group_members WHERE course_group_id = :g AND user_id = :u")
			.param("g", alpha)
			.param("u", this.mate)
			.query(Integer.class)
			.single()).isEqualTo(1);
	}

	@Test
	void oppositeMovesDoNotDeadlock() throws Exception {
		long alpha = create("가").get("groupId").asLong();
		long beta = create("나").get("groupId").asLong();
		addMember(this.professor, alpha, this.stu);
		addMember(this.professor, beta, this.mate);

		List<Integer> statuses = LearningSessionApiTests.parallel(2, (i) -> () -> (i == 0
				? this.professor.postJson(member(alpha, this.stu) + "/move", "{\"toGroupId\":" + beta + "}")
				: this.professor.postJson(member(beta, this.mate) + "/move", "{\"toGroupId\":" + alpha + "}"))
			.andReturn()
			.getResponse()
			.getStatus());

		assertThat(statuses).containsOnly(200);
		assertThat(members(alpha)).containsExactly(this.mate);
		assertThat(members(beta)).containsExactly(this.stu);
	}

	@Test
	void authorizationMatrix() throws Exception {
		long alpha = create("1조").get("groupId").asLong();
		this.outsider.get(groups(this.course)).andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).get(groups(this.course)).andExpect(status().isUnauthorized());
		for (ApiClient other : List.of(this.student, this.outsider)) {
			other.postJson(groups(this.course), "{\"name\":\"몰래\"}").andExpect(status().isNotFound());
			other.send(MockMvcRequestBuilders.put(group(alpha))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"몰래\"}")
				.header("If-Match", "\"1\"")).andExpect(status().isNotFound());
			other.send(MockMvcRequestBuilders.delete(group(alpha)).header("If-Match", "\"1\""))
				.andExpect(status().isNotFound());
			addMember(other, alpha, this.stu).andExpect(status().isNotFound());
			other.send(MockMvcRequestBuilders.delete(member(alpha, this.stu))).andExpect(status().isNotFound());
		}
		this.professor
			.send(MockMvcRequestBuilders.put("/api/courses/" + this.otherCourse + "/groups/" + alpha)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"몰래\"}")
				.header("If-Match", "\"1\""))
			.andExpect(status().isNotFound());
		this.mvc.perform(MockMvcRequestBuilders.put(member(alpha, this.stu)).cookie(new Cookie("ASTRA_SESSION", this.profSession)))
			.andExpect(status().isForbidden());
		this.mvc.perform(MockMvcRequestBuilders.post(groups(this.course))
			.cookie(new Cookie("ASTRA_SESSION", this.profSession))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"csrf\"}")).andExpect(status().isForbidden());
		new ApiClient(this.mvc, null).postJson(groups(this.course), "{\"name\":\"익명\"}").andExpect(status().isUnauthorized());
		assertThat(members(alpha)).isEmpty();
		this.professor.get(groups(this.course)).andExpect(jsonPath("$.length()").value(1));
	}

	private tools.jackson.databind.JsonNode create(String name) throws Exception {
		return ApiClient.json(this.professor.postJson(groups(this.course), "{\"name\":\"" + name + "\",\"type\":\"activity\"}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.type").value("activity")));
	}

	private ResultActions addMember(ApiClient client, long groupId, long userId) throws Exception {
		return client.send(MockMvcRequestBuilders.put(member(groupId, userId)));
	}

	private ResultActions put(String path, String ifMatch, String body) throws Exception {
		var request = MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(body);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.professor.send(request);
	}

	private List<Long> members(long groupId) {
		return this.jdbc.sql("SELECT user_id FROM course_group_members WHERE course_group_id = :g")
			.param("g", groupId)
			.query(Long.class)
			.list();
	}

	private static String groups(long courseId) {
		return "/api/courses/" + courseId + "/groups";
	}

	private String group(long groupId) {
		return groups(this.course) + "/" + groupId;
	}

	private String member(long groupId, long userId) {
		return group(groupId) + "/members/" + userId;
	}

}
