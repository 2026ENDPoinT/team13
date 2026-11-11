package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import tools.jackson.databind.JsonNode;

/**
 * 학생은 자기 진도를, 담당자는 수강생 전체의 진도를 본다. 진도는 적립 원장에서만 나오고 조회는 아무것도 바꾸지 않는다.
 */
class LectureProgressApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TestLearningClock clock;

	long course;

	long stu;

	long classmate;

	ApiClient student;

	ApiClient professor;

	long watched;

	long unknownLength;

	long hidden;

	long openLecture;

	long notYetOpen;

	LocalDateTime t0;

	@BeforeEach
	void lectures() {
		this.course = this.data.course();
		this.stu = this.data.user("STUDENT");
		this.classmate = this.data.user("STUDENT");
		long prof = this.data.user("PROFESSOR");
		this.data.enroll(this.course, this.stu);
		this.data.enroll(this.course, this.classmate);
		this.data.instructor(this.course, prof);
		this.student = new ApiClient(this.mvc, this.data.session(this.stu));
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		long open = this.data.activity(this.course, "lecture_weeks", true, null, null);
		this.openLecture = open;
		this.watched = item(open, 1, 300, 60);
		this.unknownLength = item(open, 2, null, null);
		this.hidden = item(this.data.activity(this.course, "lecture_weeks", false, null, null), 1, 300, 60);
		this.notYetOpen = item(this.data.activity(this.course, "lecture_weeks", true, "2999-01-01 00:00:00", null), 1,
				300, 60);
		this.t0 = this.clock.freeze();
	}

	@AfterEach
	void release() {
		this.clock.release();
	}

	@Test
	void studentReadsOwnProgressByWeekAndInstructorReadsTheRoster() throws Exception {
		long session = ApiClient.json(this.student.post(LearningSessionApiTests.start(this.course, this.watched))
			.andExpect(status().isCreated())).get("sessionId").asLong();
		this.clock.advance(Duration.ofSeconds(60));
		this.student.postJson(LearningSessionApiTests.heartbeats(this.course, session), "{\"seq\":1,\"positionSec\":42}")
			.andExpect(jsonPath("$.progress.status").value("met"));

		JsonNode weeks = ApiClient.json(this.student.get(progress(this.course))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.length()").value(1))
			.andExpect(jsonPath("$[0].itemCount").value(2))
			.andExpect(jsonPath("$[0].metCount").value(1)));
		JsonNode first = weeks.get(0).get("items").get(0);
		assertThat(first.get("itemId").asLong()).isEqualTo(this.watched);
		assertThat(first.get("progress").get("status").asString()).isEqualTo("met");
		assertThat(first.get("progress").get("creditedSec").asInt()).isEqualTo(60);
		assertThat(first.get("progress").get("resumePositionSec").asInt()).isEqualTo(42);
		assertThat(LocalDateTime.parse(first.get("progress").get("attendedAt").asString()))
			.isEqualTo(this.t0.plusSeconds(60));
		JsonNode second = weeks.get(0).get("items").get(1);
		assertThat(second.get("itemId").asLong()).isEqualTo(this.unknownLength);
		assertThat(second.get("progress").get("status").asString()).isEqualTo("unknown");

		JsonNode roster = ApiClient.json(this.professor.get(progress(this.course) + "/students")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store")));
		assertThat(roster.get("items")).hasSize(4);
		assertThat(roster.get("students")).hasSize(2);
		int column = 0;
		while (roster.get("items").get(column).get("itemId").asLong() != this.watched) {
			column++;
		}
		JsonNode mine = roster.get("students").get(0);
		assertThat(mine.get("userId").asLong()).isEqualTo(this.stu);
		assertThat(mine.get("name").asString()).isEqualTo("테스트 STUDENT");
		assertThat(mine.get("metCount").asInt()).isEqualTo(1);
		assertThat(mine.get("items")).hasSize(4);
		assertThat(mine.get("items").get(column).get("status").asString()).isEqualTo("met");
		assertThat(mine.get("items").get(column).get("resumePositionSec").isNull()).isTrue();
		JsonNode theirs = roster.get("students").get(1);
		assertThat(theirs.get("userId").asLong()).isEqualTo(this.classmate);
		assertThat(theirs.get("metCount").asInt()).isZero();
		assertThat(theirs.get("items").get(column).get("status").asString()).isEqualTo("under_required");
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM online_lecture_item_progress WHERE user_id = :u")
			.param("u", this.classmate)
			.query(Integer.class)
			.single()).isZero();

		// 지운 활동(ADR-0011 삭제 표시)의 콘텐츠는 내 진도와 수강생 진도 모두에서 사라진다.
		this.jdbc.sql("""
				UPDATE learning_activities a JOIN online_lecture_items i ON i.activity_id = a.activity_id
				   SET a.deleted_at = CURRENT_TIMESTAMP(3) WHERE i.online_lecture_item_id = :item
				""").param("item", this.watched).update();
		this.student.get(progress(this.course)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
		JsonNode remaining = ApiClient.json(this.professor.get(progress(this.course) + "/students").andExpect(status().isOk()));
		assertThat(remaining.get("items")).hasSize(2);
		assertThat(remaining.get("students").get(0).get("metCount").asInt()).isZero();
	}

	@Test
	void progressIsVisibleOnlyToMembersAndTheRosterOnlyToInstructors() throws Exception {
		ApiClient outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		outsider.get(progress(this.course)).andExpect(status().isNotFound());
		outsider.get(progress(this.course) + "/students").andExpect(status().isNotFound());
		this.student.get(progress(this.course) + "/students").andExpect(status().isNotFound());
		this.professor.get(progress(this.data.course()) + "/students").andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).get(progress(this.course)).andExpect(status().isUnauthorized());
		this.professor.get(progress(this.course))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.length()").value(3));
	}

	@Test
	void rosterPagesStudentsInRosterOrderAndFiltersByOneLecture() throws Exception {
		this.professor.get(progress(this.course) + "/students?size=1")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.totalStudents").value(2))
			.andExpect(jsonPath("$.page").value(1))
			.andExpect(jsonPath("$.size").value(1))
			.andExpect(jsonPath("$.students.length()").value(1))
			.andExpect(jsonPath("$.students[0].userId").value(this.stu))
			.andExpect(jsonPath("$.items.length()").value(4));
		this.professor.get(progress(this.course) + "/students?size=1&page=2")
			.andExpect(jsonPath("$.students[0].userId").value(this.classmate))
			.andExpect(jsonPath("$.students[0].items.length()").value(4));
		this.professor.get(progress(this.course) + "/students?size=1&page=3")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.students.length()").value(0))
			.andExpect(jsonPath("$.totalStudents").value(2));

		// 영상 활동 하나만 고르면 그 활동의 콘텐츠만 열이 되고 학생 행도 같은 열이다.
		JsonNode one = ApiClient.json(this.professor.get(progress(this.course) + "/students?activityId=" + this.openLecture)
			.andExpect(status().isOk()));
		assertThat(one.get("items")).hasSize(2);
		one.get("items").forEach((i) -> assertThat(i.get("activityId").asLong()).isEqualTo(this.openLecture));
		assertThat(one.get("students").get(0).get("items")).hasSize(2);

		// 영상 활동이 아니거나 다른 과목의 활동이면 없는 활동이다(SC-03).
		long material = this.data.activity(this.course, "lecture_material", true, null, null);
		long otherCourse = this.data.course();
		long elsewhere = this.data.activity(otherCourse, "lecture_weeks", true, null, null);
		for (long activity : new long[] { material, elsewhere, 999_999_999L }) {
			this.professor.get(progress(this.course) + "/students?activityId=" + activity).andExpect(status().isNotFound());
		}
		for (String bad : new String[] { "size=0", "size=101", "page=0" }) {
			this.professor.get(progress(this.course) + "/students?" + bad).andExpect(status().isBadRequest());
		}
	}

	private long item(long activityId, int linkSeq, Integer durationSec, Integer requiredSec) {
		Map<String, Object> params = new HashMap<>();
		params.put("a", activityId);
		params.put("seq", linkSeq);
		params.put("path", "vod/lecture-" + UUID.randomUUID().toString().substring(0, 8) + "/main.m3u8");
		params.put("duration", durationSec);
		params.put("required", requiredSec);
		return this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path, duration_sec, required_sec)
				VALUES (:a, :seq, '강의', 'video', :path, :duration, :required)
				""", params);
	}

	private static String progress(long courseId) {
		return "/api/courses/" + courseId + "/lecture-progress";
	}

}
