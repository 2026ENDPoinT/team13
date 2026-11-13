package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.identity.session.SchoolSessionRegistry;
import com.stackcorp.astra.learning.SchoolContentTests.FakeSchool;

import tools.jackson.databind.JsonNode;

/** 학교 강의실 공지: 목록은 서비스 공지 목록에, 상세와 첨부는 학생이 열 때 그 학생의 학교 session으로(ADR-0043). */
class SchoolNoticeTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	SchoolSessionRegistry registry;

	long course;

	FakeSchool school;

	ApiClient student;

	ApiClient other;

	ApiClient professor;

	ApiClient outsider;

	long studentSession;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.jdbc.sql("UPDATE courses SET legacy_kjkey = :key WHERE course_id = :id")
			.param("key", SchoolContentTests.KEY + this.course)
			.param("id", this.course)
			.update();
		long prof = this.data.user("PROFESSOR");
		long studentId = this.data.user("STUDENT");
		long otherId = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, studentId);
		this.data.enroll(this.course, otherId);
		this.student = new ApiClient(this.mvc, this.data.session(studentId));
		this.studentSession = this.jdbc.sql("SELECT MAX(session_id) FROM user_sessions WHERE user_id = :user")
			.param("user", studentId)
			.query(Long.class)
			.single();
		this.other = new ApiClient(this.mvc, this.data.session(otherId));
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		this.school = new FakeSchool(SchoolContentTests.KEY + this.course);
		assertThat(this.registry.keep(this.studentSession, this.school)).isTrue();
	}

	@Test
	void schoolNoticesFollowNativeOnesAndCountAsUnreadUntilOpenedInStack() throws Exception {
		this.professor.postJson("/api/courses/" + this.course + "/notices", "{\"title\":\"서비스 공지\",\"body\":\"본문\"}")
			.andExpect(status().isCreated());

		JsonNode page = notices(this.student, "");
		assertThat(page.path("total").asInt()).isEqualTo(3);
		JsonNode items = page.path("items");
		assertThat(items.get(0).path("noticeId").isNumber()).isTrue();
		JsonNode first = items.get(1);
		assertThat(first.path("noticeId").asText()).matches("school-[0-9]+");
		assertThat(first.path("origin").asText()).isEqualTo("school");
		assertThat(first.path("title").asText()).isEqualTo("시험 안내");
		assertThat(first.path("read").asBoolean()).isFalse();
		assertThat(first.path("viewCount").asInt()).isEqualTo(12);
		assertThat(first.path("schoolUrl").asText()).endsWith("SEQ=71");
		assertThat(items.get(2).path("expired").asBoolean()).isTrue();
		// 목록은 간격 안에 다시 읽지 않고, 목록을 읽었다고 공지를 열지 않는다.
		notices(this.student, "");
		assertThat(this.school.noticeLists.get()).isEqualTo(1);
		assertThat(this.school.noticeDetails.get()).isZero();
		// 서비스 공지 하나와 학교에서 안 읽은 학교 공지 하나.
		assertThat(unread(this.student)).isEqualTo(2);
		// 쪽 나누기와 제목 찾기는 두 목록을 이은 순서다.
		JsonNode second = notices(this.student, "&page=2&size=2");
		assertThat(second.path("items").size()).isEqualTo(1);
		assertThat(second.path("items").get(0).path("title").asText()).isEqualTo("Midterm");
		assertThat(notices(this.student, "&q=midterm").path("total").asInt()).isEqualTo(1);

		String id = first.path("noticeId").asText();
		JsonNode detail = ApiClient
			.json(this.student.get("/api/courses/" + this.course + "/notices/" + id).andExpect(status().isOk()));
		assertThat(detail.path("noticeId").asText()).isEqualTo(id);
		assertThat(detail.path("body").asText()).isEqualTo("본문");
		assertThat(detail.path("authorName").asText()).isEqualTo("담당 교수");
		assertThat(detail.path("read").asBoolean()).isTrue();
		assertThat(this.school.noticeDetails.get()).isEqualTo(1);
		assertThat(unread(this.student)).isEqualTo(1);
		assertThat(notices(this.student, "").path("items").get(1).path("read").asBoolean()).isTrue();

		JsonNode file = detail.path("files").get(0);
		assertThat(file.path("name").asText()).isEqualTo("안내.pdf");
		String location = this.student.get("/api/courses/" + this.course + "/files/" + file.path("fileId").asText()
				+ "/content")
			.andExpect(status().isSeeOther())
			.andReturn()
			.getResponse()
			.getHeader("Location");
		this.student.get(location).andExpect(status().isOk());
		// 다른 학생은 둔 목록을 보고(학교 session 없이) 받아 둔 첨부를 연다. 상세는 자기 학교 session이 있어야 읽는다.
		JsonNode otherItem = notices(this.other, "").path("items").get(1);
		assertThat(otherItem.path("noticeId").asText()).isEqualTo(id);
		assertThat(otherItem.path("read").asBoolean()).isTrue();
		this.other.get(location).andExpect(status().isOk());
		this.other.get("/api/courses/" + this.course + "/notices/" + id)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("school_signin_required"));
		assertThat(this.school.downloads.get()).isEqualTo(1);
	}

	@Test
	void outsidersGet404AndInstructorsDoNotGetSchoolNotices() throws Exception {
		JsonNode items = notices(this.student, "").path("items");
		String id = items.get(0).path("noticeId").asText();

		this.outsider.get("/api/courses/" + this.course + "/notices").andExpect(status().isNotFound());
		this.outsider.get("/api/courses/" + this.course + "/notices/" + id).andExpect(status().isNotFound());
		assertThat(notices(this.professor, "").path("total").asInt()).isZero();
		this.professor.get("/api/courses/" + this.course + "/notices/" + id).andExpect(status().isNotFound());
		this.student.get("/api/courses/" + this.course + "/notices/school-999999999").andExpect(status().isNotFound());
		assertThat(this.school.noticeDetails.get()).isZero();
	}

	@Test
	void anEndedSchoolSessionAsksToSignInAgainAndTheListStays() throws Exception {
		String id = notices(this.student, "").path("items").get(0).path("noticeId").asText();
		this.school.expired = true;

		this.student.get("/api/courses/" + this.course + "/notices/" + id)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("school_signin_required"));

		assertThat(this.registry.of(this.studentSession)).isEmpty();
		assertThat(notices(this.student, "").path("total").asInt()).isEqualTo(2);
	}

	@Test
	void noticeFilesSurviveTheFileListAndLeaveWithTheirNotice() throws Exception {
		String id = notices(this.student, "").path("items").get(0).path("noticeId").asText();
		this.student.get("/api/courses/" + this.course + "/notices/" + id).andExpect(status().isOk());
		// 파일모음 목록에는 공지 첨부가 없다. 그 목록으로 공지 첨부를 지우지 않는다.
		this.student.get("/api/courses/" + this.course + "/materials").andExpect(status().isOk());
		assertThat(noticeFiles()).isEqualTo(1);

		this.school.notices = List.of(this.school.notices.get(1));
		this.jdbc.sql("UPDATE school_notice_lists SET listed_at = DATE_SUB(listed_at, INTERVAL 1 DAY)").update();
		JsonNode page = notices(this.student, "");

		assertThat(page.path("total").asInt()).isEqualTo(1);
		assertThat(noticeFiles()).isZero();
		assertThat(unread(this.student)).isZero();
		this.student.get("/api/courses/" + this.course + "/notices/" + id).andExpect(status().isNotFound());
	}

	private int noticeFiles() {
		return this.jdbc.sql("SELECT COUNT(*) FROM school_files WHERE course_id = :course AND school_type = 'notice'")
			.param("course", this.course)
			.query(Integer.class)
			.single();
	}

	private JsonNode notices(ApiClient client, String query) throws Exception {
		return ApiClient.json(client.get("/api/courses/" + this.course + "/notices?x=1" + query)
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store")));
	}

	private int unread(ApiClient client) throws Exception {
		JsonNode courses = ApiClient.json(client.get("/api/me/courses").andExpect(status().isOk()));
		for (JsonNode c : courses) {
			if (c.path("courseId").asLong() == this.course) {
				return c.path("unreadNotices").asInt();
			}
		}
		throw new AssertionError("course not listed");
	}

}
