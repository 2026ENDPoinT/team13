package com.stackcorp.astra.summary.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.summary.FakeLlm;

import jakarta.servlet.http.Cookie;

/**
 * HTTP로 요약을 요청하고 게시하고 읽는다. 권한이 없으면 존재를 드러내지 않는 404다.
 */
class SummaryApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	SummaryJobRunner runner;

	@Autowired
	FakeLlm llm;

	long course;

	long otherCourse;

	long fileId;

	long activity;

	Cookie professor;

	Cookie student;

	Cookie outsider;

	@BeforeEach
	void courseWithMaterial() {
		this.llm.reset(FakeLlm::honest);
		while (this.runner.runOnce()) {
			// 앞 테스트가 남긴 작업을 비운다.
		}
		this.course = this.data.course();
		this.otherCourse = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, stu);
		this.data.enroll(this.otherCourse, stu);
		this.fileId = this.data.pdfFile(TestPdfs.pdf(2), "clean");
		this.activity = this.data.activity(this.course, "lecture_material", true, null,
				this.data.attachmentGroup(this.fileId));
		this.professor = session(prof);
		this.student = session(stu);
		this.outsider = session(this.data.user("STUDENT"));
	}

	@Test
	void professorGeneratesAndPublishesAndStudentReadsWithPageImageCookie() throws Exception {
		MvcResult created = this.mvc.perform(post(files() + "/summary-jobs", this.professor))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.state").value("queued"))
			.andReturn();
		long jobId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
		this.mvc.perform(get("/api/courses/" + this.course + "/files/" + this.fileId + "/summary").cookie(this.student))
			.andExpect(status().isNotFound());

		this.runner.runOnce();

		this.mvc.perform(get(jobs(jobId)).cookie(this.professor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("completed"))
			.andExpect(jsonPath("$.pagesDone").value(2));
		this.mvc.perform(get(jobs(jobId) + "/result").cookie(this.professor))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.slides.length()").value(2));
		this.mvc.perform(post(jobs(jobId) + "/publish", this.professor)).andExpect(status().isOk());

		MvcResult read = this.mvc
			.perform(get("/api/courses/" + this.course + "/files/" + this.fileId + "/summary").cookie(this.student))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.question").value("이 자료는 무엇을 설명하나요?"))
			.andExpect(jsonPath("$.slides[1].page").value(2))
			.andReturn();
		String base = JsonPath.read(read.getResponse().getContentAsString(), "$.pageImageBase");
		assertThat(base).matches("/doc/[0-9a-f]{64}/");
		List<String> cookies = read.getResponse().getHeaders("Set-Cookie");
		assertThat(cookies).extracting((c) -> c.substring(0, c.indexOf('=')))
			.containsExactlyInAnyOrder("CloudFront-Policy", "CloudFront-Signature", "CloudFront-Key-Pair-Id");
		assertThat(cookies).allSatisfy((c) -> assertThat(c).contains("Path=" + base, "HttpOnly", "Secure"));
	}

	@Test
	void hidesFilesAndJobsFromPeopleWhoCannotManageThem() throws Exception {
		this.mvc.perform(post(files() + "/summary-jobs", this.student)).andExpect(status().isNotFound());
		this.mvc.perform(post(files() + "/summary-jobs", this.outsider)).andExpect(status().isNotFound());
		// 같은 파일이라도 다른 과목 경로로는 다룰 수 없다(SC-03).
		this.mvc.perform(post("/api/courses/" + this.otherCourse + "/files/" + this.fileId + "/summary-jobs",
				this.professor))
			.andExpect(status().isNotFound());
		MvcResult created = this.mvc.perform(post(files() + "/summary-jobs", this.professor))
			.andExpect(status().isAccepted())
			.andReturn();
		long jobId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();

		this.mvc.perform(get(jobs(jobId)).cookie(this.student)).andExpect(status().isNotFound());
		this.mvc.perform(post(jobs(jobId) + "/cancel", this.student)).andExpect(status().isNotFound());
		this.mvc.perform(get("/api/courses/" + this.otherCourse + "/summary-jobs/" + jobId).cookie(this.professor))
			.andExpect(status().isNotFound());
		this.mvc.perform(post(files() + "/summary-jobs", this.professor))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("conflict"));
	}

	@Test
	void studentsCannotReadSummaryOfHiddenOrNotYetOpenMaterial() throws Exception {
		MvcResult created = this.mvc.perform(post(files() + "/summary-jobs", this.professor)).andReturn();
		long jobId = ((Number) JsonPath.read(created.getResponse().getContentAsString(), "$.id")).longValue();
		this.runner.runOnce();
		this.mvc.perform(post(jobs(jobId) + "/publish", this.professor)).andExpect(status().isOk());
		String summary = "/api/courses/" + this.course + "/files/" + this.fileId + "/summary";

		this.jdbc.sql("UPDATE learning_activities SET published_yn = 0 WHERE activity_id = :id")
			.param("id", this.activity)
			.update();
		this.mvc.perform(get(summary).cookie(this.student)).andExpect(status().isNotFound());
		this.jdbc.sql("""
				UPDATE learning_activities SET published_yn = 1, open_at = DATE_ADD(CURRENT_TIMESTAMP, INTERVAL 1 DAY)
				 WHERE activity_id = :id
				""").param("id", this.activity).update();
		this.mvc.perform(get(summary).cookie(this.student)).andExpect(status().isNotFound());
		this.mvc.perform(get(summary).cookie(this.professor)).andExpect(status().isOk());
		this.mvc.perform(get(summary).cookie(this.outsider)).andExpect(status().isNotFound());
	}

	@Test
	void requiresSessionAndCsrfToken() throws Exception {
		this.mvc.perform(get(files() + "/summary-jobs")).andExpect(status().isUnauthorized());
		this.mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.post(files() + "/summary-jobs")
			.cookie(this.professor)).andExpect(status().isForbidden());
	}

	private String files() {
		return "/api/courses/" + this.course + "/files/" + this.fileId;
	}

	private String jobs(long jobId) {
		return "/api/courses/" + this.course + "/summary-jobs/" + jobId;
	}

	private MockHttpServletRequestBuilder post(String path, Cookie session) throws Exception {
		Cookie csrf = this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path)
			.cookie(session, csrf)
			.header("X-XSRF-TOKEN", csrf.getValue());
	}

	private Cookie session(long userId) {
		return new Cookie("ASTRA_SESSION", this.data.session(userId));
	}

}
