package com.stackcorp.astra.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestOfficeFiles;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.files.api.ConversionException;
import com.stackcorp.astra.files.api.PageConverter;
import com.stackcorp.astra.files.api.RenditionException;
import com.stackcorp.astra.files.storage.ObjectStore;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

/**
 * 문서 미리보기, 썸네일, 이미지 보기(ADR-0033). 권한은 파일을 첨부한 부모 활동의 읽기 권한이고, 변환은 대기열에 넣은 뒤 worker가
 * 한다. 쪽 이미지는 그 rendition 경로에만 유효한 짧은 cookie로 연다. 테스트의 converter는 office 형식을 가짜 엔진으로 바꾼다.
 */
class PreviewApiTests extends IntegrationTest {

	private static final String WORKER = "preview-test-worker";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	S3Client s3;

	@Autowired
	TestBuckets buckets;

	@Autowired
	ApplicationContext context;

	@Autowired
	DocumentRenditionService renditions;

	@Autowired
	ConversionRecords conversions;

	@Autowired
	FileRecords fileRecords;

	@Autowired
	ObjectStore store;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	PageConverter pageConverter;

	long course;

	long otherCourse;

	ApiClient professor;

	ApiClient student;

	String studentToken;

	ApiClient outsider;

	ApiClient otherProfessor;

	ApiClient anonymous;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.otherCourse = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		long out = this.data.user("STUDENT");
		long otherProf = this.data.user("PROFESSOR");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, stu);
		this.data.instructor(this.otherCourse, otherProf);
		this.data.enroll(this.otherCourse, out);
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.studentToken = this.data.session(stu);
		this.student = new ApiClient(this.mvc, this.studentToken);
		this.outsider = new ApiClient(this.mvc, this.data.session(out));
		this.otherProfessor = new ApiClient(this.mvc, this.data.session(otherProf));
		this.anonymous = new ApiClient(this.mvc, null);
		drainQueue();
	}

	@Test
	void convertsAsynchronouslyAndThenOpensThePagesWithAPathScopedCookie() throws Exception {
		long fileId = attached(pdf(3), "application/pdf", "week3.pdf", true);

		MvcResult converting = this.student.post(preview(this.course, fileId))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Retry-After", "2"))
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.state").value("converting"))
			.andExpect(jsonPath("$.pageImageBase").doesNotExist())
			.andReturn();
		assertThat(deliveryCookies(converting)).isEmpty();
		this.student.post(preview(this.course, fileId)).andExpect(status().isAccepted());
		assertThat(jobs(fileId)).singleElement().satisfies((j) -> assertThat(j).containsEntry("conversion_state", "requested"));

		drainQueue();

		MvcResult ready = this.student.post(preview(this.course, fileId))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.state").value("ready"))
			.andExpect(jsonPath("$.pageCount").value(3))
			.andReturn();
		String key = key(fileId);
		String base = ApiClient.json(ready.getResponse()).get("pageImageBase").asString();
		assertThat(base).isEqualTo("/doc/" + key + "/");
		List<String> cookies = deliveryCookies(ready);
		assertThat(cookies).extracting((c) -> c.substring(0, c.indexOf('=')))
			.containsExactlyInAnyOrder("CloudFront-Policy", "CloudFront-Signature", "CloudFront-Key-Pair-Id");
		assertThat(cookies).allSatisfy((c) -> assertThat(c).contains("Path=" + base, "HttpOnly", "Secure", "Max-Age=600"));
		// 뷰어 URL token, 사용자 ID, query string을 응답에 넣지 않는다(SEC-17).
		String body = ready.getResponse().getContentAsString();
		assertThat(body).doesNotContain("?", "token", "userId");
		assertThat(ApiClient.json(ready.getResponse()).get("cookieExpiresAt").isNull()).isFalse();
		for (int page = 1; page <= 3; page++) {
			assertThat(exists(this.buckets.delivery(), "doc/" + key + "/%03d.jpg".formatted(page))).isTrue();
		}
		assertThat(jobs(fileId)).singleElement()
			.satisfies((j) -> assertThat(j).containsEntry("conversion_state", "ready").containsEntry("attempt_count", 1L));
	}

	@Test
	void eachFileGetsCookiesOnlyForItsOwnRendition() throws Exception {
		long first = attached(pdf(1), "application/pdf", "a.pdf", true);
		long second = attached(pdf(1), "application/pdf", "b.pdf", true);
		this.student.post(preview(this.course, first));
		this.student.post(preview(this.course, second));
		drainQueue();

		List<String> cookies = deliveryCookies(this.student.post(preview(this.course, first)).andReturn());

		assertThat(key(first)).isNotEqualTo(key(second));
		assertThat(cookies).hasSize(3)
			.allSatisfy((c) -> assertThat(c).contains("Path=/doc/" + key(first) + "/").doesNotContain(key(second)));
	}

	@Test
	void onlyPeopleWhoMayReadTheParentGetAPreview() throws Exception {
		long fileId = attached(pdf(1), "application/pdf", "w.pdf", true);
		long hidden = attached(pdf(1), "application/pdf", "hidden.pdf", false);
		this.professor.post(preview(this.course, fileId)).andExpect(status().isAccepted());
		drainQueue();

		this.professor.post(preview(this.course, fileId)).andExpect(status().isOk());
		this.student.post(preview(this.course, fileId)).andExpect(status().isOk());
		this.outsider.post(preview(this.course, fileId)).andExpect(status().isNotFound());
		// 다른 과목의 담당자가 자기 과목 경로로 물어도 없는 파일이다(SC-03).
		this.otherProfessor.post(preview(this.otherCourse, fileId)).andExpect(status().isNotFound());
		this.professor.post(preview(this.otherCourse, fileId)).andExpect(status().isNotFound());
		this.anonymous.post(preview(this.course, fileId)).andExpect(status().isUnauthorized());
		// 공개하지 않은 활동의 첨부는 학생에게 없다.
		this.student.post(preview(this.course, hidden)).andExpect(status().isNotFound());
		this.professor.post(preview(this.course, hidden)).andExpect(status().isAccepted());
		// CSRF token이 없으면 상태를 바꾸는 요청이 아니어도 403이다.
		this.mvc.perform(post(preview(this.course, fileId)).cookie(new Cookie("ASTRA_SESSION", this.studentToken)))
			.andExpect(status().isForbidden());
		// 404로 거부한 요청은 대기열에 아무것도 넣지 않았다.
		assertThat(jobs(hidden)).hasSize(1);
	}

	@Test
	void deletedQuarantinedAndRejectedFilesHaveNoPreviewThumbnailOrImage() throws Exception {
		long deleted = attached(pdf(1), "application/pdf", "d.pdf", true);
		long quarantined = attached(pdf(1), "application/pdf", "q.pdf", true);
		long rejected = attached(png(), "image/png", "r.png", true);
		this.student.post(preview(this.course, deleted));
		drainQueue();
		this.jdbc.sql("UPDATE files SET state = 'deleted', deleted_at = CURRENT_TIMESTAMP(3) WHERE file_id = :id")
			.param("id", deleted)
			.update();
		scanState(quarantined, "quarantined", "pending");
		scanState(rejected, "rejected", "infected");

		for (long fileId : List.of(deleted, quarantined, rejected)) {
			this.student.post(preview(this.course, fileId)).andExpect(status().isNotFound());
			this.student.get(thumbnail(this.course, fileId, "thumb_1")).andExpect(status().isNotFound());
			this.student.get(image(this.course, fileId)).andExpect(status().isNotFound());
			this.professor.post(preview(this.course, fileId)).andExpect(status().isNotFound());
		}
		assertThat(jobs(quarantined)).isEmpty();
	}

	@Test
	void officeDocumentUploadedByTheInstructorIsConvertedInsideTheConverterAndShownAsPages() throws Exception {
		long uploadId = this.professor.uploaded(this.course, "slides.pptx", TestOfficeFiles.pptx(4, null));
		this.professor.postJson("/api/courses/" + this.course + "/materials",
				"{\"weekNo\":1,\"title\":\"슬라이드\",\"uploadIds\":[" + uploadId + "]}").andExpect(status().isCreated());
		long fileId = fileOfUpload(uploadId);

		this.student.post(preview(this.course, fileId)).andExpect(status().isAccepted());
		drainQueue();

		this.student.post(preview(this.course, fileId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ready"))
			.andExpect(jsonPath("$.pageCount").value(4));
		assertThat(exists(this.buckets.delivery(), "doc/" + key(fileId) + "/004.jpg")).isTrue();
		assertThat(exists(this.buckets.content(), "renditions/" + key(fileId) + "/thumbnails/thumb_1.jpg")).isTrue();
	}

	@Test
	void reportsFailedConversionsWithACodeAndDoesNotRetryThemFromThePreviewRequest() throws Exception {
		long encrypted = attached(TestPdfs.pdf(1, "user-pw", null), "application/pdf", "locked.pdf", true);
		long broken = attached(TestOfficeFiles.pptx(1, "astra-test/fail"), TestOfficeFiles.PPTX, "broken.pptx", true);
		long slow = attached(TestOfficeFiles.pptx(1, "astra-test/timeout"), TestOfficeFiles.PPTX, "slow.pptx", true);
		for (long fileId : List.of(encrypted, broken, slow)) {
			this.student.post(preview(this.course, fileId)).andExpect(status().isAccepted());
		}
		drainQueue();

		MvcResult failed = this.student.post(preview(this.course, encrypted))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("failed"))
			.andExpect(jsonPath("$.code").value("encrypted"))
			.andReturn();
		assertThat(deliveryCookies(failed)).isEmpty();
		this.student.post(preview(this.course, broken)).andExpect(jsonPath("$.code").value("failed"));
		this.student.post(preview(this.course, slow)).andExpect(jsonPath("$.code").value("timeout"));
		this.student.get(thumbnail(this.course, encrypted, "thumb_1")).andExpect(status().isNotFound());
		assertThat(jobs(encrypted)).singleElement().satisfies((j) -> assertThat(j).containsEntry("conversion_state", "failed"));
		// 실패한 변환이 쓴 객체는 남지 않는다.
		assertThat(exists(this.buckets.delivery(), "doc/" + key(broken) + "/")).isFalse();
		assertThat(exists(this.buckets.content(), "renditions/" + key(broken) + "/")).isFalse();
	}

	@Test
	void formatsThatAreNotConvertedAnswerUnsupportedWithoutQueueingAnything() throws Exception {
		long hwp = attached(new byte[] { (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, 1 }, "application/x-hwp", "a.hwp", true);
		long hwpx = attached(TestOfficeFiles.docx(), "application/hwp+zip", "a.hwpx", true);
		long text = attached("notes".getBytes(), "text/plain", "a.txt", true);
		long picture = attached(png(), "image/png", "a.png", true);

		for (long fileId : List.of(hwp, hwpx, text, picture)) {
			this.student.post(preview(this.course, fileId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("unsupported"));
			assertThat(jobs(fileId)).isEmpty();
		}
	}

	@Test
	void aConverterWithoutTheOfficeEngineMakesThePreviewUnsupported() throws Exception {
		long fileId = attached(TestOfficeFiles.docx(), TestOfficeFiles.DOCX, "a.docx", true);
		this.jdbc.sql("""
				INSERT INTO conversion_jobs (file_id, file_version_no, conv_type, converted_doc_key, conversion_state, error_code)
				VALUES (:file, 1, 'img', :key, 'failed', 'unsupported_format')
				""").param("file", fileId).param("key", newKey()).update();

		this.student.post(preview(this.course, fileId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("unsupported"));
	}

	@Test
	void aDocumentOverThePageLimitFailsWithTooManyPages() throws Exception {
		long fileId = attached(pdf(DocumentRenditionService.MAX_PAGES + 1), "application/pdf", "long.pdf", true);
		this.student.post(preview(this.course, fileId)).andExpect(status().isAccepted());
		drainQueue();

		this.student.post(preview(this.course, fileId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("failed"))
			.andExpect(jsonPath("$.code").value("too_many_pages"));
	}

	@Test
	void everyStoredFailureMapsToOneDocumentedCode() throws Exception {
		Map<String, String> expected = Map.ofEntries(Map.entry("encrypted", "encrypted"),
				Map.entry("invalid_pdf", "invalid_document"), Map.entry("empty", "empty"),
				Map.entry("too_many_pages", "too_many_pages"), Map.entry("too_large", "too_large"),
				Map.entry("timeout", "timeout"), Map.entry("unavailable", "unavailable"),
				Map.entry("interrupted", "unavailable"), Map.entry("storage", "unavailable"),
				Map.entry("failed", "failed"), Map.entry("something_new", "failed"));
		for (Map.Entry<String, String> entry : expected.entrySet()) {
			long fileId = attached(pdf(1), "application/pdf", "f.pdf", true);
			this.jdbc.sql("""
					INSERT INTO conversion_jobs (file_id, file_version_no, conv_type, converted_doc_key, conversion_state, error_code)
					VALUES (:file, 1, 'img', :key, 'failed', :code)
					""").param("file", fileId).param("key", newKey()).param("code", entry.getKey()).update();

			this.student.post(preview(this.course, fileId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.state").value("failed"))
				.andExpect(jsonPath("$.code").value(entry.getValue()));
		}
	}

	@Test
	void retriesWhenTheConverterIsUnreachableAndFailsAfterTheLastAttempt() throws Exception {
		long fileId = attached(pdf(1), "application/pdf", "down.pdf", true);
		DocumentRenditionService down = new DocumentRenditionService(this.fileRecords, this.conversions, this.store,
				(document, format, maxPages, pages) -> {
					throw new ConversionException(ConversionException.Code.UNAVAILABLE, "converter에 연결하지 못했습니다.");
				}, new ConversionProperties(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(15), 2,
						Duration.ofMinutes(10), "down-worker"),
				this.transactionManager, new SimpleMeterRegistry());

		assertThat(code(() -> down.prepare(fileId))).isEqualTo(RenditionException.Code.CONVERSION_FAILED);

		assertThat(jobs(fileId)).singleElement()
			.satisfies((j) -> assertThat(j).containsEntry("conversion_state", "requested")
				.containsEntry("attempt_count", 1L)
				.containsEntry("error_code", "unavailable"));
		// 다시 시도할 시각 전에는 worker가 잡지 않는다. 미리보기는 아직 변환 중이다.
		assertThat(this.renditions.convertNext(WORKER)).isFalse();
		this.student.post(preview(this.course, fileId)).andExpect(status().isAccepted());

		assertThat(code(() -> down.prepare(fileId))).isEqualTo(RenditionException.Code.CONVERSION_FAILED);

		assertThat(jobs(fileId)).singleElement()
			.satisfies((j) -> assertThat(j).containsEntry("conversion_state", "failed").containsEntry("attempt_count", 2L));
		this.student.post(preview(this.course, fileId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("failed"))
			.andExpect(jsonPath("$.code").value("unavailable"));
	}

	@Test
	void aConversionWhoseWorkerStoppedIsPickedUpAgainUntilTheAttemptLimit() throws Exception {
		long resumed = attached(pdf(2), "application/pdf", "resumed.pdf", true);
		long abandoned = attached(pdf(2), "application/pdf", "abandoned.pdf", true);
		this.student.post(preview(this.course, resumed));
		this.student.post(preview(this.course, abandoned));
		long resumedJob = jobId(resumed);
		long abandonedJob = jobId(abandoned);
		assertThat(this.conversions.claim(resumedJob, "stopped-worker", Duration.ofMinutes(15))).isPresent();
		assertThat(this.conversions.claim(abandonedJob, "stopped-worker", Duration.ofMinutes(15))).isPresent();
		this.jdbc.sql("UPDATE conversion_jobs SET attempt_count = 3 WHERE conversion_job_id = :id").param("id", abandonedJob).update();
		this.s3.putObject((b) -> b.bucket(this.buckets.delivery()).key("doc/" + keyOfJob(abandonedJob) + "/001.jpg"),
				RequestBody.fromBytes(new byte[] { 1 }));
		// lease가 남아 있으면 다른 worker가 잡지 않는다.
		this.renditions.recoverExpired();
		assertThat(state(resumedJob)).isEqualTo("converting");
		this.jdbc.sql("UPDATE conversion_jobs SET lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE conversion_job_id IN (:ids)")
			.param("ids", List.of(resumedJob, abandonedJob))
			.update();

		this.renditions.recoverExpired();
		// 멈춘 worker는 lease를 잃었으므로 늦게 끝나도 완료를 기록하지 못한다.
		assertThat(this.conversions.ready(resumedJob, "stopped-worker", 2,
				new ConversionRecords.Thumbnail("thumb_1", "renditions/x/thumbnails/thumb_1.jpg", "image/jpeg", 1)))
			.isFalse();
		drainQueue();

		assertThat(state(resumedJob)).isEqualTo("ready");
		this.student.post(preview(this.course, resumed)).andExpect(jsonPath("$.pageCount").value(2));
		assertThat(jobs(abandoned)).singleElement()
			.satisfies((j) -> assertThat(j).containsEntry("conversion_state", "failed").containsEntry("error_code", "interrupted"));
		assertThat(exists(this.buckets.delivery(), "doc/" + keyOfJob(abandonedJob) + "/")).isFalse();
	}

	@Test
	void manySimultaneousPreviewRequestsQueueOneConversion() throws Exception {
		long fileId = attached(pdf(1), "application/pdf", "busy.pdf", true);
		int threads = 8;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<Integer>> results = new ArrayList<>();
		try {
			for (int i = 0; i < threads; i++) {
				results.add(pool.submit(() -> {
					start.await();
					return this.student.post(preview(this.course, fileId)).andReturn().getResponse().getStatus();
				}));
			}
			start.countDown();
			for (Future<Integer> result : results) {
				assertThat(result.get(60, TimeUnit.SECONDS)).isEqualTo(202);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(jobs(fileId)).hasSize(1);
	}

	@Test
	void twoWorkersDrainingNeighbouringConversionsConvertEachExactlyOnce() throws Exception {
		List<Long> fileIds = new ArrayList<>();
		for (int i = 0; i < 4; i++) {
			long fileId = attached(pdf(1), "application/pdf", "n" + i + ".pdf", true);
			this.student.post(preview(this.course, fileId)).andExpect(status().isAccepted());
			fileIds.add(fileId);
		}
		ExecutorService pool = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		try {
			List<Future<?>> workers = new ArrayList<>();
			for (String owner : List.of("worker-a", "worker-b")) {
				workers.add(pool.submit(() -> {
					start.await();
					while (this.renditions.convertNext(owner)) {
						// 대기열이 빌 때까지
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> worker : workers) {
				worker.get(120, TimeUnit.SECONDS);
			}
		}
		finally {
			pool.shutdownNow();
		}

		for (long fileId : fileIds) {
			assertThat(jobs(fileId)).singleElement()
				.satisfies((j) -> assertThat(j).containsEntry("conversion_state", "ready").containsEntry("attempt_count", 1L));
			assertThat(this.jdbc.sql("SELECT COUNT(*) FROM file_thumbnails WHERE conversion_job_id = :id")
				.param("id", jobId(fileId))
				.query(Long.class)
				.single()).isOne();
		}
	}

	/**
	 * worker 하나의 scheduler thread가 여럿이면 같은 process 안에서 lease가 끝난 변환을 다른 thread가 다시 잡을 수 있다(A1). 두 시도는
	 * worker 이름이 같아도 잡을 때마다 다른 lease 소유자로 남아, 늦게 끝난 앞 시도가 새 시도의 행을 닫거나 객체를 지우지 못한다.
	 */
	@Test
	void aStaleAttemptCannotCloseTheConversionThatTheSameWorkerTookOverAgain() throws Exception {
		long fileId = attached(pdf(1), "application/pdf", "stale.pdf", true);
		this.student.post(preview(this.course, fileId)).andExpect(status().isAccepted());
		CountDownLatch staleStarted = new CountDownLatch(1);
		CountDownLatch staleRelease = new CountDownLatch(1);
		DocumentRenditionService stale = worker((document, format, maxPages, pages) -> {
			staleStarted.countDown();
			awaitQuietly(staleRelease);
			throw new ConversionException(ConversionException.Code.FAILED, "앞 시도가 늦게 실패했다");
		});
		CountDownLatch retryStarted = new CountDownLatch(1);
		CountDownLatch retryRelease = new CountDownLatch(1);
		DocumentRenditionService retry = worker((document, format, maxPages, pages) -> {
			retryStarted.countDown();
			awaitQuietly(retryRelease);
			return this.pageConverter.convert(document, format, maxPages, pages);
		});
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			Future<Boolean> first = pool.submit(() -> stale.convertNext(WORKER));
			assertThat(staleStarted.await(30, TimeUnit.SECONDS)).isTrue();
			this.jdbc.sql("""
					UPDATE conversion_jobs SET lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
					 WHERE file_id = :file AND conversion_state = 'converting'
					""").param("file", fileId).update();
			assertThat(this.renditions.recoverExpired()).isPositive();
			Future<Boolean> second = pool.submit(() -> retry.convertNext(WORKER));
			assertThat(retryStarted.await(30, TimeUnit.SECONDS)).isTrue();

			staleRelease.countDown();
			assertThat(first.get(30, TimeUnit.SECONDS)).isTrue();
			assertThat(jobs(fileId)).singleElement()
				.satisfies((j) -> assertThat(j).containsEntry("conversion_state", "converting")
					.containsEntry("attempt_count", 2L));

			retryRelease.countDown();
			assertThat(second.get(60, TimeUnit.SECONDS)).isTrue();
		}
		finally {
			staleRelease.countDown();
			retryRelease.countDown();
			pool.shutdownNow();
		}
		assertThat(jobs(fileId)).singleElement()
			.satisfies((j) -> assertThat(j).containsEntry("conversion_state", "ready"));
		this.student.post(preview(this.course, fileId)).andExpect(status().isOk());
	}

	private DocumentRenditionService worker(PageConverter converter) {
		return new DocumentRenditionService(this.fileRecords, this.conversions, this.store, converter,
				new ConversionProperties(Duration.ofHours(1), Duration.ofHours(1), Duration.ofMinutes(15), 3,
						Duration.ofMinutes(10), WORKER),
				this.transactionManager, new SimpleMeterRegistry());
	}

	private static void awaitQuietly(CountDownLatch latch) {
		try {
			latch.await(60, TimeUnit.SECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	@Test
	void thumbnailIsServedOnlyAfterTheWorkerMadeItWithItsStoredTypeAndPrivateCaching() throws Exception {
		long fileId = attached(pdf(2), "application/pdf", "t.pdf", true);

		// 요청으로는 만들지 않는다. GET은 대기열에 아무것도 넣지 않는다(SC-04).
		this.student.get(thumbnail(this.course, fileId, "thumb_1")).andExpect(status().isNotFound());
		assertThat(jobs(fileId)).isEmpty();

		this.student.post(preview(this.course, fileId));
		drainQueue();

		MvcResult result = this.student.get(thumbnail(this.course, fileId, "thumb_1"))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/jpeg"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Cache-Control", "max-age=300, private"))
			.andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
			.andReturn();
		var image = ImageIO.read(new ByteArrayInputStream(result.getResponse().getContentAsByteArray()));
		assertThat(Math.max(image.getWidth(), image.getHeight())).isEqualTo(360);

		this.professor.get(thumbnail(this.course, fileId, "thumb_1")).andExpect(status().isOk());
		this.outsider.get(thumbnail(this.course, fileId, "thumb_1")).andExpect(status().isNotFound());
		this.otherProfessor.get(thumbnail(this.otherCourse, fileId, "thumb_1")).andExpect(status().isNotFound());
		this.anonymous.get(thumbnail(this.course, fileId, "thumb_1")).andExpect(status().isUnauthorized());
		// 변형은 정해진 값만 받는다. 경로 조각을 저장 key에 잇지 않는다(FILES-13).
		for (String variant : List.of("thumb_2", "THUMB_1", "thumb_1.jpg", "document.json")) {
			this.student.get(thumbnail(this.course, fileId, variant)).andExpect(status().isNotFound());
		}
		// 경로를 거슬러 오르는 값은 요청 방화벽이 먼저 거부한다.
		for (String variant : List.of("..", "%2e%2e%2fdocument.json")) {
			this.student.get(thumbnail(this.course, fileId, variant)).andExpect(status().is4xxClientError());
		}
	}

	@Test
	void showsOnlyServerDetectedPngAndJpegInlineInASandbox() throws Exception {
		byte[] pngBytes = png();
		long png = attached(pngBytes, "image/png", "diagram.png", true);
		long jpeg = attached(new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0x00 }, "image/jpeg", "photo.jpg", true);

		this.student.get(image(this.course, png))
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "image/png"))
			.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("inline;")))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(content().bytes(pngBytes));
		this.student.get(image(this.course, jpeg)).andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg"));
		this.outsider.get(image(this.course, png)).andExpect(status().isNotFound());
		this.otherProfessor.get(image(this.otherCourse, png)).andExpect(status().isNotFound());
		this.anonymous.get(image(this.course, png)).andExpect(status().isUnauthorized());
	}

	@Test
	void everythingElseStaysAttachmentOnly() throws Exception {
		long svg = attached("<svg onload=\"alert(1)\"/>".getBytes(), "image/svg+xml", "logo.svg", true);
		long disguised = attached("<html>".getBytes(), "text/html", "logo.png", true);
		long gif = attached("GIF89a".getBytes(), "image/gif", "a.gif", true);
		long pdf = attached(pdf(1), "application/pdf", "a.pdf", true);
		long legacy = attached(png(), "image/png", "legacy.png", true);
		// 업로드 API 이전의 파일은 서버가 판정한 형식이 없다. 선언한 MIME만으로는 inline으로 보이지 않는다.
		this.jdbc.sql("UPDATE file_blobs b JOIN files f ON f.file_blob_id = b.file_blob_id SET b.detected_mime_type = NULL WHERE f.file_id = :id")
			.param("id", legacy)
			.update();

		for (long fileId : List.of(svg, disguised, gif, pdf, legacy)) {
			this.student.get(image(this.course, fileId)).andExpect(status().isNotFound());
		}
		this.student.get("/api/courses/" + this.course + "/files/" + svg + "/content")
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")));
	}

	@Test
	void storageGcPurgesPreviewRenditionsThumbnailsAndQueuedConversionsOfDeletedFiles() throws Exception {
		long convertedUpload = this.professor.uploaded(this.course, "gc.pdf", pdf(2));
		long queuedUpload = this.professor.uploaded(this.course, "queued.pptx", TestOfficeFiles.pptx(1, null));
		long material = ApiClient.json(this.professor
			.postJson("/api/courses/" + this.course + "/materials",
					"{\"weekNo\":1,\"title\":\"GC\",\"uploadIds\":[" + convertedUpload + "," + queuedUpload + "]}")
			.andExpect(status().isCreated())).get("activityId").asLong();
		long converted = fileOfUpload(convertedUpload);
		long queued = fileOfUpload(queuedUpload);
		this.student.post(preview(this.course, converted));
		drainQueue();
		this.student.post(preview(this.course, queued)).andExpect(status().isAccepted());
		String key = key(converted);
		long job = jobId(converted);
		assertThat(exists(this.buckets.content(), "renditions/" + key + "/thumbnails/thumb_1.jpg")).isTrue();

		this.professor
			.send(MockMvcRequestBuilders.delete("/api/courses/" + this.course + "/materials/" + material).header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());
		this.student.post(preview(this.course, converted)).andExpect(status().isNotFound());
		this.student.get(thumbnail(this.course, converted, "thumb_1")).andExpect(status().isNotFound());
		this.jdbc.sql("UPDATE files SET deleted_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR) WHERE file_id IN (:ids)")
			.param("ids", List.of(converted, queued))
			.update();

		StorageGcDriver.drain(this.context);

		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM file_thumbnails WHERE conversion_job_id = :id")
			.param("id", job)
			.query(Long.class)
			.single()).isZero();
		assertThat(exists(this.buckets.content(), "renditions/" + key + "/")).isFalse();
		assertThat(exists(this.buckets.delivery(), "doc/" + key + "/")).isFalse();
		assertThat(jobs(converted)).isEmpty();
		assertThat(this.jdbc.sql("SELECT conversion_state FROM conversion_jobs WHERE file_id = :id")
			.param("id", queued)
			.query(String.class)
			.list()).containsExactly("expired");
		// 닫힌 대기 변환은 worker가 잡지 않는다.
		drainQueue();
		assertThat(this.jdbc.sql("SELECT conversion_state FROM conversion_jobs WHERE file_id = :id")
			.param("id", queued)
			.query(String.class)
			.list()).containsExactly("expired");
	}

	// 대기열이 빌 때까지 변환한다. 다른 테스트가 남긴 대기 변환도 함께 처리한다.
	private void drainQueue() {
		while (this.renditions.convertNext(WORKER)) {
			// 다음 변환
		}
	}

	/** 공개 여부를 정한 자료 활동에 파일 하나를 첨부한다. MIME은 저장할 때 서버가 판정한 값으로 넣는다. */
	private long attached(byte[] content, String mime, String name, boolean published) {
		long fileId = this.data.file(name, mime, content, "clean");
		this.data.activity(this.course, "lecture_material", published, null, this.data.attachmentGroup(fileId));
		return fileId;
	}

	private void scanState(long fileId, String blobState, String fileState) {
		this.jdbc.sql("""
				UPDATE file_blobs b JOIN files f ON f.file_blob_id = b.file_blob_id
				   SET b.blob_state = :blob, f.scan_state = :file
				 WHERE f.file_id = :id
				""").param("blob", blobState).param("file", fileState).param("id", fileId).update();
	}

	/** 지금 보이는 rendition 행(GC가 닫은 행은 빼고). */
	private List<Map<String, Object>> jobs(long fileId) {
		return this.jdbc.sql("""
				SELECT conversion_job_id, conversion_state, attempt_count, error_code FROM conversion_jobs
				 WHERE file_id = :id AND conversion_state <> 'expired' ORDER BY conversion_job_id
				""").param("id", fileId).query().listOfRows();
	}

	private long jobId(long fileId) {
		return ((Number) jobs(fileId).getLast().get("conversion_job_id")).longValue();
	}

	private String key(long fileId) {
		return this.jdbc.sql("SELECT converted_doc_key FROM conversion_jobs WHERE file_id = :id AND conversion_state <> 'expired' ORDER BY conversion_job_id DESC LIMIT 1")
			.param("id", fileId)
			.query(String.class)
			.single();
	}

	private String keyOfJob(long jobId) {
		return this.jdbc.sql("SELECT converted_doc_key FROM conversion_jobs WHERE conversion_job_id = :id")
			.param("id", jobId)
			.query(String.class)
			.single();
	}

	private String state(long jobId) {
		return this.conversions.state(jobId);
	}

	private long fileOfUpload(long uploadId) {
		return this.jdbc.sql("SELECT file_id FROM upload_sessions WHERE upload_id = :id")
			.param("id", uploadId)
			.query(Long.class)
			.single();
	}

	// 전달 경로 cookie만 본다. 상태를 바꾸는 요청의 응답에는 CSRF cookie도 실린다.
	private static List<String> deliveryCookies(MvcResult result) {
		return result.getResponse().getHeaders("Set-Cookie").stream().filter((c) -> c.startsWith("CloudFront-")).toList();
	}

	private boolean exists(String bucket, String prefix) {
		return !this.s3.listObjectsV2((b) -> b.bucket(bucket).prefix(prefix)).contents().isEmpty();
	}

	private static RenditionException.Code code(Runnable work) {
		try {
			work.run();
		}
		catch (RenditionException ex) {
			return ex.code();
		}
		throw new AssertionError("RenditionException이 나지 않았습니다.");
	}

	private static String preview(long courseId, long fileId) {
		return "/api/courses/" + courseId + "/files/" + fileId + "/preview-sessions";
	}

	private static String thumbnail(long courseId, long fileId, String variant) {
		return "/api/courses/" + courseId + "/files/" + fileId + "/thumbnail/" + variant;
	}

	private static String image(long courseId, long fileId) {
		return "/api/courses/" + courseId + "/files/" + fileId + "/image";
	}

	private static String newKey() {
		return (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");
	}

	private static byte[] pdf(int pages) {
		return TestPdfs.pdf(pages, null, UUID.randomUUID().toString());
	}

	private static byte[] png() {
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
		byte[] random = UUID.randomUUID().toString().getBytes();
		byte[] bytes = new byte[signature.length + random.length];
		System.arraycopy(signature, 0, bytes, 0, signature.length);
		System.arraycopy(random, 0, bytes, signature.length, random.length);
		return bytes;
	}

}
