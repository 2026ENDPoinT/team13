package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.files.api.VideoKeys;

import jakarta.servlet.http.Cookie;

/**
 * HLS AES-128 key는 그 강의를 볼 수 있는 사람에게만, 캐시하지 않고 내보낸다(ADR-0007 8번).
 */
class LectureKeyApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	VideoKeys keys;

	long course;

	long activity;

	long item;

	byte[] key;

	Cookie student;

	Cookie professor;

	@BeforeEach
	void lecture() {
		this.course = this.data.course();
		long stu = this.data.user("STUDENT");
		long prof = this.data.user("PROFESSOR");
		this.data.enroll(this.course, stu);
		this.data.instructor(this.course, prof);
		this.activity = this.data.activity(this.course, "lecture_weeks", true, null, null);
		String asset = "lecture-" + UUID.randomUUID().toString().substring(0, 8);
		this.key = this.keys.ensure(asset);
		this.item = this.data.insert("""
				INSERT INTO online_lecture_items (activity_id, link_seq, title, content_kind, content_path)
				VALUES (:a, 1, '9주차 강의', 'video', :path)
				""", Map.of("a", this.activity, "path", "vod/" + asset + "/main.m3u8"));
		this.student = new Cookie("ASTRA_SESSION", this.data.session(stu));
		this.professor = new Cookie("ASTRA_SESSION", this.data.session(prof));
	}

	@Test
	void givesEnrolledStudentTheKeyWithoutCaching() throws Exception {
		byte[] body = this.mvc.perform(get(path(this.course)).cookie(this.student))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();

		assertThat(body).hasSize(16).isEqualTo(this.key);
	}

	@Test
	void keepsKeyOnceCreated() {
		String asset = "lecture-" + UUID.randomUUID().toString().substring(0, 8);

		assertThat(this.keys.ensure(asset)).isEqualTo(this.keys.ensure(asset));
	}

	@Test
	void hidesKeyFromOutsidersOtherCoursePathsAndNotYetOpenLectures() throws Exception {
		Cookie outsider = new Cookie("ASTRA_SESSION", this.data.session(this.data.user("STUDENT")));
		this.mvc.perform(get(path(this.course)).cookie(outsider)).andExpect(status().isNotFound());
		this.mvc.perform(get(path(this.data.course())).cookie(this.student)).andExpect(status().isNotFound());
		this.mvc.perform(get(path(this.course))).andExpect(status().isUnauthorized());

		this.jdbc.sql("UPDATE learning_activities SET open_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY) WHERE activity_id = :id")
			.param("id", this.activity)
			.update();

		this.mvc.perform(get(path(this.course)).cookie(this.student)).andExpect(status().isNotFound());
		this.mvc.perform(get(path(this.course)).cookie(this.professor)).andExpect(status().isOk());
	}

	private String path(long courseId) {
		return "/api/courses/" + courseId + "/lecture-items/" + this.item + "/hls-key";
	}

}
