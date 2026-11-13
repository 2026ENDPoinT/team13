package com.stackcorp.astra.files.scan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.files.StorageGcDriver;
import com.stackcorp.astra.TestVideos;
import com.stackcorp.astra.files.VideoRunnerDriver;
import com.stackcorp.astra.files.api.ContentScanner;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.RenditionException;
import com.stackcorp.astra.files.api.StoredFiles;
import com.stackcorp.astra.files.storage.ObjectStore;
import com.stackcorp.astra.files.storage.ObjectStore.Bucket;
import com.stackcorp.astra.integration.scan.FakeClamd;
import com.stackcorp.astra.integration.scan.FakeClamd.Mode;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;

/**
 * 검사기를 붙인 배포의 업로드 흐름(SC-08, SEC-15, ADR-0018). 새 내용은 검사가 끝날 때까지 quarantined이고, 첨부·열람·변환되지 않는다.
 * clean으로 가는 길은 검사기의 clean 판정 하나뿐이다. 검사기 설정이 달라 Spring context를 따로 쓴다. 검사기는 같은 process의 가짜
 * clamd다.
 */
@TestPropertySource(properties = { "astra.scan.required=true", "astra.scan.poll-interval=1h",
		"astra.scan.retry-backoff=10m", "astra.scan.max-retry-backoff=1h", "astra.scan.worker-id=scan-test-1",
		"astra.scan.clamd.host=127.0.0.1", "astra.scan.clamd.timeout=5s" })
class ScanFlowTests extends IntegrationTest {

	static final FakeClamd clamd = FakeClamd.start();

	@DynamicPropertySource
	static void clamdPort(DynamicPropertyRegistry registry) {
		registry.add("astra.scan.clamd.port", clamd::port);
	}

	@AfterAll
	static void stopClamd() {
		clamd.close();
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ScanWorker worker;

	@Autowired
	ScanRecords records;

	@Autowired
	ObjectProvider<ContentScanner> scanner;

	@Autowired
	ObjectStore store;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	ScanProperties properties;

	@Autowired
	StoredFiles files;

	@Autowired
	DocumentRenditions renditions;

	@Autowired
	ApplicationContext context;

	@Autowired
	MeterRegistry meters;

	long course;

	ApiClient student;

	@BeforeEach
	void course() {
		clamd.reset();
		this.course = this.data.course();
		long stu = this.data.user("STUDENT");
		this.data.enroll(this.course, stu);
		this.student = new ApiClient(this.mvc, this.data.session(stu));
	}

	@Test
	void keepsNewContentQuarantinedUntilTheScannerCleansItThenItCanBeAttached() throws Exception {
		ApiClient professor = instructor();
		byte[] notes = notes();
		long uploadId = uploadId(upload(professor, notes).andExpect(jsonPath("$.state").value("quarantined")));
		long fileId = fileOf(uploadId);

		// 검사 전에는 첨부, 열람, 변환이 모두 안 된다.
		attach(professor, uploadId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attachment_not_ready"));
		assertThat(this.files.find(fileId)).hasValueSatisfying((f) -> assertThat(f.ready()).isFalse());
		assertThatThrownBy(() -> this.renditions.prepare(fileId)).isInstanceOfSatisfying(RenditionException.class,
				(ex) -> assertThat(ex.code()).isEqualTo(RenditionException.Code.NOT_READY));

		drain();

		professor.get("/api/uploads/" + uploadId).andExpect(jsonPath("$.state").value("clean"));
		assertThat(row(uploadId)).containsEntry("blob_state", "clean").containsEntry("scan_state", "clean");
		assertThat(clamd.scansOf(notes)).isEqualTo(1);
		long attached = ApiClient.json(attach(professor, uploadId).andExpect(status().isCreated()))
			.get("files")
			.get(0)
			.get("fileId")
			.asLong();
		assertThat(attached).isEqualTo(fileId);
		this.student.get(contentPath(fileId)).andExpect(status().isOk()).andExpect(content().bytes(notes));
	}

	@Test
	void rejectsInfectedContentEverywhereAndTellsTheUploaderOnlyTheState() throws Exception {
		ApiClient professor = instructor();
		byte[] eicar = infected();
		long uploadId = uploadId(upload(professor, eicar).andExpect(jsonPath("$.state").value("quarantined")));
		long fileId = fileOf(uploadId);

		drain();

		String view = professor.get("/api/uploads/" + uploadId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("rejected"))
			.andReturn()
			.getResponse()
			.getContentAsString();
		assertThat(view).doesNotContain(FakeClamd.EICAR_SIGNATURE).doesNotContain("EICAR").doesNotContain("infected");
		attach(professor, uploadId).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attachment_not_ready"));
		Map<String, Object> row = row(uploadId);
		assertThat(row).containsEntry("blob_state", "rejected")
			.containsEntry("scan_signature", FakeClamd.EICAR_SIGNATURE)
			.containsEntry("scan_state", "infected")
			.containsEntry("upload_state", "rejected")
			.containsEntry("rejection_code", "infected");
		// 거부한 내용의 객체는 지운다. 행은 남겨 같은 내용을 다시 받지 않는다.
		assertThat(row.get("purged_at")).isNotNull();
		assertThat(this.store.open(Bucket.CONTENT, (String) row.get("storage_key"))).isEmpty();

		// 어떤 경로로 부모에 붙어 있더라도 읽히지 않는다.
		long group = this.data.attachmentGroup(fileId);
		this.data.activity(this.course, "lecture_material", true, null, group);
		professor.get(contentPath(fileId)).andExpect(status().isNotFound());
		this.student.get(contentPath(fileId)).andExpect(status().isNotFound());
		assertThat(this.files.find(fileId)).hasValueSatisfying((f) -> assertThat(f.ready()).isFalse());

		// 같은 내용을 다시 올리면 검사기를 다시 부르지 않고 바로 거부한다.
		instructor().upload(this.course, "lecture_material", "copy.txt", eicar, null)
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("content_rejected"));
		assertThat(clamd.scansOf(eicar)).isEqualTo(1);
		assertThat(this.store.open(Bucket.CONTENT, (String) row.get("storage_key"))).isEmpty();
	}

	@Test
	void keepsContentQuarantinedWhileTheScannerIsDownAndRetriesWithGrowingBackoff() throws Exception {
		ApiClient professor = instructor();
		byte[] notes = notes();
		long uploadId = uploadId(upload(professor, notes));
		clamd.mode(Mode.CLOSE_EARLY);

		drain();

		professor.get("/api/uploads/" + uploadId).andExpect(jsonPath("$.state").value("quarantined"));
		Map<String, Object> first = row(uploadId);
		assertThat(first).containsEntry("blob_state", "quarantined").containsEntry("scan_state", "pending");
		assertThat(number(first, "scan_attempt_count")).isEqualTo(1);
		assertThat(number(first, "retry_in")).isBetween(590L, 600L);

		// 재시도 시각 전에는 다시 부르지 않는다.
		int connections = clamd.connections();
		drain();
		assertThat(clamd.connections()).isEqualTo(connections);

		makeDue(uploadId);
		drain();
		Map<String, Object> second = row(uploadId);
		assertThat(second).containsEntry("blob_state", "quarantined");
		assertThat(number(second, "scan_attempt_count")).isEqualTo(2);
		assertThat(number(second, "retry_in")).isBetween(1190L, 1200L);

		clamd.mode(Mode.NORMAL);
		makeDue(uploadId);
		drain();
		professor.get("/api/uploads/" + uploadId).andExpect(jsonPath("$.state").value("clean"));
		assertThat(number(row(uploadId), "scan_attempt_count")).isEqualTo(3);
		assertThat(clamd.scansOf(notes)).isEqualTo(1);
	}

	@Test
	void sharesOneVerdictAmongUploadsOfTheSameContent() throws Exception {
		byte[] notes = notes();
		ApiClient first = instructor();
		ApiClient second = instructor();
		long a = uploadId(upload(first, notes));
		// 검사를 기다리는 내용을 다시 올리면 같은 판정을 기다린다.
		long b = uploadId(upload(second, notes).andExpect(jsonPath("$.state").value("quarantined")));
		assertThat(row(a).get("file_blob_id")).isEqualTo(row(b).get("file_blob_id"));

		drain();

		first.get("/api/uploads/" + a).andExpect(jsonPath("$.state").value("clean"));
		second.get("/api/uploads/" + b).andExpect(jsonPath("$.state").value("clean"));
		assertThat(clamd.scansOf(notes)).isEqualTo(1);
		// 이미 clean인 내용은 다시 검사하지 않는다.
		upload(instructor(), notes).andExpect(jsonPath("$.state").value("clean"));
		assertThat(clamd.scansOf(notes)).isEqualTo(1);

		byte[] eicar = infected();
		ApiClient third = instructor();
		long c = uploadId(upload(first, eicar));
		long d = uploadId(upload(third, eicar));

		drain();

		first.get("/api/uploads/" + c).andExpect(jsonPath("$.state").value("rejected"));
		third.get("/api/uploads/" + d).andExpect(jsonPath("$.state").value("rejected"));
		assertThat(row(d)).containsEntry("scan_state", "infected");
		assertThat(clamd.scansOf(eicar)).isEqualTo(1);
	}

	@Test
	void countsVerdictsAndScannerOutagesAndReportsTheQueueLength() throws Exception {
		ApiClient professor = instructor();
		StorageGcDriver.sampleBacklog(this.context);
		double queued = gauge("astra.files.scan.backlog");
		double unavailable = results("unavailable");
		double clean = results("clean");
		long uploadId = uploadId(upload(professor, notes()));
		StorageGcDriver.sampleBacklog(this.context);
		assertThat(gauge("astra.files.scan.backlog")).isEqualTo(queued + 1);

		clamd.mode(Mode.CLOSE_EARLY);
		drain();
		// 다른 테스트가 남긴 내용이 함께 잡힐 수 있어 늘어난 수의 하한만 본다.
		assertThat(results("unavailable")).isGreaterThanOrEqualTo(unavailable + 1);
		professor.get("/api/uploads/" + uploadId).andExpect(jsonPath("$.state").value("quarantined"));

		clamd.mode(Mode.NORMAL);
		makeDue(uploadId);
		drain();
		assertThat(results("clean")).isGreaterThanOrEqualTo(clean + 1);
		StorageGcDriver.sampleBacklog(this.context);
		assertThat(gauge("astra.files.scan.backlog")).isLessThanOrEqualTo(queued);
		// tag는 판정과 사유 코드뿐이다.
		assertThat(this.meters.find("astra.files.scan.results").counters())
			.allSatisfy((c) -> assertThat(c.getId().getTags()).extracting(Tag::getKey)
				.containsExactlyInAnyOrder("result", "reason", "role"));
	}

	private double gauge(String name) {
		return this.meters.get(name).gauge().value();
	}

	private double results(String result) {
		return this.meters.find("astra.files.scan.results")
			.tag("result", result)
			.counters()
			.stream()
			.mapToDouble(Counter::count)
			.sum();
	}

	@Test
	void twoWorkersNeverScanTheSameContentTwice() throws Exception {
		List<byte[]> contents = new ArrayList<>();
		List<Long> uploads = new ArrayList<>();
		for (int u = 0; u < 2; u++) {
			ApiClient professor = instructor();
			for (int i = 0; i < 4; i++) {
				byte[] notes = notes();
				contents.add(notes);
				uploads.add(uploadId(upload(professor, notes)));
			}
		}
		clamd.slow(java.time.Duration.ofMillis(100));
		ScanWorker other = new ScanWorker(this.records, this.scanner, this.store, this.transactionManager,
				new ScanProperties(false, this.properties.pollInterval(), this.properties.batchSize(),
						this.properties.lease(), this.properties.retryBackoff(), this.properties.maxRetryBackoff(),
						"scan-test-2"),
				this.meters);
		CountDownLatch start = new CountDownLatch(1);

		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<?>> runs = new ArrayList<>();
			for (ScanWorker w : new ScanWorker[] { this.worker, other }) {
				runs.add(pool.submit(() -> {
					start.await();
					while (w.runOnce() > 0) {
						// 잡을 내용이 없을 때까지 돈다.
					}
					return null;
				}));
			}
			start.countDown();
			for (Future<?> run : runs) {
				run.get(60, TimeUnit.SECONDS);
			}
		}

		for (int i = 0; i < contents.size(); i++) {
			assertThat(clamd.scansOf(contents.get(i))).isEqualTo(1);
			Map<String, Object> row = row(uploads.get(i));
			assertThat(row).containsEntry("upload_state", "clean");
			assertThat(number(row, "scan_attempt_count")).isEqualTo(1);
		}
	}

	@Test
	void respectsAnotherWorkersLeaseAndIgnoresAResultFromAnExpiredOne() throws Exception {
		byte[] notes = notes();
		long uploadId = uploadId(upload(instructor(), notes));
		long blobId = number(row(uploadId), "file_blob_id");
		lease(blobId, "stale-worker", "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)");

		drain();
		assertThat(clamd.scansOf(notes)).isZero();
		assertThat(row(uploadId)).containsEntry("blob_state", "quarantined");

		lease(blobId, "stale-worker", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)");
		drain();
		assertThat(row(uploadId)).containsEntry("blob_state", "clean");

		// lease를 잃은 worker가 늦게 낸 결과는 확정된 판정을 바꾸지 못한다.
		Boolean overwritten = this.transaction
			.execute((tx) -> this.records.markInfected(blobId, "stale-worker", "Late.Result"));
		assertThat(overwritten).isFalse();
		assertThat(row(uploadId)).containsEntry("blob_state", "clean")
			.containsEntry("scan_state", "clean")
			.containsEntry("upload_state", "clean");
	}

	@Test
	void contentThatComesBackAfterGcPurgedItIsStoredAgainAndScannedAgain() throws Exception {
		byte[] notes = notes();
		ApiClient professor = instructor();
		long first = uploadId(upload(professor, notes));
		drain();
		professor.get("/api/uploads/" + first).andExpect(jsonPath("$.state").value("clean"));
		long blobId = number(row(first), "file_blob_id");
		String oldKey = (String) row(first).get("storage_key");
		professor.delete("/api/uploads/" + first).andExpect(status().isNoContent());
		age(blobId);
		gc();
		assertThat(blob(blobId)).containsEntry("purged", 1L);

		// 지우기 전에 clean이었어도 새로 받은 객체는 검사를 다시 받는다(ADR-0011, ADR-0018).
		long again = uploadId(upload(professor, notes).andExpect(jsonPath("$.state").value("quarantined")));
		Map<String, Object> revived = row(again);
		assertThat(number(revived, "file_blob_id")).isEqualTo(blobId);
		assertThat(revived).containsEntry("blob_state", "quarantined")
			.containsEntry("scan_state", "pending")
			.containsEntry("purged_at", null);
		assertThat(number(revived, "scan_attempt_count")).isZero();
		assertThat((String) revived.get("storage_key")).startsWith(oldKey + "-");
		attach(professor, again).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("attachment_not_ready"));

		drain();

		professor.get("/api/uploads/" + again).andExpect(jsonPath("$.state").value("clean"));
		assertThat(clamd.scansOf(notes)).isEqualTo(2);
		attach(professor, again).andExpect(status().isCreated());
	}

	@Test
	void gcLeavesContentAloneWhileItIsAwaitedOrUnderScanAndALateVerdictCannotRevivePurgedContent() throws Exception {
		byte[] notes = notes();
		ApiClient first = instructor();
		ApiClient second = instructor();
		long a = uploadId(upload(first, notes));
		long waiting = uploadId(upload(second, notes));
		long blobId = number(row(a), "file_blob_id");
		String key = (String) row(a).get("storage_key");

		// 같은 내용을 기다리는 업로드가 남아 있으면 지우지 않는다.
		first.delete("/api/uploads/" + a).andExpect(status().isNoContent());
		age(blobId);
		gc();
		assertThat(blob(blobId)).containsEntry("purged", 0L).containsEntry("reference_count", 1L);

		// 참조가 0이 돼도 검사 worker가 lease를 잡고 검사하는 동안에는 지우지 않는다.
		lease(blobId, "other-worker", "DATE_ADD(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE)");
		second.delete("/api/uploads/" + waiting).andExpect(status().isNoContent());
		age(blobId);
		gc();
		assertThat(blob(blobId)).containsEntry("purged", 0L).containsEntry("reference_count", 0L);
		assertThat(this.store.get(Bucket.CONTENT, key)).isPresent();

		// lease가 끝나면 지운다. 그 뒤 늦게 온 판정은 반영하지 않고, 검사 worker는 지운 내용을 잡지 않는다.
		lease(blobId, "other-worker", "DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)");
		age(blobId);
		gc();
		assertThat(blob(blobId)).containsEntry("purged", 1L).containsEntry("blob_state", "quarantined");
		assertThat(this.store.get(Bucket.CONTENT, key)).isEmpty();
		Boolean lateClean = this.transaction.execute((tx) -> this.records.markClean(blobId, "other-worker"));
		Boolean lateInfected = this.transaction
			.execute((tx) -> this.records.markInfected(blobId, "other-worker", "Late.Result"));
		assertThat(lateClean).isFalse();
		assertThat(lateInfected).isFalse();
		drain();
		assertThat(clamd.scansOf(notes)).isZero();
		assertThat(blob(blobId)).containsEntry("blob_state", "quarantined").containsEntry("purged", 1L);
	}

	/**
	 * 강의 영상은 검사를 면제한다(ADR-0016 12번). 면제한 내용은 unscanned이고, 같은 바이트를 다른 용도로 올리면 그 내용은 검사를
	 * 받는다. unscanned인 내용이 다른 용도의 업로드를 clean으로 만들지 않는다.
	 */
	@Test
	void exemptsLectureVideosButUnscannedContentNeverMakesAnotherPurposeClean() throws Exception {
		long professorId = instructorId();
		ApiClient professor = new ApiClient(this.mvc, this.data.session(professorId));
		byte[] content = videoAndText("9주차 노트 " + UUID.randomUUID());

		long video = TestVideos.upload(this.mvc, this.data.session(professorId), this.course, "lecture.mkv", content);
		assertThat(row(video)).containsEntry("upload_state", "clean")
			.containsEntry("scan_state", "clean")
			.containsEntry("blob_state", "unscanned");
		drain();
		assertThat(clamd.scansOf(content)).isZero();

		long material = uploadId(professor.upload(this.course, "lecture_material", "notes.txt", content, null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.state").value("quarantined")));
		assertThat(row(material)).containsEntry("blob_state", "quarantined").containsEntry("scan_state", "pending");
		assertThat(number(row(material), "file_blob_id")).isEqualTo(number(row(video), "file_blob_id"));
		attach(professor, material).andExpect(status().isConflict());

		drain();

		assertThat(clamd.scansOf(content)).isEqualTo(1);
		assertThat(row(material)).containsEntry("upload_state", "clean").containsEntry("blob_state", "clean");
		attach(professor, material).andExpect(status().isCreated());
		assertThat(row(video)).containsEntry("upload_state", "clean").containsEntry("scan_state", "clean");
	}

	@Test
	void anInfectedVerdictOnContentSharedWithAVideoRejectsTheVideoAndWithdrawsItsLecture() throws Exception {
		VideoRunnerDriver.drain(this.context);
		long professorId = instructorId();
		ApiClient professor = new ApiClient(this.mvc, this.data.session(professorId));
		byte[] content = videoAndText("첨부 " + UUID.randomUUID() + "\n" + FakeClamd.EICAR);
		long video = TestVideos.upload(this.mvc, this.data.session(professorId), this.course, "lecture.mkv", content);
		long itemId = ApiClient.json(professor
			.postJson("/api/courses/" + this.course + "/lectures",
					"{\"weekNo\":3,\"title\":\"3주차\",\"items\":[{\"title\":\"강의\",\"uploadId\":" + video + "}]}")
			.andExpect(status().isCreated())).get("items").get(0).get("itemId").asLong();

		professor.upload(this.course, "lecture_material", "notes.txt", content, null).andExpect(status().isCreated());
		drain();

		assertThat(row(video)).containsEntry("scan_state", "infected").containsEntry("blob_state", "rejected");
		VideoRunnerDriver.withdrawRemovedFiles(this.context);
		assertThat(this.jdbc.sql("""
				SELECT j.job_state FROM video_transcode_jobs j
				  JOIN online_lecture_items i ON i.video_asset_id = j.video_asset_id WHERE i.online_lecture_item_id = :i
				""").param("i", itemId).query(String.class).single()).isEqualTo("cancelled");
		assertThat(this.jdbc.sql("SELECT video_state FROM online_lecture_items WHERE online_lecture_item_id = :i")
			.param("i", itemId)
			.query(String.class)
			.single()).isEqualTo("removed");
		assertThat(VideoRunnerDriver.runOnce(this.context)).isFalse();
	}

	private long instructorId() {
		long id = this.data.user("PROFESSOR");
		this.data.instructor(this.course, id);
		return id;
	}

	// Matroska EBML header(DocType matroska) 뒤에 텍스트를 붙인다. NUL byte가 없어 mkv와 txt의 형식 검사를 모두 통과한다.
	private static byte[] videoAndText(String text) {
		byte[] header = { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42, (byte) 0x86, (byte) 0x81, 0x01, 0x42,
				(byte) 0xF7, (byte) 0x81, 0x01, 0x42, (byte) 0xF2, (byte) 0x81, 0x04, 0x42, (byte) 0xF3, (byte) 0x81,
				0x08, 0x42, (byte) 0x82, (byte) 0x88, 'm', 'a', 't', 'r', 'o', 's', 'k', 'a', 0x42, (byte) 0x87,
				(byte) 0x81, 0x04, 0x42, (byte) 0x85, (byte) 0x81, 0x02, '\n' };
		byte[] body = text.getBytes(StandardCharsets.UTF_8);
		byte[] content = java.util.Arrays.copyOf(header, header.length + body.length);
		System.arraycopy(body, 0, content, header.length, body.length);
		return content;
	}

	private void gc() {
		StorageGcDriver.drain(this.context);
	}

	// 참조 수가 0이 된 지 오래된 것처럼 만든다. 명시한 updated_at은 ON UPDATE로 덮어쓰지 않는다.
	private void age(long blobId) {
		this.jdbc.sql("UPDATE file_blobs SET updated_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE file_blob_id = :id")
			.param("id", blobId)
			.update();
	}

	private Map<String, Object> blob(long blobId) {
		return this.jdbc.sql("""
				SELECT reference_count, blob_state, CAST(purged_at IS NOT NULL AS SIGNED) AS purged FROM file_blobs
				 WHERE file_blob_id = :id
				""").param("id", blobId).query().singleRow();
	}

	@Test
	void studyRoomRecordingsWaitForTheScannerAndInfectedOnesNeverPlay() throws Exception {
		byte[] clean = ("OggS clean recording " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
		byte[] infected = ("OggS " + FakeClamd.EICAR + UUID.randomUUID()).getBytes(StandardCharsets.ISO_8859_1);
		long ok = ApiClient.json(recording(clean, status().isCreated()).andExpect(jsonPath("$.state").value("scanning")))
			.get("id")
			.asLong();
		long bad = ApiClient.json(recording(infected, status().isCreated()).andExpect(jsonPath("$.state").value("scanning")))
			.get("id")
			.asLong();
		this.student.get("/api/me/study-room/recordings/" + ok + "/content")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_ready"));

		drain();

		this.student.get("/api/me/study-room/recordings/" + ok).andExpect(jsonPath("$.state").value("ready"));
		this.student.get("/api/me/study-room/recordings/" + ok + "/content")
			.andExpect(status().isOk())
			.andExpect(content().bytes(clean));
		this.student.get("/api/me/study-room/recordings/" + bad).andExpect(jsonPath("$.state").value("rejected"));
		this.student.get("/api/me/study-room/recordings/" + bad + "/content")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("content_rejected"));
		// 거부한 내용은 다시 올려도 저장하지 않는다.
		recording(infected, status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("content_rejected"));
	}

	@Test
	void aScanVerdictArrivingWhileARecordingIsDeletedLeavesConsistentState() throws Exception {
		byte[] content = ("OggS deleted during scan " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
		long id = ApiClient.json(recording(content, status().isCreated())).get("id").asLong();
		long fileId = this.jdbc.sql("SELECT file_id FROM study_room_recordings WHERE study_room_recording_id = :id")
			.param("id", id)
			.query(Long.class)
			.single();
		clamd.slow(java.time.Duration.ofMillis(300));
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch start = new CountDownLatch(1);
			Future<?> scan = pool.submit(() -> {
				start.await();
				drain();
				return null;
			});
			Future<Integer> delete = pool.submit(() -> {
				start.await();
				return this.student
					.send(MockMvcRequestBuilders.delete("/api/me/study-room/recordings/" + id).header("If-Match", "\"1\""))
					.andReturn()
					.getResponse()
					.getStatus();
			});
			start.countDown();
			scan.get(30, TimeUnit.SECONDS);
			assertThat(delete.get(30, TimeUnit.SECONDS)).isEqualTo(204);
		}
		finally {
			pool.shutdownNow();
		}
		// 판정은 내용(blob)에 남고, 지운 녹음과 파일은 되살아나지 않으며 사용량은 0이다.
		Map<String, Object> file = this.jdbc.sql("""
				SELECT f.state, f.original_file_name, b.blob_state, b.reference_count
				  FROM files f JOIN file_blobs b ON b.file_blob_id = f.file_blob_id WHERE f.file_id = :id
				""").param("id", fileId).query().singleRow();
		assertThat(file).containsEntry("state", "deleted").containsEntry("original_file_name", "recording.ogg");
		assertThat(number(file, "reference_count")).isZero();
		assertThat(file.get("blob_state")).isIn("clean", "quarantined");
		this.student.get("/api/me/study-room/recordings/" + id).andExpect(status().isNotFound());
		assertThat(ApiClient.json(this.student.get("/api/me/study-room/recordings")).get("usage").get("usedBytes").asLong())
			.isZero();
	}

	private ResultActions recording(byte[] content, ResultMatcher expected) throws Exception {
		return this.student
			.send(MockMvcRequestBuilders.multipart("/api/me/study-room/courses/" + this.course + "/recordings")
				.file(new MockMultipartFile("file", "lecture.ogg", "audio/ogg", content))
				.param("title", "녹음")
				.param("source", "audio")
				.param("durationMs", "1000")
				.param("recordedAt", "2026-11-12T05:00:00Z"))
			.andExpect(expected);
	}

	private ApiClient instructor() {
		long id = this.data.user("PROFESSOR");
		this.data.instructor(this.course, id);
		return new ApiClient(this.mvc, this.data.session(id));
	}

	private ResultActions upload(ApiClient client, byte[] content) throws Exception {
		return client.upload(this.course, "lecture_material", "notes.txt", content, null).andExpect(status().isCreated());
	}

	private static long uploadId(ResultActions result) throws Exception {
		return ApiClient.json(result).get("uploadId").asLong();
	}

	private ResultActions attach(ApiClient client, long uploadId) throws Exception {
		return client.postJson("/api/courses/" + this.course + "/materials",
				"{\"weekNo\":9,\"title\":\"9주차 자료\",\"uploadIds\":[" + uploadId + "]}");
	}

	private String contentPath(long fileId) {
		return "/api/courses/" + this.course + "/files/" + fileId + "/content";
	}

	private void drain() {
		while (this.worker.runOnce() > 0) {
			// 지금 검사할 수 있는 내용을 모두 처리한다.
		}
	}

	private long fileOf(long uploadId) {
		return number(row(uploadId), "file_id");
	}

	private Map<String, Object> row(long uploadId) {
		return this.jdbc.sql("""
				SELECT u.upload_state, u.rejection_code, f.file_id, f.scan_state, b.file_blob_id, b.blob_state,
				       b.scan_attempt_count, b.scan_signature, b.storage_key, b.purged_at,
				       TIMESTAMPDIFF(SECOND, CURRENT_TIMESTAMP(3), b.scan_next_at) AS retry_in
				  FROM upload_sessions u JOIN files f ON f.file_id = u.file_id JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE u.upload_id = :id
				""").param("id", uploadId).query().singleRow();
	}

	// 재시도 시각이 지난 것으로 만든다.
	private void makeDue(long uploadId) {
		this.jdbc.sql("""
				UPDATE file_blobs SET scan_next_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
				 WHERE file_blob_id = :id
				""").param("id", number(row(uploadId), "file_blob_id")).update();
	}

	private void lease(long blobId, String owner, String expiresSql) {
		this.jdbc.sql("UPDATE file_blobs SET scan_lease_owner = :owner, scan_lease_expires_at = " + expiresSql
				+ " WHERE file_blob_id = :id").param("owner", owner).param("id", blobId).update();
	}

	private static long number(Map<String, Object> row, String column) {
		return ((Number) row.get(column)).longValue();
	}

	private static byte[] notes() {
		return ("9주차 정규화 노트 " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
	}

	private static byte[] infected() {
		return ("첨부 " + UUID.randomUUID() + "\n" + FakeClamd.EICAR).getBytes(StandardCharsets.UTF_8);
	}

}
