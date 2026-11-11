package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.files.api.DeliveryObjects;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.LlmConsents;
import com.stackcorp.astra.summary.job.SummaryRunnerDriver;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

/**
 * 강의 자료 수정과 삭제(ADR-0011). 수정은 마지막으로 본 version을 If-Match로 보내야 하고 첨부는 최종 집합으로 한 번에 바뀐다.
 * 삭제한 자료와 집합에서 빠진 파일은 그 순간부터 학생과 담당자 모두 어느 경로로도 읽지 못한다. 학습 기록은 남는다.
 */
class MaterialLifecycleTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Autowired
	FakeLlm llm;

	@Autowired
	DeliveryObjects delivery;

	@Autowired
	DocumentRenditions renditions;

	@Autowired
	S3Client s3;

	@Autowired
	TestBuckets buckets;

	long course;

	long studentId;

	String professorSession;

	ApiClient professor;

	ApiClient student;

	ApiClient outsider;

	@BeforeEach
	void course() {
		this.llm.reset(FakeLlm::honest);
		while (SummaryRunnerDriver.runOnce(this.context)) {
			// 앞 테스트가 남긴 요약 작업을 비운다.
		}
		this.course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.studentId = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		LlmConsents.grant(this.context, this.course, prof);
		this.data.enroll(this.course, this.studentId);
		this.professorSession = this.data.session(prof);
		this.professor = new ApiClient(this.mvc, this.professorSession);
		this.student = new ApiClient(this.mvc, this.data.session(this.studentId));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	@Test
	void editReplacesFieldsAndAttachmentSetAndRemovedFileBecomesUnreadableEverywhere() throws Exception {
		long a = this.professor.uploaded(this.course, "a.pdf", unique(1));
		long b = this.professor.uploaded(this.course, "b.pdf", unique(2));
		Material material = register(3, a, b);
		long fileA = material.files().get(0);
		long fileB = material.files().get(1);
		String key = this.renditions.prepare(fileB).key();
		assertThat(this.delivery.find("doc/" + key + "/001.jpg")).isPresent();
		long c = this.professor.uploaded(this.course, "c.pdf", unique(1));

		put(this.professor, material.id(), "\"1\"", edit("4주차 정규화", false, List.of(fileA), List.of(c)))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"2\""))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.weekNo").value(4))
			.andExpect(jsonPath("$.title").value("4주차 정규화"))
			.andExpect(jsonPath("$.description").value("고친 설명"))
			.andExpect(jsonPath("$.published").value(false))
			.andExpect(jsonPath("$.files.length()").value(2))
			.andExpect(jsonPath("$.files[0].fileId").value(fileA))
			.andExpect(jsonPath("$.files[1].name").value("c.pdf"));
		// 비공개로 바꾼 자료는 학생에게 사라진다.
		this.student.get(activity(material.id())).andExpect(status().isNotFound());
		this.student.get(content(fileA)).andExpect(status().isNotFound());

		JsonNode republished = ApiClient.json(put(this.professor, material.id(), "\"2\"",
				edit("4주차 정규화", true, List.of(fileA, fileOf(c)), List.of()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(3)));
		assertThat(republished.get("files")).hasSize(2);

		this.student.get(activity(material.id()))
			.andExpect(status().isOk())
			.andExpect(header().string("ETag", "\"3\""))
			.andExpect(jsonPath("$.files[*].fileId", not(hasItem((int) fileB))))
			.andExpect(jsonPath("$.files.length()").value(2));
		this.student.get(content(fileA)).andExpect(status().isOk());
		this.student.get(content(fileOf(c))).andExpect(status().isOk());
		// 집합에서 빠진 파일은 담당자도 어느 경로로도 읽지 못한다.
		for (ApiClient who : new ApiClient[] { this.professor, this.student }) {
			who.get(content(fileB)).andExpect(status().isNotFound());
			who.get(files(fileB) + "/summary").andExpect(status().isNotFound());
			who.get(materials()).andExpect(jsonPath("$[0].files[*].fileId", not(hasItem((int) fileB))));
		}
		this.professor.post(files(fileB) + "/summary-jobs").andExpect(status().isNotFound());
		assertThat(this.delivery.find("doc/" + key + "/001.jpg")).isEmpty();
		this.professor.get("/api/uploads/" + b).andExpect(jsonPath("$.state").value("deleted"));
		assertThat(fileState(fileB)).isEqualTo("deleted");
		assertThat(referenceCount(fileB)).isZero();
		assertThat(fileState(fileA)).isEqualTo("active");
	}

	@Test
	void refusesEditAndDeleteBasedOnAStaleVersionAndChangesNothing() throws Exception {
		Material material = register(5, this.professor.uploaded(this.course, "a.pdf", unique(1)));
		long file = material.files().getFirst();
		long late = this.professor.uploaded(this.course, "late.pdf", unique(1));

		put(this.professor, material.id(), "\"1\"", edit("먼저 고친 제목", true, List.of(file), List.of()))
			.andExpect(status().isOk());
		put(this.professor, material.id(), "\"1\"", edit("늦게 고친 제목", true, List.of(), List.of(late)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		this.professor.send(MockMvcRequestBuilders.delete(material(this.course, material.id())).header("If-Match", "\"1\""))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		put(this.professor, material.id(), null, edit("If-Match 없음", true, List.of(file), List.of()))
			.andExpect(status().isPreconditionRequired())
			.andExpect(jsonPath("$.code").value("precondition_required"));
		this.professor.send(MockMvcRequestBuilders.delete(material(this.course, material.id())))
			.andExpect(status().isPreconditionRequired());
		for (String malformed : new String[] { "W/\"2\"", "*", "2", "\"0\"" }) {
			put(this.professor, material.id(), malformed, edit("형식 오류", true, List.of(file), List.of()))
				.andExpect(status().isBadRequest());
		}

		this.student.get(activity(material.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("먼저 고친 제목"))
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(jsonPath("$.files[0].fileId").value(file));
		this.student.get(content(file)).andExpect(status().isOk());
		this.professor.get("/api/uploads/" + late).andExpect(jsonPath("$.state").value("clean"));
	}

	@Test
	void unusableAttachmentRollsBackTheWholeEdit() throws Exception {
		long colleagueId = this.data.user("PROFESSOR");
		this.data.instructor(this.course, colleagueId);
		ApiClient colleague = new ApiClient(this.mvc, this.data.session(colleagueId));
		long theirs = colleague.uploaded(this.course, "theirs.pdf", unique(1));
		Material material = register(6, this.professor.uploaded(this.course, "a.pdf", unique(1)));
		Material other = register(7, this.professor.uploaded(this.course, "b.pdf", unique(1)));
		long file = material.files().getFirst();
		long good = this.professor.uploaded(this.course, "good.pdf", unique(1));

		// 남길 파일을 빼고, 쓸 수 있는 업로드와 남의 업로드를 함께 더하는 수정
		put(this.professor, material.id(), "\"1\"", edit("바뀌면 안 되는 제목", false, List.of(), List.of(good, theirs)))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		// 다른 자료의 파일은 keep으로 가져오지 못한다.
		put(this.professor, material.id(), "\"1\"",
				edit("바뀌면 안 되는 제목", false, List.of(file, other.files().getFirst()), List.of()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("attachment_not_ready"));

		this.student.get(activity(material.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("6주차"))
			.andExpect(jsonPath("$.version").value(1))
			.andExpect(jsonPath("$.files.length()").value(1))
			.andExpect(jsonPath("$.files[0].fileId").value(file));
		this.student.get(content(file)).andExpect(status().isOk());
		this.student.get(content(other.files().getFirst())).andExpect(status().isOk());
		this.professor.get("/api/uploads/" + good).andExpect(jsonPath("$.state").value("clean"));
		assertThat(fileState(file)).isEqualTo("active");
	}

	@Test
	void deleteHidesTheMaterialEverywhereKeepsLearningRecordsAndFreesItsFiles() throws Exception {
		byte[] pdf = unique(2);
		long upload = this.professor.uploaded(this.course, "week8.pdf", pdf);
		Material material = register(8, upload);
		long file = material.files().getFirst();
		String key = publishSummary(file);
		assertThat(this.delivery.find("doc/" + key + "/001.jpg")).isPresent();
		this.jdbc.sql("INSERT INTO activity_progress (user_id, activity_id, view_count) VALUES (:u, :a, 1)")
			.param("u", this.studentId)
			.param("a", material.id())
			.update();

		this.professor.send(MockMvcRequestBuilders.delete(material(this.course, material.id())).header("If-Match", "\"1\""))
			.andExpect(status().isNoContent())
			.andExpect(header().string("Cache-Control", "no-store"));

		for (ApiClient who : new ApiClient[] { this.professor, this.student }) {
			who.get(materials()).andExpect(status().isOk()).andExpect(jsonPath("$[*].activityId", not(hasItem((int) material.id()))));
			who.get("/api/courses/" + this.course + "/weeks")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].activities[*].activityId", not(hasItem((int) material.id()))));
			who.get(activity(material.id())).andExpect(status().isNotFound());
			who.get(content(file)).andExpect(status().isNotFound());
			who.get(files(file) + "/summary").andExpect(status().isNotFound());
		}
		assertThat(this.delivery.find("doc/" + key + "/001.jpg")).isEmpty();
		put(this.professor, material.id(), "\"2\"", edit("지운 자료", true, List.of(), List.of()))
			.andExpect(status().isNotFound());
		this.professor.send(MockMvcRequestBuilders.delete(material(this.course, material.id())).header("If-Match", "\"2\""))
			.andExpect(status().isNotFound());

		this.professor.get("/api/uploads/" + upload).andExpect(jsonPath("$.state").value("deleted"));
		assertThat(fileState(file)).isEqualTo("deleted");
		assertThat(referenceCount(file)).isZero();
		// 학습 기록은 지우지 않는다(SC-07). 활동에는 삭제 표시만 남는다.
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM activity_progress WHERE activity_id = :a")
			.param("a", material.id())
			.query(Long.class)
			.single()).isOne();
		assertThat(this.jdbc.sql("SELECT deleted_at IS NOT NULL FROM learning_activities WHERE activity_id = :a")
			.param("a", material.id())
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void notificationFollowsTheMaterialThroughUnpublishRepublishAndDelete() throws Exception {
		Material material = register(6, this.professor.uploaded(this.course, "a.pdf", unique(1)));
		long file = material.files().getFirst();
		assertThat(materialNotifications(material.id())).isOne();

		// 비공개로 돌리면 보낸 알림을 거두고, 다시 공개하면 한 번만 다시 보낸다.
		put(this.professor, material.id(), "\"1\"", edit("숨긴 자료", false, List.of(file), List.of())).andExpect(status().isOk());
		assertThat(materialNotifications(material.id())).isZero();
		put(this.professor, material.id(), "\"2\"", edit("다시 연 자료", true, List.of(file), List.of())).andExpect(status().isOk());
		put(this.professor, material.id(), "\"3\"", edit("제목만 고친 자료", true, List.of(file), List.of())).andExpect(status().isOk());
		assertThat(materialNotifications(material.id())).isOne();
		this.student.get("/api/me/notifications").andExpect(jsonPath("$.items[0].targetId").value(material.id()));

		this.professor.send(MockMvcRequestBuilders.delete(material(this.course, material.id())).header("If-Match", "\"4\""))
			.andExpect(status().isNoContent());
		assertThat(materialNotifications(material.id())).isZero();
		this.student.get("/api/me/notifications").andExpect(jsonPath("$.items.length()").value(0));
		// 수정과 삭제는 명령마다 감사 기록 한 줄이다(보안 검토 L2).
		assertThat(this.jdbc.sql("""
				SELECT operation FROM audit_events
				 WHERE object_type = 'learning_activity' AND object_id = :id AND course_id = :course
				 ORDER BY audit_event_id
				""").param("id", material.id()).param("course", this.course).query(String.class).list())
			.containsExactly("material.create", "material.update", "material.update", "material.update",
					"material.delete");
	}

	@Test
	void onlyTheCourseInstructorCanEditOrDeleteThroughThatCoursePath() throws Exception {
		Material material = register(9, this.professor.uploaded(this.course, "a.pdf", unique(1)));
		long file = material.files().getFirst();
		long otherCourse = this.data.course();
		this.jdbc.sql("""
				INSERT INTO course_instructors (course_id, user_id, instructor_role)
				SELECT :other, user_id, 'PROFESSOR' FROM course_instructors WHERE course_id = :course
				""").param("other", otherCourse).param("course", this.course).update();
		this.data.enroll(otherCourse, this.studentId);
		long report = this.data.activity(this.course, "report", true, null, null);
		String body = edit("고친 제목", true, List.of(file), List.of());

		for (ApiClient who : new ApiClient[] { this.student, this.outsider }) {
			put(who, material.id(), "\"1\"", body).andExpect(status().isNotFound());
			who.send(MockMvcRequestBuilders.delete(material(this.course, material.id())).header("If-Match", "\"1\""))
				.andExpect(status().isNotFound());
		}
		// 담당하는 다른 과목의 경로로는 이 과목의 자료에 닿지 않는다(SC-03).
		this.professor.send(MockMvcRequestBuilders.put(material(otherCourse, material.id()))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header("If-Match", "\"1\"")).andExpect(status().isNotFound());
		this.professor.send(MockMvcRequestBuilders.delete(material(otherCourse, material.id())).header("If-Match", "\"1\""))
			.andExpect(status().isNotFound());
		// 강의 자료가 아닌 활동은 이 API로 바꾸지 못한다.
		put(this.professor, report, "\"1\"", body).andExpect(status().isNotFound());
		this.professor.send(MockMvcRequestBuilders.delete(material(this.course, report)).header("If-Match", "\"1\""))
			.andExpect(status().isNotFound());
		put(new ApiClient(this.mvc, null), material.id(), "\"1\"", body).andExpect(status().isUnauthorized());
		this.mvc.perform(MockMvcRequestBuilders.put(material(this.course, material.id()))
			.cookie(new Cookie("ASTRA_SESSION", this.professorSession))
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.header("If-Match", "\"1\"")).andExpect(status().isForbidden());
		this.mvc.perform(MockMvcRequestBuilders.delete(material(this.course, material.id()))
			.cookie(new Cookie("ASTRA_SESSION", this.professorSession))
			.header("If-Match", "\"1\"")).andExpect(status().isForbidden());
		// 요청 본문에 문서에 없는 필드를 넣으면 거부한다(SC-02).
		put(this.professor, material.id(), "\"1\"", body.replace("\"published\"", "\"userId\":1,\"published\""))
			.andExpect(status().isBadRequest());

		this.student.get(activity(material.id()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("9주차"))
			.andExpect(jsonPath("$.version").value(1));
		this.student.get(content(file)).andExpect(status().isOk());
	}

	@Test
	void summaryOfADeletedFileIsNotServedOrRevivedByAnIdenticalUpload() throws Exception {
		byte[] pdf = unique(2);
		Material material = register(10, this.professor.uploaded(this.course, "same.pdf", pdf));
		long file = material.files().getFirst();
		publishSummary(file);
		this.professor.send(MockMvcRequestBuilders.delete(material(this.course, material.id())).header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());

		Material again = register(11, this.professor.uploaded(this.course, "same.pdf", pdf));
		long newFile = again.files().getFirst();

		assertThat(newFile).isNotEqualTo(file);
		this.student.get(content(newFile)).andExpect(status().isOk());
		for (ApiClient who : new ApiClient[] { this.professor, this.student }) {
			who.get(files(newFile) + "/summary").andExpect(status().isNotFound());
			who.get(files(file) + "/summary").andExpect(status().isNotFound());
		}
		this.professor.get(files(newFile) + "/summary-jobs").andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
	}

	@Test
	void summaryJobsOnADeletedFileEndFailedWithoutWritingOrPublishing() throws Exception {
		Material queued = register(12, this.professor.uploaded(this.course, "queued.pdf", unique(1)));
		long queuedJob = requestSummary(queued.files().getFirst());
		this.professor.send(MockMvcRequestBuilders.delete(material(this.course, queued.id())).header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());

		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();
		assertThat(job(queuedJob)).containsEntry("job_state", "failed").containsEntry("error_code", "file");

		Material running = register(13, this.professor.uploaded(this.course, "running.pdf", unique(8)));
		long runningFile = running.files().getFirst();
		long runningJob = requestSummary(runningFile);
		AtomicBoolean deleted = new AtomicBoolean();
		this.llm.reset((request) -> {
			// 첫 모델 호출 중에 담당자가 자료를 지운다.
			if (deleted.compareAndSet(false, true)) {
				try {
					this.professor
						.send(MockMvcRequestBuilders.delete(material(this.course, running.id())).header("If-Match", "\"1\""))
						.andExpect(status().isNoContent());
				}
				catch (Exception ex) {
					throw new IllegalStateException(ex);
				}
			}
			return FakeLlm.honest(request);
		});

		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();

		assertThat(job(runningJob)).containsEntry("job_state", "failed").containsEntry("error_code", "file");
		assertThat(this.llm.log).hasSize(1);
		assertThat(this.s3.listObjectsV2((b) -> b.bucket(this.buckets.content())
			.prefix("derivatives/" + runningFile + "/summary/jobs/" + runningJob + "/result.json")).contents()).isEmpty();
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM summary_publications WHERE file_id IN (:files)")
			.param("files", List.of(queued.files().getFirst(), runningFile))
			.query(Long.class)
			.single()).isZero();
		this.professor.post("/api/courses/" + this.course + "/summary-jobs/" + runningJob + "/publish")
			.andExpect(status().isNotFound());
		this.professor.post("/api/courses/" + this.course + "/summary-jobs/" + runningJob + "/retry")
			.andExpect(status().isNotFound());
	}

	private String publishSummary(long fileId) throws Exception {
		long jobId = requestSummary(fileId);
		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();
		this.professor.post("/api/courses/" + this.course + "/summary-jobs/" + jobId + "/publish").andExpect(status().isOk());
		String base = ApiClient.json(this.student.get(files(fileId) + "/summary").andExpect(status().isOk()))
			.get("pageImageBase")
			.stringValue();
		return base.substring("/doc/".length(), base.length() - 1);
	}

	private long requestSummary(long fileId) throws Exception {
		return ApiClient.json(this.professor.post(files(fileId) + "/summary-jobs").andExpect(status().isAccepted()))
			.get("id")
			.asLong();
	}

	private java.util.Map<String, Object> job(long jobId) {
		return this.jdbc.sql("SELECT job_state, error_code FROM summary_jobs WHERE summary_job_id = :id")
			.param("id", jobId)
			.query()
			.singleRow();
	}

	private long materialNotifications(long activityId) {
		return this.jdbc.sql("""
				SELECT COUNT(*) FROM notifications
				 WHERE user_id = :u AND notification_type = 'LECTURE_MATERIAL' AND target_ref_id = :a
				""").param("u", this.studentId).param("a", activityId).query(Long.class).single();
	}

	private Material register(int weekNo, long... uploadIds) throws Exception {
		String ids = LongStream.of(uploadIds).mapToObj(Long::toString).collect(Collectors.joining(","));
		JsonNode created = ApiClient.json(this.professor
			.postJson(materials(), "{\"weekNo\":%d,\"title\":\"%d주차\",\"uploadIds\":[%s]}".formatted(weekNo, weekNo, ids))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.version").value(1)));
		List<Long> files = new ArrayList<>();
		created.get("files").forEach((f) -> files.add(f.get("fileId").asLong()));
		return new Material(created.get("activityId").asLong(), files);
	}

	private ResultActions put(ApiClient who, long activityId, String ifMatch, String json) throws Exception {
		var request = MockMvcRequestBuilders.put(material(this.course, activityId))
			.contentType(MediaType.APPLICATION_JSON)
			.content(json);
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return who.send(request);
	}

	private static String edit(String title, boolean published, List<Long> keep, List<Long> add) {
		return """
				{"weekNo":4,"title":"%s","description":"고친 설명","openAt":null,"published":%s,
				 "attachments":{"keep":%s,"add":%s}}""".formatted(title, published, keep, add);
	}

	private long fileOf(long uploadId) {
		return this.jdbc.sql("SELECT file_id FROM upload_sessions WHERE upload_id = :id")
			.param("id", uploadId)
			.query(Long.class)
			.single();
	}

	private String fileState(long fileId) {
		return this.jdbc.sql("SELECT state FROM files WHERE file_id = :id").param("id", fileId).query(String.class).single();
	}

	private int referenceCount(long fileId) {
		return this.jdbc.sql("""
				SELECT b.reference_count FROM files f JOIN file_blobs b ON b.file_blob_id = f.file_blob_id WHERE f.file_id = :id
				""").param("id", fileId).query(Integer.class).single();
	}

	// 같은 내용의 다른 업로드와 blob을 함께 쓰지 않도록 문서 제목을 달리한다.
	private static byte[] unique(int pages) {
		return TestPdfs.pdf(pages, null, UUID.randomUUID().toString());
	}

	private String materials() {
		return "/api/courses/" + this.course + "/materials";
	}

	private static String material(long courseId, long activityId) {
		return "/api/courses/" + courseId + "/materials/" + activityId;
	}

	private String activity(long activityId) {
		return "/api/courses/" + this.course + "/activities/" + activityId;
	}

	private String files(long fileId) {
		return "/api/courses/" + this.course + "/files/" + fileId;
	}

	private String content(long fileId) {
		return files(fileId) + "/content";
	}

	private record Material(long id, List<Long> files) {
	}

}
