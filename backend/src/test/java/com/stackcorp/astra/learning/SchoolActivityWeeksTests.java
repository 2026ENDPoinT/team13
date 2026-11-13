package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolActivity;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolCourseRoom;
import com.stackcorp.astra.identity.api.SchoolCourseRooms.SchoolWeek;

import tools.jackson.databind.JsonNode;

/** 학교 강의실에서 가져온 목록의 저장과 강의실 주차 목록 병합(ADR-0042). */
class SchoolActivityWeeksTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	SchoolActivities store;

	long course;

	long studentId;

	long otherId;

	ApiClient student;

	ApiClient other;

	ApiClient professor;

	ApiClient outsider;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.studentId = this.data.user("STUDENT");
		this.otherId = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, this.studentId);
		this.data.enroll(this.course, this.otherId);
		this.student = new ApiClient(this.mvc, this.data.session(this.studentId));
		this.other = new ApiClient(this.mvc, this.data.session(this.otherId));
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	@Test
	void schoolRowsJoinNativeWeeksForStudentsOnly() throws Exception {
		long nativeWeek = this.data.activity(this.course, "lecture_material", true, null, null);
		int nativeNo = this.jdbc.sql("""
				SELECT w.week_no FROM learning_activities a JOIN course_weeks w ON w.course_week_id = a.course_week_id
				 WHERE a.activity_id = :id
				""").param("id", nativeWeek).query(Integer.class).single();
		this.store.save(this.course, this.studentId, room(week(1, activity("report", "11", true)),
				week(nativeNo, activity("lecture_material", "12", false))));

		JsonNode weeks = weeks(this.student);

		assertThat(weeks.size()).isEqualTo(2);
		assertThat(weeks.get(0).path("weekNo").asInt()).isEqualTo(1);
		assertThat(weeks.get(0).path("startsOn").asText()).isEqualTo("2026-09-01");
		JsonNode shared = weeks.get(1).path("activities");
		assertThat(shared.get(0).path("activityId").asLong()).isEqualTo(nativeWeek);
		assertThat(shared.get(0).has("origin")).isFalse();
		assertThat(shared.get(1).path("origin").asText()).isEqualTo("school");
		assertThat(shared.get(1).path("type").asText()).isEqualTo("material");
		assertThat(weeks.get(1).path("startsOn").asText()).isEqualTo("2026-09-01");
		JsonNode report = weeks.get(0).path("activities").get(0);
		assertThat(report.path("done").asBoolean()).isTrue();
		assertThat(report.path("phase").asText()).isEqualTo("open");
		assertThat(report.path("published").asBoolean()).isTrue();
		// 다른 학생은 행을 보지만 본인 상태는 모른다.
		assertThat(weeks(this.other).get(0).path("activities").get(0).path("done").isNull()).isTrue();
		// 담당자에게는 학교 행과 학교에만 있는 주차가 없다.
		JsonNode taught = weeks(this.professor);
		assertThat(taught.size()).isEqualTo(1);
		assertThat(taught.get(0).path("activities").size()).isEqualTo(1);
		this.outsider.get("/api/courses/" + this.course + "/weeks").andExpect(status().isNotFound());
	}

	@Test
	void rowsMissingFromANonEmptyPageAreRemovedButAnEmptyPageRemovesNothing() throws Exception {
		this.store.save(this.course, this.studentId,
				room(week(1, activity("report", "21", false), activity("survey", "22", false)), week(2)));
		this.store.save(this.course, this.otherId, room(week(1, activity("survey", "22", true))));

		JsonNode weeks = weeks(this.student);
		assertThat(weeks.size()).isEqualTo(1);
		assertThat(weeks.get(0).path("activities").size()).isEqualTo(1);
		assertThat(weeks.get(0).path("activities").get(0).path("schoolType").asText()).isEqualTo("survey");
		assertThat(weeks.get(0).path("activities").get(0).path("done").asBoolean()).isFalse();

		this.store.save(this.course, this.otherId, room(week(1)));
		this.store.save(this.course, this.otherId, room());

		assertThat(weeks(this.student).get(0).path("activities").size()).isEqualTo(1);
	}

	@Test
	void anImportWithinTheIntervalIsNotClaimedTwice() {
		long user = this.data.user("STUDENT");

		assertThat(this.store.claim(user, 600)).isTrue();
		assertThat(this.store.claim(user, 600)).isFalse();
		assertThat(this.store.claim(user, 0)).isTrue();
	}

	private JsonNode weeks(ApiClient client) throws Exception {
		return ApiClient.json(client.get("/api/courses/" + this.course + "/weeks").andExpect(status().isOk()))
			.path("weeks");
	}

	private static SchoolCourseRoom room(SchoolWeek... weeks) {
		return new SchoolCourseRoom(List.of(weeks));
	}

	private static SchoolWeek week(int no, SchoolActivity... activities) {
		return new SchoolWeek(no, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 7), List.of(activities));
	}

	private static SchoolActivity activity(String type, String item, boolean done) {
		return new SchoolActivity(type, null, item, "학교 " + type, null, LocalDateTime.of(2099, 1, 1, 0, 0), false,
				null, null, false, done, "https://eclass.example.ac.kr/ilos/mp/todo_list_connect.acl?SEQ=" + item);
	}

}
