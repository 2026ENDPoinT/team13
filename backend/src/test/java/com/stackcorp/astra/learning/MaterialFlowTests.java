package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import tools.jackson.databind.JsonNode;

/**
 * 담당자가 올린 자료를 수강생이 보는 흐름. 권한은 과목 관계와 활동의 공개·시작 일시로만 정해지고, 요청이 보낸 값으로는 바뀌지 않는다.
 */
class MaterialFlowTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	ApiClient professor;

	ApiClient student;

	ApiClient outsider;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, stu);
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.student = new ApiClient(this.mvc, this.data.session(stu));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	@Test
	void instructorRegistersAMaterialAndEnrolledStudentReadsIt() throws Exception {
		byte[] pdf = TestPdfs.pdf(2);
		long uploadId = this.professor.uploaded(this.course, "week9.pdf", pdf);
		JsonNode material = ApiClient.json(this.professor
			.postJson(materials(this.course),
					"{\"weekNo\":9,\"title\":\"  9주차 정규화 \",\"description\":\"<b>3NF</b>까지\",\"uploadIds\":[" + uploadId + "]}")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value("9주차 정규화"))
			.andExpect(jsonPath("$.weekNo").value(9))
			.andExpect(jsonPath("$.published").value(true))
			.andExpect(jsonPath("$.files[0].name").value("week9.pdf"))
			.andExpect(jsonPath("$.files[0].pdf").value(true)));
		long activityId = material.get("activityId").asLong();
		long fileId = material.get("files").get(0).get("fileId").asLong();
		this.professor.get("/api/uploads/" + uploadId).andExpect(jsonPath("$.state").value("attached"));

		this.student.get(materials(this.course))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.serverTime").isNotEmpty())
			.andExpect(jsonPath("$.items.length()").value(1))
			// 설명은 저장한 텍스트 그대로 돌려준다. 화면은 텍스트로만 그린다.
			.andExpect(jsonPath("$.items[0].description").value("<b>3NF</b>까지"))
			.andExpect(jsonPath("$.items[0].phase").value("open"))
			.andExpect(jsonPath("$.items[0].files[0].fileId").value(fileId))
			.andExpect(jsonPath("$.items[0].files[0].summaryPublished").value(false));
		this.student.get("/api/courses/" + this.course + "/weeks")
			.andExpect(jsonPath("$.weeks[?(@.weekNo == 9)].activities[0].activityId").value((int) activityId))
			.andExpect(jsonPath("$.weeks[?(@.weekNo == 9)].activities[0].phase").value("open"));
		this.student.get("/api/courses/" + this.course + "/activities/" + activityId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("lecture_material"))
			.andExpect(jsonPath("$.phase").value("open"))
			.andExpect(jsonPath("$.serverTime").isNotEmpty())
			.andExpect(jsonPath("$.files[0].fileId").value(fileId));

		this.student.get(contentPath(this.course, fileId))
			.andExpect(status().isOk())
			.andExpect(content().bytes(pdf))
			.andExpect(content().contentType("application/pdf"))
			.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
	}

	@Test
	void hidesMaterialsFilesAndWeeksFromPeopleOutsideTheCourseAndFromOtherCoursePaths() throws Exception {
		Registered registered = register("{\"weekNo\":3,\"title\":\"3주차\",\"uploadIds\":[%d]}");
		long otherCourse = this.data.course();

		this.outsider.get(materials(this.course)).andExpect(status().isNotFound());
		this.outsider.get("/api/courses/" + this.course + "/weeks").andExpect(status().isNotFound());
		this.outsider.get("/api/courses/" + this.course + "/activities/" + registered.activityId)
			.andExpect(status().isNotFound());
		this.outsider.get(contentPath(this.course, registered.fileId)).andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).get(contentPath(this.course, registered.fileId)).andExpect(status().isUnauthorized());
		// 수강하는 다른 과목의 경로로는 이 과목의 활동과 파일에 닿지 않는다(SC-03).
		long stu = this.data.user("STUDENT");
		this.data.enroll(this.course, stu);
		this.data.enroll(otherCourse, stu);
		ApiClient both = new ApiClient(this.mvc, this.data.session(stu));
		both.get("/api/courses/" + otherCourse + "/activities/" + registered.activityId).andExpect(status().isNotFound());
		both.get(contentPath(otherCourse, registered.fileId)).andExpect(status().isNotFound());
		both.get(contentPath(this.course, registered.fileId)).andExpect(status().isOk());
	}

	@Test
	void keepsUnpublishedAndNotYetOpenMaterialsFromStudents() throws Exception {
		Registered hidden = register("{\"weekNo\":4,\"title\":\"비공개 자료\",\"published\":false,\"uploadIds\":[%d]}");
		Registered future = register("{\"weekNo\":5,\"title\":\"다음 주 자료\",\"openAt\":\"2999-01-01T09:00:00\",\"uploadIds\":[%d]}");

		this.professor.get(materials(this.course)).andExpect(jsonPath("$.items.length()").value(2));
		this.student.get(materials(this.course)).andExpect(jsonPath("$.items.length()").value(0));
		// 주차 목록도 다른 학생 목록과 같이 공개했고 시작한 활동만 보인다. 시작 전 활동은 제목도 기간도 없다(SC-06).
		String weeks = this.student.get("/api/courses/" + this.course + "/weeks")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.weeks[?(@.weekNo == 4)].activities[*]").isEmpty())
			.andExpect(jsonPath("$.weeks[?(@.weekNo == 5)].activities[*]").isEmpty())
			.andReturn()
			.getResponse()
			.getContentAsString(StandardCharsets.UTF_8);
		assertThat(weeks).doesNotContain("다음 주 자료", "2999-01-01");
		// 담당자에게는 보이고, 서버 시계로 정한 구간이 시작 전이다.
		this.professor.get("/api/courses/" + this.course + "/weeks")
			.andExpect(jsonPath("$.weeks[?(@.weekNo == 5)].activities[0].title").value("다음 주 자료"))
			.andExpect(jsonPath("$.weeks[?(@.weekNo == 5)].activities[0].phase").value("not_open"));
		this.professor.get(materials(this.course))
			.andExpect(jsonPath("$.items[?(@.title == '다음 주 자료')].phase").value("not_open"));
		for (Registered r : new Registered[] { hidden, future }) {
			this.student.get("/api/courses/" + this.course + "/activities/" + r.activityId).andExpect(status().isNotFound());
			this.student.get(contentPath(this.course, r.fileId)).andExpect(status().isNotFound());
			this.professor.get(contentPath(this.course, r.fileId)).andExpect(status().isOk());
		}
	}

	@Test
	void refusesRegistrationByStudentsAndRequestsCarryingActorOrUnknownFields() throws Exception {
		this.student.postJson(materials(this.course), "{\"weekNo\":1,\"title\":\"학생이 올린 자료\"}")
			.andExpect(status().isNotFound());
		this.professor.postJson(materials(this.data.course()), "{\"weekNo\":1,\"title\":\"남의 과목\"}")
			.andExpect(status().isNotFound());
		this.professor.postJson(materials(this.course), "{\"weekNo\":1,\"title\":\"자료\",\"userId\":1}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
		this.professor.postJson(materials(this.course), "{\"weekNo\":0,\"title\":\"자료\"}").andExpect(status().isBadRequest());
		this.professor.postJson(materials(this.course), "{\"weekNo\":1,\"title\":\"  \"}").andExpect(status().isBadRequest());
		this.professor.postJson(materials(this.course), "{\"weekNo\":1,\"title\":\"자료\",\"uploadIds\":[1,1]}")
			.andExpect(status().isBadRequest());
		this.professor.postJson(materials(this.course), "{not json").andExpect(status().isBadRequest());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_activities WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void attachesOnlyTheCallersOwnUnusedUploadForTheSameCourse() throws Exception {
		byte[] pdf = TestPdfs.pdf(1);
		long colleagueId = this.data.user("PROFESSOR");
		this.data.instructor(this.course, colleagueId);
		long otherCourse = this.data.course();
		this.jdbc.sql("""
				INSERT INTO course_instructors (course_id, user_id, instructor_role)
				SELECT :other, user_id, 'PROFESSOR' FROM course_instructors WHERE course_id = :course
				""").param("other", otherCourse).param("course", this.course).update();
		ApiClient colleague = new ApiClient(this.mvc, this.data.session(colleagueId));

		long theirs = colleague.uploaded(this.course, "theirs.pdf", pdf);
		long elsewhere = this.professor.uploaded(otherCourse, "elsewhere.pdf", pdf);
		long deleted = this.professor.uploaded(this.course, "deleted.pdf", pdf);
		this.professor.delete("/api/uploads/" + deleted).andExpect(status().isNoContent());
		long used = this.professor.uploaded(this.course, "used.pdf", pdf);
		String body = "{\"weekNo\":2,\"title\":\"2주차\",\"uploadIds\":[%d]}";
		this.professor.postJson(materials(this.course), body.formatted(used)).andExpect(status().isCreated());

		for (long uploadId : new long[] { theirs, elsewhere, deleted, used, 999_999_999L }) {
			this.professor.postJson(materials(this.course), body.formatted(uploadId))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		}
		// 실패한 등록은 활동도 첨부도 남기지 않는다.
		this.professor.get(materials(this.course)).andExpect(jsonPath("$.items.length()").value(1));
		// 좋은 업로드와 나쁜 업로드를 함께 보내도 좋은 쪽이 소비되지 않는다.
		long good = this.professor.uploaded(this.course, "good.pdf", pdf);
		this.professor.postJson(materials(this.course), "{\"weekNo\":2,\"title\":\"섞음\",\"uploadIds\":[%d,%d]}".formatted(good, theirs))
			.andExpect(status().isConflict());
		this.professor.get("/api/uploads/" + good).andExpect(jsonPath("$.state").value("clean"));
	}

	@Test
	void registersAMaterialWhoseTitleIsLongerThanTheAttachmentGroupLabel() throws Exception {
		// 자료 제목은 300자까지 받는다. 첨부 그룹 표시명 칸(200자)보다 길어도 등록되고 제목은 그대로 남는다(#15).
		String title = "가".repeat(199) + "😀" + "나".repeat(50);
		long uploadId = this.professor.uploaded(this.course, "long.pdf", TestPdfs.pdf(1));
		JsonNode material = ApiClient.json(this.professor
			.postJson(materials(this.course),
					"{\"weekNo\":3,\"title\":\"%s\",\"uploadIds\":[%d]}".formatted(title, uploadId))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.title").value(title)));
		long activityId = material.get("activityId").asLong();
		assertThat(this.jdbc.sql("SELECT title FROM learning_activities WHERE activity_id = :id")
			.param("id", activityId)
			.query(String.class)
			.single()).isEqualTo(title);
		// 첨부 그룹은 ID로만 가리킨다. 표시명은 쓰지 않는다.
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM learning_activities a
				JOIN attachment_groups g ON g.attachment_group_id = a.attachment_group_id
				WHERE a.activity_id = :id AND g.group_title IS NULL
				""").param("id", activityId).query(Long.class).single()).isEqualTo(1L);
	}

	private Registered register(String body) throws Exception {
		long uploadId = this.professor.uploaded(this.course, "material.pdf", TestPdfs.pdf(1));
		JsonNode material = ApiClient
			.json(this.professor.postJson(materials(this.course), body.formatted(uploadId)).andExpect(status().isCreated()));
		return new Registered(material.get("activityId").asLong(), material.get("files").get(0).get("fileId").asLong());
	}

	private static String materials(long courseId) {
		return "/api/courses/" + courseId + "/materials";
	}

	private static String contentPath(long courseId, long fileId) {
		return "/api/courses/" + courseId + "/files/" + fileId + "/content";
	}

	private record Registered(long activityId, long fileId) {
	}

}
