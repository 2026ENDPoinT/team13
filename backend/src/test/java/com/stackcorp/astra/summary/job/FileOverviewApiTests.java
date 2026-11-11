package com.stackcorp.astra.summary.job;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.LlmConsents;

/**
 * 자료 상세 화면의 파일 요약(63_학습활동_자료상세 "ASTRA 서포터즈 요약", ADR-0032). 게시한 쪽별 요약에서 모델 호출 없이 만들고, 그
 * 파일을 볼 수 있는 사람에게만 보이며, 게시를 내리거나 파일을 지우면 사라진다.
 */
class FileOverviewApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Autowired
	SummaryJobRunner runner;

	@Autowired
	FakeLlm llm;

	long course;

	long otherCourse;

	long fileId;

	long activity;

	long jobId;

	ApiClient professor;

	ApiClient student;

	ApiClient outsider;

	@BeforeEach
	void publishedSummary() throws Exception {
		this.llm.reset(FakeLlm::honest);
		while (this.runner.runOnce()) {
			// 앞 테스트가 남긴 작업을 비운다.
		}
		this.course = this.data.course();
		this.otherCourse = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		LlmConsents.grant(this.context, this.course, prof);
		this.data.enroll(this.course, stu);
		this.data.enroll(this.otherCourse, stu);
		this.fileId = this.data.pdfFile(TestPdfs.pdf(2), "clean");
		this.activity = this.data.activity(this.course, "lecture_material", true, null,
				this.data.attachmentGroup(this.fileId));
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.student = new ApiClient(this.mvc, this.data.session(stu));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		this.jobId = ApiClient.json(this.professor.post(file() + "/summary-jobs").andExpect(status().isAccepted()))
			.get("id")
			.asLong();
		this.runner.runOnce();
		this.professor.post("/api/courses/" + this.course + "/summary-jobs/" + this.jobId + "/publish")
			.andExpect(status().isOk());
		this.llm.log.clear();
	}

	@Test
	void readersOfTheFileSeeTheOverviewBuiltWithoutAModelCall() throws Exception {
		for (ApiClient reader : new ApiClient[] { this.student, this.professor }) {
			reader.get(file() + "/summary/overview")
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andExpect(jsonPath("$.question").value("이 자료는 무엇을 설명하나요?"))
				.andExpect(jsonPath("$.story[0].title").value("핵심 흐름"))
				.andExpect(jsonPath("$.markdown").value(containsString("### 핵심 흐름 (p.1–2)")))
				.andExpect(jsonPath("$.markdown").value(containsString("**이 자료는 무엇을 설명하나요?**")));
		}
		assertThat(this.llm.log).isEmpty();
		this.outsider.get(file() + "/summary/overview").andExpect(status().isNotFound());
		this.student.get("/api/courses/" + this.otherCourse + "/files/" + this.fileId + "/summary/overview")
			.andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).get(file() + "/summary/overview").andExpect(status().isUnauthorized());
		// ai_file_summaries의 본문 컬럼은 쓰지 않는다(ADR-0007).
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM ai_file_summaries WHERE file_id = :f")
			.param("f", this.fileId)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void hiddenMaterialHidesTheOverviewFromStudents() throws Exception {
		this.jdbc.sql("UPDATE learning_activities SET published_yn = 0 WHERE activity_id = :id")
			.param("id", this.activity)
			.update();

		this.student.get(file() + "/summary/overview").andExpect(status().isNotFound());
		this.professor.get(file() + "/summary/overview").andExpect(status().isOk());
	}

	@Test
	void unpublishingRemovesOverviewAndSummaryUntilPublishedAgain() throws Exception {
		this.student.delete(file() + "/summary").andExpect(status().isNotFound());

		this.professor.delete(file() + "/summary").andExpect(status().isNoContent());

		this.student.get(file() + "/summary/overview").andExpect(status().isNotFound());
		this.professor.get(file() + "/summary/overview").andExpect(status().isNotFound());
		this.student.get(file() + "/summary").andExpect(status().isNotFound());
		this.professor.delete(file() + "/summary").andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM audit_events WHERE operation = 'summary.unpublish' AND object_id = :f")
			.param("f", this.fileId)
			.query(Long.class)
			.single()).isEqualTo(1);

		this.professor.post("/api/courses/" + this.course + "/summary-jobs/" + this.jobId + "/publish")
			.andExpect(status().isOk());
		this.student.get(file() + "/summary/overview").andExpect(status().isOk());
	}

	@Test
	void deletedFileHasNoOverview() throws Exception {
		this.jdbc.sql("UPDATE files SET state = 'deleted', deleted_at = CURRENT_TIMESTAMP(3) WHERE file_id = :f")
			.param("f", this.fileId)
			.update();

		this.student.get(file() + "/summary/overview").andExpect(status().isNotFound());
		this.professor.get(file() + "/summary/overview").andExpect(status().isNotFound());
	}

	private String file() {
		return "/api/courses/" + this.course + "/files/" + this.fileId;
	}

}
