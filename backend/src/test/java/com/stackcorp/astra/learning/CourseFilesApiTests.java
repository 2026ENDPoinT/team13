package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 강의실 파일모음(화면 52). 보이는 파일은 그 파일을 내려받을 수 있는 파일과 같다. 담당자는 모든 활동의 첨부를, 학생은 공개했고 시작한
 * 활동의 첨부만 본다. 자료 목록의 파일에는 요약 게시 여부가 함께 온다.
 */
class CourseFilesApiTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	long prof;

	ApiClient professor;

	ApiClient student;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, this.prof);
		this.data.enroll(this.course, stu);
		this.professor = new ApiClient(this.mvc, this.data.session(this.prof));
		this.student = new ApiClient(this.mvc, this.data.session(stu));
	}

	@Test
	void listsTheFilesEachMemberMayReadInWeekOrder() throws Exception {
		Material week2 = material(2, "정규화", true, null, "normal-1.pdf", "normal-2.pdf");
		Material week1 = material(1, "Syllabus", true, null, "syllabus.pdf");
		Material hidden = material(1, "비공개", false, null, "hidden.pdf");
		Material future = material(3, "다음 주", true, "2999-01-01T09:00:00", "future.pdf");

		JsonNode mine = ApiClient.json(this.student.get(files(""))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.serverTime").isNotEmpty())
			.andExpect(jsonPath("$.total").value(3))
			.andExpect(jsonPath("$.nextCursor").doesNotExist()));
		assertThat(fileIds(mine)).containsExactly(week1.files.get(0), week2.files.get(0), week2.files.get(1));
		JsonNode first = mine.get("items").get(0);
		assertThat(first.get("weekNo").asInt()).isEqualTo(1);
		assertThat(first.get("activityId").asLong()).isEqualTo(week1.activityId);
		assertThat(first.get("activityType").asString()).isEqualTo("lecture_material");
		assertThat(first.get("activityTitle").asString()).isEqualTo("Syllabus");
		assertThat(first.get("file").get("name").asString()).isEqualTo("syllabus.pdf");
		assertThat(first.get("file").get("pdf").asBoolean()).isTrue();
		assertThat(first.get("file").get("summaryPublished").asBoolean()).isFalse();
		// 목록에 없는 파일은 내려받기도 막힌다. 목록과 내려받기가 같은 판정이다.
		for (Material m : List.of(hidden, future)) {
			this.student.get(content(m.files.get(0))).andExpect(status().isNotFound());
		}
		for (JsonNode item : mine.get("items")) {
			this.student.get(content(item.get("file").get("fileId").asLong())).andExpect(status().isOk());
		}

		JsonNode all = ApiClient.json(this.professor.get(files("")).andExpect(jsonPath("$.total").value(5)));
		assertThat(fileIds(all)).contains(hidden.files.get(0), future.files.get(0));

		// 지운 자료의 파일은 누구의 목록에도 없다.
		this.professor
			.send(MockMvcRequestBuilders.delete("/api/courses/" + this.course + "/materials/" + week2.activityId)
				.header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());
		this.student.get(files("")).andExpect(jsonPath("$.total").value(1));
		this.professor.get(files("")).andExpect(jsonPath("$.total").value(3));
	}

	@Test
	void searchesFileNamesAndActivityTitlesAndPagesWithACursor() throws Exception {
		material(1, "Syllabus", true, null, "intro.pdf");
		Material normal = material(2, "정규화", true, null, "BCNF-notes.pdf", "slides.pdf");
		material(3, "트랜잭션", true, null, "acid.pdf");

		this.student.get(files("?q=bcnf"))
			.andExpect(jsonPath("$.total").value(1))
			.andExpect(jsonPath("$.items[0].file.fileId").value(normal.files.get(0)));
		// 활동 제목이 맞으면 그 활동의 파일이 모두 나온다.
		this.student.get(files("?q=" + "정규")).andExpect(jsonPath("$.total").value(2));
		this.student.get(files("?q=없는말")).andExpect(jsonPath("$.total").value(0)).andExpect(jsonPath("$.items").isEmpty());

		List<Long> paged = new ArrayList<>();
		String cursor = null;
		do {
			JsonNode page = ApiClient.json(this.student.get(files("?size=1" + (cursor == null ? "" : "&cursor=" + cursor)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(4)));
			paged.addAll(fileIds(page));
			cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
		}
		while (cursor != null);
		assertThat(paged).containsExactlyElementsOf(fileIds(ApiClient.json(this.student.get(files("")))));

		for (String bad : new String[] { "?size=0", "?size=101", "?cursor=abc", "?q=" + "가".repeat(101) }) {
			this.student.get(files(bad)).andExpect(status().isBadRequest());
		}
	}

	@Test
	void outsidersOtherCoursePathsAndAnonymousSeeNothing() throws Exception {
		material(1, "Syllabus", true, null, "intro.pdf");
		new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT"))).get(files("")).andExpect(status().isNotFound());
		long other = this.data.course();
		this.data.instructor(other, this.prof);
		// 담당자의 다른 과목 경로로는 이 과목의 파일이 나오지 않는다(SC-03).
		this.professor.get("/api/courses/" + other + "/files").andExpect(jsonPath("$.total").value(0));
		new ApiClient(this.mvc, null).get(files("")).andExpect(status().isUnauthorized());
		this.jdbc.sql("UPDATE courses SET course_status = 'CANCELLED' WHERE course_id = :c").param("c", this.course).update();
		this.professor.get(files("")).andExpect(status().isNotFound());
	}

	@Test
	void materialFilesSayWhetherASummaryIsPublished() throws Exception {
		Material m = material(1, "요약 자료", true, null, "summarized.pdf", "plain.pdf");
		long summarized = m.files.get(0);
		long job = this.data.insert("""
				INSERT INTO summary_jobs (file_id, file_version_no, course_id, requested_by_user_id)
				VALUES (:f, 1, :c, :u)
				""", Map.of("f", summarized, "c", this.course, "u", this.prof));
		this.jdbc.sql("INSERT INTO summary_publications (file_id, summary_job_id, published_by_user_id) VALUES (:f, :j, :u)")
			.param("f", summarized)
			.param("j", job)
			.param("u", this.prof)
			.update();

		this.student.get("/api/courses/" + this.course + "/materials")
			.andExpect(jsonPath("$.items[0].files[0].summaryPublished").value(true))
			.andExpect(jsonPath("$.items[0].files[1].summaryPublished").value(false));
		this.student.get("/api/courses/" + this.course + "/activities/" + m.activityId)
			.andExpect(jsonPath("$.files[0].summaryPublished").value(true));
		this.student.get(files(""))
			.andExpect(jsonPath("$.items[?(@.file.fileId == %d)].file.summaryPublished", summarized).value(true));
	}

	private Material material(int week, String title, boolean published, String openAt, String... names)
			throws Exception {
		List<Long> uploads = new ArrayList<>();
		for (String name : names) {
			uploads.add(this.professor.uploaded(this.course, name, TestPdfs.pdf(1)));
		}
		JsonNode created = ApiClient.json(this.professor
			.postJson("/api/courses/" + this.course + "/materials",
					"{\"weekNo\":%d,\"title\":\"%s\",\"published\":%s%s,\"uploadIds\":%s}".formatted(week, title,
							published, openAt == null ? "" : ",\"openAt\":\"" + openAt + "\"", uploads))
			.andExpect(status().isCreated()));
		List<Long> files = new ArrayList<>();
		created.get("files").forEach((f) -> files.add(f.get("fileId").asLong()));
		return new Material(created.get("activityId").asLong(), files);
	}

	private static List<Long> fileIds(JsonNode page) {
		List<Long> ids = new ArrayList<>();
		page.get("items").forEach((i) -> ids.add(i.get("file").get("fileId").asLong()));
		return ids;
	}

	private String files(String query) {
		return "/api/courses/" + this.course + "/files" + query;
	}

	private String content(long fileId) {
		return "/api/courses/" + this.course + "/files/" + fileId + "/content";
	}

	private record Material(long activityId, List<Long> files) {
	}

}
