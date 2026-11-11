package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

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
 * 담당자의 영상 활동 목록. 주차 순서로 쪽을 나누고, 콘텐츠마다 영상 처리 상태를 준다. 지운 활동과 콘텐츠는 빠진다.
 */
class LectureListApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	ApiClient professor;

	ApiClient student;

	long first;

	long second;

	long third;

	@BeforeEach
	void lectures() {
		this.course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, stu);
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.student = new ApiClient(this.mvc, this.data.session(stu));
		this.third = lecture(3, false);
		this.first = lecture(1, true);
		this.second = lecture(2, true);
		this.jdbc.sql("UPDATE learning_activities SET description = :d WHERE activity_id = :a")
			.param("d", "1주차 안내\n둘째 줄")
			.param("a", this.first)
			.update();
		item(this.first, 1, "vod/ready-" + UUID.randomUUID().toString().substring(0, 8) + "/main.m3u8", "ready");
		item(this.first, 2, null, "processing");
		item(this.second, 1, null, "failed");
		long removed = item(this.second, 2, null, "processing");
		this.jdbc.sql("UPDATE online_lecture_items SET deleted_at = CURRENT_TIMESTAMP(3) WHERE online_lecture_item_id = :i")
			.param("i", removed)
			.update();
		long deleted = lecture(4, true);
		this.jdbc.sql("UPDATE learning_activities SET deleted_at = CURRENT_TIMESTAMP(3) WHERE activity_id = :a")
			.param("a", deleted)
			.update();
		this.data.activity(this.course, "lecture_material", true, null, null);
	}

	@Test
	void instructorPagesThroughLecturesInWeekOrderWithVideoStates() throws Exception {
		JsonNode page = ApiClient.json(this.professor.get(list(this.course) + "?size=2")
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items.length()").value(2))
			.andExpect(jsonPath("$.items[0].activityId").value(this.first))
			// 수정(PUT)이 빠진 설명을 지우므로 목록에도 상세와 같은 설명이 있다. 없으면 null이다.
			.andExpect(jsonPath("$.items[0].description").value("1주차 안내\n둘째 줄"))
			.andExpect(jsonPath("$.items[1].description").value(nullValue()))
			.andExpect(jsonPath("$.items[0].items.length()").value(2))
			.andExpect(jsonPath("$.items[0].items[0].state").value("ready"))
			.andExpect(jsonPath("$.items[0].items[1].state").value("processing"))
			.andExpect(jsonPath("$.items[1].activityId").value(this.second))
			.andExpect(jsonPath("$.items[1].items.length()").value(1))
			.andExpect(jsonPath("$.items[1].items[0].state").value("failed")));
		String cursor = page.get("nextCursor").asString();

		this.professor.get(list(this.course) + "?size=2&cursor=" + cursor)
			.andExpect(jsonPath("$.items.length()").value(1))
			.andExpect(jsonPath("$.items[0].activityId").value(this.third))
			.andExpect(jsonPath("$.items[0].published").value(false))
			.andExpect(jsonPath("$.items[0].items.length()").value(0))
			.andExpect(jsonPath("$.nextCursor").doesNotExist());
		// 꼭 맞는 쪽이면 다음 쪽이 없다.
		this.professor.get(list(this.course) + "?size=3").andExpect(jsonPath("$.nextCursor").doesNotExist());
		this.professor.get(list(this.course) + "?size=0").andExpect(status().isBadRequest());
		this.professor.get(list(this.course) + "?size=101").andExpect(status().isBadRequest());
		this.professor.get(list(this.course) + "?cursor=1.1;DROP").andExpect(status().isBadRequest());
	}

	@Test
	void onlyTheCourseInstructorsSeeTheList() throws Exception {
		long otherCourse = this.data.course();
		long otherProf = this.data.user("PROFESSOR");
		this.data.instructor(otherCourse, otherProf);
		ApiClient otherProfessor = new ApiClient(this.mvc, this.data.session(otherProf));
		ApiClient outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));

		this.student.get(list(this.course)).andExpect(status().isNotFound());
		outsider.get(list(this.course)).andExpect(status().isNotFound());
		otherProfessor.get(list(this.course)).andExpect(status().isNotFound());
		// 자기 과목 경로로는 자기 과목 활동만 본다.
		JsonNode own = ApiClient.json(otherProfessor.get(list(otherCourse)).andExpect(status().isOk()));
		assertThat(own.get("items")).isEmpty();
		new ApiClient(this.mvc, null).get(list(this.course)).andExpect(status().isUnauthorized());

		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c").param("c", this.course).update();
		this.professor.get(list(this.course)).andExpect(status().isNotFound());
	}

	private long lecture(int weekNo, boolean published) {
		long activity = this.data.activity(this.course, "lecture_weeks", published, null, null);
		this.jdbc.sql("""
				UPDATE course_weeks w JOIN learning_activities a ON a.course_week_id = w.course_week_id
				   SET w.week_no = :week WHERE a.activity_id = :a
				""").param("week", weekNo).param("a", activity).update();
		return activity;
	}

	private long item(long activityId, int linkSeq, String contentPath, String videoState) {
		Map<String, Object> params = new HashMap<>();
		params.put("a", activityId);
		params.put("seq", linkSeq);
		params.put("path", contentPath);
		params.put("state", videoState);
		return this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path, video_state)
				VALUES (:a, :seq, '강의', 'video', :path, :state)
				""", params);
	}

	private static String list(long courseId) {
		return "/api/courses/" + courseId + "/lectures";
	}

}
