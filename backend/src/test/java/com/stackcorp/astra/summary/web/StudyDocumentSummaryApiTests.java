package com.stackcorp.astra.summary.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.files.StorageGcDriver;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.job.SummaryRunnerDriver;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import tools.jackson.databind.JsonNode;

/**
 * 학습실 내 문서의 쪽별 요약(ADR-0040 14번). 강의자료 요약과 같은 원장, pipeline, worker를 쓰고, 문서의 소유자만 요청하고 읽는다. 교수자
 * 동의는 보지 않는다(이 과목에는 동의가 없다). 완료된 요약은 강의자료 게시본과 같은 모양이다. MySQL, S3 호환 저장소, converter는 실제로
 * 띄우고 모델만 가짜로 둔다.
 */
class StudyDocumentSummaryApiTests extends IntegrationTest {

	static final String BASE = "/api/me/study-room";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	FakeLlm llm;

	@Autowired
	S3Client s3;

	@Autowired
	TestBuckets buckets;

	@Autowired
	ApplicationContext context;

	long course;

	long ownerId;

	ApiClient owner;

	ApiClient other;

	@BeforeEach
	void setUp() {
		this.llm.reset(FakeLlm::honest);
		drain();
		this.course = this.data.course();
		this.ownerId = member();
		this.owner = client(this.ownerId);
		this.other = client(member());
	}

	@Test
	void theOwnerRequestsASummaryAndReadsItInTheCourseSummaryShape() throws Exception {
		long document = upload(this.owner, TestPdfs.pdf(3));
		long fileId = fileOf(document);
		this.owner.get(BASE + "/documents/" + document).andExpect(jsonPath("$.summaryStatus").isEmpty());
		this.owner.get(BASE + "/documents/" + document + "/summary").andExpect(status().isNotFound());

		this.owner.post(summary(document))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Retry-After", "5"))
			.andExpect(jsonPath("$.status").value("queued"))
			.andExpect(jsonPath("$.summary").doesNotExist());
		// 진행 중에 다시 요청해도 작업을 더 만들지 않는다.
		this.owner.post(summary(document)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("queued"));
		assertThat(jobCount(fileId)).isEqualTo(1);
		this.owner.get(BASE + "/documents/" + document).andExpect(jsonPath("$.summaryStatus").value("queued"));
		this.jdbc.sql("UPDATE summary_jobs SET job_state = 'running' WHERE file_id = :f").param("f", fileId).update();
		this.owner.get(summary(document)).andExpect(jsonPath("$.status").value("processing"));
		this.jdbc.sql("UPDATE summary_jobs SET job_state = 'queued' WHERE file_id = :f").param("f", fileId).update();

		assertThat(SummaryRunnerDriver.runOnce(this.context)).isTrue();

		MvcResult read = this.owner.get(summary(document))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.status").value("ready"))
			.andExpect(jsonPath("$.message").doesNotExist())
			.andExpect(jsonPath("$.summary.fileId").value(fileId))
			.andExpect(jsonPath("$.summary.question").value("이 자료는 무엇을 설명하나요?"))
			.andExpect(jsonPath("$.summary.slides[2].page").value(3))
			.andReturn();
		// 모양은 강의자료 게시본(GET /api/courses/{courseId}/files/{fileId}/summary)의 응답 record와 같다.
		JsonNode body = ApiClient.json(read.getResponse());
		List<String> fields = new ArrayList<>();
		body.get("summary").propertyNames().forEach(fields::add);
		assertThat(fields).containsExactlyInAnyOrderElementsOf(Arrays
			.stream(SummaryViews.PublishedView.class.getRecordComponents())
			.map((c) -> c.getName())
			.toList());
		// 쪽 이미지 cookie는 소유자에게만, 그 rendition 경로로만 발급한다.
		String base = body.get("summary").get("pageImageBase").asString();
		assertThat(base).matches("/doc/[0-9a-f]{64}/");
		List<String> cookies = read.getResponse().getHeaders("Set-Cookie");
		assertThat(cookies).extracting((c) -> c.substring(0, c.indexOf('=')))
			.containsExactlyInAnyOrder("CloudFront-Policy", "CloudFront-Signature", "CloudFront-Key-Pair-Id");
		assertThat(cookies).allSatisfy((c) -> assertThat(c).contains("Path=" + base, "HttpOnly", "Secure"));
		this.owner.get(BASE + "/documents/" + document).andExpect(jsonPath("$.summaryStatus").value("ready"));
		this.owner.get(BASE + "/courses/" + this.course)
			.andExpect(jsonPath("$.documents[0].summaryStatus").value("ready"));

		// 완료된 뒤의 요청은 새 작업 없이 그 요약이다.
		this.owner.post(summary(document)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ready"));
		assertThat(jobCount(fileId)).isEqualTo(1);
		// 교수자 동의가 없는 과목이다. 사용량은 과목 합계에 들어가지 않는다.
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM llm_usage_daily WHERE course_id = :c")
			.param("c", this.course)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void onlyTheOwnerReachesTheSummaryAndCoursePathsNeverDo() throws Exception {
		long document = upload(this.owner, TestPdfs.pdf(1));
		long fileId = fileOf(document);
		this.other.post(summary(document)).andExpect(status().isNotFound());
		this.owner.post(summary(document)).andExpect(status().isAccepted());
		SummaryRunnerDriver.runOnce(this.context);
		long job = this.jdbc.sql("SELECT summary_job_id FROM summary_jobs WHERE file_id = :f")
			.param("f", fileId)
			.query(Long.class)
			.single();

		MvcResult foreign = this.other.get(summary(document)).andExpect(status().isNotFound()).andReturn();
		assertThat(foreign.getResponse().getHeaders("Set-Cookie")).noneMatch((c) -> c.startsWith("CloudFront-"));
		this.other.post(summary(document)).andExpect(status().isNotFound());
		this.owner.get(BASE + "/documents/" + Long.MAX_VALUE + "/summary").andExpect(status().isNotFound());

		// 과목 경로의 요약 API는 이 파일이 과목 첨부가 아니어서 열지 못한다. 담당 교수도 마찬가지다.
		long professor = this.data.user("PROFESSOR");
		this.data.instructor(this.course, professor);
		ApiClient instructor = client(professor);
		String files = "/api/courses/" + this.course + "/files/" + fileId;
		this.owner.get(files + "/summary").andExpect(status().isNotFound());
		instructor.get(files + "/summary").andExpect(status().isNotFound());
		instructor.get(files + "/summary-jobs").andExpect(status().isNotFound());
		instructor.get("/api/courses/" + this.course + "/summary-jobs/" + job).andExpect(status().isNotFound());
		instructor.get("/api/courses/" + this.course + "/summary-jobs/" + job + "/result")
			.andExpect(status().isNotFound());
		instructor.post("/api/courses/" + this.course + "/summary-jobs/" + job + "/publish")
			.andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM summary_publications WHERE file_id = :f")
			.param("f", fileId)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void aFailedSummaryCanBeRequestedAgainAndContinuesFromItsCheckpoints() throws Exception {
		long document = upload(this.owner, TestPdfs.pdf(2));
		long fileId = fileOf(document);
		this.llm.reset((req) -> {
			if (FakeLlm.isStory(req)) {
				throw new LlmException(LlmException.Code.AUTH, "rejected");
			}
			return FakeLlm.honest(req);
		});
		this.owner.post(summary(document)).andExpect(status().isAccepted());
		SummaryRunnerDriver.runOnce(this.context);
		this.owner.get(summary(document))
			.andExpect(status().isOk())
			.andExpect(header().doesNotExist("Retry-After"))
			.andExpect(jsonPath("$.status").value("failed"))
			.andExpect(jsonPath("$.message").isNotEmpty())
			.andExpect(jsonPath("$.summary").doesNotExist());
		this.owner.get(BASE + "/documents/" + document).andExpect(jsonPath("$.summaryStatus").value("failed"));
		long failed = latestJob(fileId);

		this.llm.reset(FakeLlm::honest);
		this.owner.post(summary(document)).andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("queued"));
		long retried = latestJob(fileId);
		assertThat(retried).isNotEqualTo(failed);
		assertThat(this.jdbc.sql("SELECT retry_of_job_id FROM summary_jobs WHERE summary_job_id = :id")
			.param("id", retried)
			.query(Long.class)
			.single()).isEqualTo(failed);
		this.llm.log.clear();
		SummaryRunnerDriver.runOnce(this.context);
		this.owner.get(summary(document)).andExpect(jsonPath("$.status").value("ready"));
		// 쪽 해설은 앞 작업의 중간 결과를 이어받아 흐름만 다시 만든다.
		assertThat(this.llm.log).hasSize(1);
	}

	@Test
	void aDocumentIsSummarizedOnlyAfterItsScanPasses() throws Exception {
		long document = upload(this.owner, TestPdfs.pdf(1));
		scanState(document, "quarantined", "pending");
		this.owner.get(BASE + "/documents/" + document).andExpect(jsonPath("$.state").value("scanning"));
		this.owner.post(summary(document))
			.andExpect(status().isConflict())
			.andExpect(header().string("Retry-After", "10"))
			.andExpect(jsonPath("$.code").value("file_scanning"));
		scanState(document, "rejected", "infected");
		this.owner.post(summary(document))
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("content_rejected"));
		assertThat(jobCount(fileOf(document))).isZero();
		scanState(document, "clean", "clean");
		this.owner.post(summary(document)).andExpect(status().isAccepted());
	}

	@Test
	void eachUserHasADailyCap() throws Exception {
		long document = upload(this.owner, TestPdfs.pdf(1));
		long fileId = fileOf(document);
		for (int i = 0; i < 20; i++) {
			this.data.insert("""
					INSERT INTO summary_jobs (file_id, file_version_no, job_origin, requested_by_user_id, job_state)
					VALUES (:f, 1, 'study_room', :u, 'failed')
					""", Map.of("f", fileId, "u", this.ownerId));
		}
		ResultActions limited = this.owner.post(summary(document))
			.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("summary_daily_limit"));
		assertThat(Long.parseLong(limited.andReturn().getResponse().getHeader("Retry-After"))).isPositive();
		assertThat(jobCount(fileId)).isEqualTo(20);
		// 다른 사용자의 상한과는 따로 센다. 어제 요청은 세지 않는다.
		long theirs = upload(this.other, TestPdfs.pdf(1));
		this.other.post(summary(theirs)).andExpect(status().isAccepted());
		this.jdbc.sql("""
				UPDATE summary_jobs SET created_at = DATE_SUB(created_at, INTERVAL 1 DAY)
				 WHERE requested_by_user_id = :u AND job_origin = 'study_room'
				""").param("u", this.ownerId).update();
		this.owner.post(summary(document)).andExpect(status().isAccepted());
	}

	@Test
	void deletingTheDocumentStopsItsSummaryAndRemovesTheResultAndPageImages() throws Exception {
		long document = upload(this.owner, TestPdfs.pdf(2));
		long fileId = fileOf(document);
		this.owner.post(summary(document)).andExpect(status().isAccepted());
		SummaryRunnerDriver.runOnce(this.context);
		JsonNode ready = ApiClient.json(this.owner.get(summary(document)).andExpect(status().isOk()));
		String base = ready.get("summary").get("pageImageBase").asString();
		long job = latestJob(fileId);
		String result = "derivatives/" + fileId + "/summary/jobs/" + job + "/result.json";
		assertThat(exists(this.buckets.content(), result)).isTrue();
		assertThat(exists(this.buckets.delivery(), base.substring(1) + "001.jpg")).isTrue();

		this.owner.send(MockMvcRequestBuilders.delete(BASE + "/documents/" + document).param("version", "1"))
			.andExpect(status().isNoContent());
		this.owner.get(summary(document)).andExpect(status().isNotFound());
		this.owner.post(summary(document)).andExpect(status().isNotFound());
		this.jdbc.sql("UPDATE files SET deleted_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE file_id = :f")
			.param("f", fileId)
			.update();
		StorageGcDriver.drain(this.context);
		assertThat(exists(this.buckets.content(), result)).isFalse();
		assertThat(exists(this.buckets.delivery(), base.substring(1) + "001.jpg")).isFalse();

		// 대기 중인 요약은 문서를 지울 때 취소한다.
		long queued = upload(this.owner, TestPdfs.pdf(1));
		long queuedFile = fileOf(queued);
		this.owner.post(summary(queued)).andExpect(status().isAccepted());
		this.owner.send(MockMvcRequestBuilders.delete(BASE + "/documents/" + queued).param("version", "1"))
			.andExpect(status().isNoContent());
		assertThat(this.jdbc.sql("SELECT job_state FROM summary_jobs WHERE file_id = :f")
			.param("f", queuedFile)
			.query(String.class)
			.single()).isEqualTo("cancelled");
		assertThat(SummaryRunnerDriver.runOnce(this.context)).isFalse();
	}

	private long upload(ApiClient client, byte[] pdf) throws Exception {
		return ApiClient.json(client
			.send(MockMvcRequestBuilders.multipart(BASE + "/courses/" + this.course + "/documents")
				.file(new MockMultipartFile("file", "notes-" + UUID.randomUUID() + ".pdf", "application/pdf", pdf)))
			.andExpect(status().isCreated())).get("documentId").asLong();
	}

	private long fileOf(long document) {
		return this.jdbc.sql("SELECT file_id FROM study_room_documents WHERE study_room_document_id = :id")
			.param("id", document)
			.query(Long.class)
			.single();
	}

	private long jobCount(long fileId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM summary_jobs WHERE file_id = :f")
			.param("f", fileId)
			.query(Long.class)
			.single();
	}

	private long latestJob(long fileId) {
		return this.jdbc.sql("SELECT MAX(summary_job_id) FROM summary_jobs WHERE file_id = :f")
			.param("f", fileId)
			.query(Long.class)
			.single();
	}

	private void scanState(long document, String blobState, String fileState) {
		this.jdbc.sql("""
				UPDATE file_blobs b JOIN files f ON f.file_blob_id = b.file_blob_id
				  JOIN study_room_documents d ON d.file_id = f.file_id
				   SET b.blob_state = :blob, f.scan_state = :file
				 WHERE d.study_room_document_id = :id
				""").param("blob", blobState).param("file", fileState).param("id", document).update();
	}

	private boolean exists(String bucket, String key) {
		try {
			this.s3.headObject((b) -> b.bucket(bucket).key(key));
			return true;
		}
		catch (NoSuchKeyException ex) {
			return false;
		}
	}

	private void drain() {
		while (SummaryRunnerDriver.runOnce(this.context)) {
			// 앞 테스트가 남긴 대기 작업을 비운다.
		}
	}

	private long member() {
		long userId = this.data.user("STUDENT");
		this.data.enroll(this.course, userId);
		return userId;
	}

	private ApiClient client(long userId) {
		return new ApiClient(this.mvc, this.data.session(userId));
	}

	private static String summary(long document) {
		return BASE + "/documents/" + document + "/summary";
	}

}
