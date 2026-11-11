package com.stackcorp.astra.files.filebox;

import static com.stackcorp.astra.files.filebox.FileboxCalls.command;
import static com.stackcorp.astra.files.filebox.FileboxCalls.file;
import static com.stackcorp.astra.files.filebox.FileboxCalls.folder;
import static com.stackcorp.astra.files.filebox.FileboxCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.files.filebox.ZipJobRecords.Claimed;
import com.stackcorp.astra.files.storage.ObjectStore;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;

/**
 * 파일함 ZIP 일괄 다운로드(FILES-08). 요청은 소유·상태·한도를 검사해 작업만 남기고(202), worker가 저장소에서 저장소로 흘려 만든다.
 * worker가 둘이어도 작업은 한 번만 만들어지고, 생성물은 요청한 사람만 TTL 동안 내려받는다.
 */
class ZipJobTests extends IntegrationTest {

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
	ZipJobWorker worker;

	@Autowired
	ZipJobRecords records;

	@Autowired
	ObjectStore store;

	@Autowired
	PlatformTransactionManager transactionManager;

	@Autowired
	FileboxProperties properties;

	ApiClient owner;

	ApiClient other;

	@BeforeEach
	void users() {
		this.owner = user();
		this.other = user();
	}

	@Test
	void buildsASanitizedUtf8ArchiveThatOnlyTheRequesterCanDownload() throws Exception {
		byte[] evil = text();
		byte[] first = text();
		byte[] second = text();
		long a = file(this.owner, null, "a.txt", evil);
		long r1 = file(this.owner, null, "보고서.txt", first);
		long folder = folder(this.owner, null, "폴더");
		long r2 = file(this.owner, folder, "보고서.txt", second);
		this.jdbc.sql("UPDATE filebox_items SET item_name = '../../evil.txt' WHERE filebox_item_id = :id")
			.param("id", a)
			.update();

		JsonNode job = ApiClient.json(request(this.owner, "zip-1", a, r1, r2).andExpect(status().isAccepted())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.state").value("requested"))
			.andExpect(jsonPath("$.itemCount").value(3))
			.andExpect(jsonPath("$.totalBytes").value(evil.length + first.length + second.length)));
		long jobId = job.get("jobId").asLong();
		request(this.owner, "zip-1", a, r1, r2).andExpect(status().isAccepted()).andExpect(jsonPath("$.jobId").value(jobId));
		this.owner.get("/api/filebox/zip-jobs/" + jobId + "/download").andExpect(status().isConflict());

		drain();

		this.owner.get("/api/filebox/zip-jobs/" + jobId)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.state").value("ready"))
			.andExpect(jsonPath("$.sizeBytes").isNumber())
			.andExpect(jsonPath("$.expiresAt").isNotEmpty());
		this.other.get("/api/filebox/zip-jobs/" + jobId).andExpect(status().isNotFound());
		this.other.get("/api/filebox/zip-jobs/" + jobId + "/download").andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).get("/api/filebox/zip-jobs/" + jobId + "/download").andExpect(status().isUnauthorized());

		byte[] zip = this.owner.get("/api/filebox/zip-jobs/" + jobId + "/download")
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "application/zip"))
			.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		Map<String, byte[]> entries = unzip(zip);
		assertThat(entries.keySet()).containsExactly("_.._evil.txt", "보고서.txt", "보고서 (1).txt");
		assertThat(entries.get("_.._evil.txt")).isEqualTo(evil);
		assertThat(entries.get("보고서.txt")).isEqualTo(first);
		assertThat(entries.get("보고서 (1).txt")).isEqualTo(second);
		// 첫 local file header의 general purpose flag(offset 6, little endian)에서 bit 11(UTF-8 이름)이 켜져 있다.
		assertThat(zip[0]).isEqualTo((byte) 'P');
		assertThat(((zip[6] & 0xff) | ((zip[7] & 0xff) << 8)) & 0x0800).isEqualTo(0x0800);
		assertThat(this.jdbc.sql("SELECT result_storage_key FROM zip_jobs WHERE zip_job_id = :id")
			.param("id", jobId)
			.query(String.class)
			.single()).startsWith("zips/" + jobId + "/");
	}

	@Test
	void streamsLargeArchivesInPartsAndRejectsSelectionsOverTheSizeLimit() throws Exception {
		byte[] p1 = png(5 << 20);
		byte[] p2 = png(5 << 20);
		byte[] p3 = png(5 << 20);
		long i1 = file(this.owner, null, "1.png", p1);
		long i2 = file(this.owner, null, "2.png", p2);
		long i3 = file(this.owner, null, "3.png", p3);

		// 크기 합 상한(테스트 설정 12MB)을 넘으면 작업을 만들지 않는다.
		request(this.owner, "big", i1, i2, i3).andExpect(status().is(413)).andExpect(jsonPath("$.code").value("zip_too_large"));
		long jobId = ApiClient.json(request(this.owner, "ok", i1, i2).andExpect(status().isAccepted())).get("jobId").asLong();
		drain();

		JsonNode status = ApiClient.json(this.owner.get("/api/filebox/zip-jobs/" + jobId));
		assertThat(status.get("state").asString()).isEqualTo("ready");
		// 압축되지 않는 내용이라 생성물이 part 하나(8 MiB)보다 크다.
		assertThat(status.get("sizeBytes").asLong()).isGreaterThan(ObjectStore.PART_SIZE);
		Map<String, byte[]> entries = unzip(this.owner.get("/api/filebox/zip-jobs/" + jobId + "/download")
			.andReturn()
			.getResponse()
			.getContentAsByteArray());
		assertThat(entries.get("1.png")).isEqualTo(p1);
		assertThat(entries.get("2.png")).isEqualTo(p2);
	}

	@Test
	void validatesItemsAndLimitsConcurrentJobsPerUser() throws Exception {
		long x = file(this.owner, null, "x.txt", text());
		long y = file(this.owner, null, "y.txt", text());
		long z = file(this.owner, null, "z.txt", text());
		long folderId = folder(this.owner, null, "폴더");
		long trashed = file(this.owner, null, "t.txt", text());
		command(this.owner, "trash", trashed).andExpect(status().isOk());
		long theirs = file(this.other, null, "theirs.txt", text());

		this.owner.postJson("/api/filebox/zip-jobs", "{\"itemIds\":[" + x + "]}")
			.andExpect(status().isBadRequest());
		request(this.owner, "k-folder", folderId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("folder_not_allowed"));
		request(this.owner, "k-trash", x, trashed).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_active"));
		request(this.owner, "k-foreign", x, theirs).andExpect(status().isNotFound());
		request(this.owner, "k-dup", x, x).andExpect(status().isBadRequest());
		long[] many = new long[101];
		for (int i = 0; i < many.length; i++) {
			many[i] = x + i;
		}
		request(this.owner, "k-many", many).andExpect(status().isBadRequest());
		this.jdbc.sql("UPDATE files f JOIN filebox_items i ON i.file_id = f.file_id SET f.scan_state = 'pending' WHERE i.filebox_item_id = :id")
			.param("id", z)
			.update();
		request(this.owner, "k-scan", z).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_ready"));

		long first = jobId(request(this.owner, "k-1", x).andExpect(status().isAccepted()));
		long second = jobId(request(this.owner, "k-2", y).andExpect(status().isAccepted()));
		assertThat(second).isNotEqualTo(first);
		// 같은 항목 집합은 key가 달라도 진행 중인 작업을 돌려준다.
		assertThat(jobId(request(this.owner, "k-3", x).andExpect(status().isAccepted()))).isEqualTo(first);
		request(this.owner, "k-4", x, y).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("too_many_jobs"));
		request(this.owner, "k-1", y).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency_conflict"));
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM zip_jobs z JOIN zip_job_items i ON i.zip_job_id = z.zip_job_id WHERE i.filebox_item_id IN (:ids)")
			.param("ids", List.of(x, y))
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

	@Test
	void twoSimultaneousRequestsForTheSameItemsCreateOneJob() throws Exception {
		long x = file(this.owner, null, "x.txt", text());
		long y = file(this.owner, null, "y.txt", text());

		List<Long> ids = concurrently(2, (i) -> jobId(request(this.owner, "same-" + i, x, y).andExpect(status().isAccepted())));

		assertThat(ids.get(0)).isEqualTo(ids.get(1));
		assertThat(this.jdbc.sql("SELECT COUNT(DISTINCT zip_job_id) FROM zip_job_items WHERE filebox_item_id = :id")
			.param("id", x)
			.query(Integer.class)
			.single()).isOne();
	}

	@Test
	void twoWorkersBuildEachJobOnceAndALostLeaseNeverOverwritesTheResult() throws Exception {
		drain();
		ApiClient third = user();
		List<Long> jobs = new ArrayList<>();
		for (ApiClient client : List.of(this.owner, this.other, third)) {
			long item = file(client, null, "w.txt", text());
			jobs.add(jobId(request(client, "w-" + item, item).andExpect(status().isAccepted())));
		}
		ZipJobWorker second = new ZipJobWorker(this.records, this.store, this.transactionManager, this.properties,
				"test-worker-b");

		List<Integer> attempted = concurrently(2, (i) -> (i == 0 ? this.worker : second).runOnce());

		assertThat(attempted.get(0) + attempted.get(1)).isGreaterThanOrEqualTo(jobs.size());
		for (long job : jobs) {
			Map<String, Object> row = this.jdbc.sql("SELECT zip_state, attempt_count FROM zip_jobs WHERE zip_job_id = :id")
				.param("id", job)
				.query()
				.singleRow();
			assertThat(row).containsEntry("zip_state", "ready").containsEntry("attempt_count", 1L);
			assertThat(objects(job)).hasSize(1);
		}

		// lease가 끝난 작업은 다른 worker가 가져가고, 앞 worker의 늦은 결과는 버려진다.
		long item = file(this.owner, null, "late.txt", text());
		long job = jobId(request(this.owner, "late", item).andExpect(status().isAccepted()));
		Optional<Claimed> stale = this.worker.claim();
		assertThat(stale).map(Claimed::id).contains(job);
		assertThat(second.claim()).isEmpty();
		this.jdbc.sql("UPDATE zip_jobs SET lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE zip_job_id = :id")
			.param("id", job)
			.update();
		Claimed taken = second.claim().orElseThrow();
		assertThat(taken.id()).isEqualTo(job);
		assertThat(taken.attempt()).isEqualTo(2);
		second.build(taken);
		String key = resultKey(job);
		this.worker.build(stale.get());

		assertThat(resultKey(job)).isEqualTo(key);
		assertThat(objects(job)).extracting(S3Object::key).containsExactly(key);
		this.owner.get("/api/filebox/zip-jobs/" + job).andExpect(jsonPath("$.state").value("ready"));
	}

	@Test
	void failsWhenAnItemIsGoneAndRemovesArchivesAfterTheTtl() throws Exception {
		long kept = file(this.owner, null, "kept.txt", text());
		long gone = file(this.owner, null, "gone.txt", text());
		long failed = jobId(request(this.owner, "gone", kept, gone).andExpect(status().isAccepted()));
		long ready = jobId(request(this.owner, "kept", kept).andExpect(status().isAccepted()));
		command(this.owner, "trash", gone).andExpect(status().isOk());

		drain();

		this.owner.get("/api/filebox/zip-jobs/" + failed)
			.andExpect(jsonPath("$.state").value("failed"))
			.andExpect(jsonPath("$.failure").value("item_unavailable"));
		this.owner.get("/api/filebox/zip-jobs/" + failed + "/download").andExpect(status().isConflict());
		assertThat(objects(failed)).isEmpty();
		assertThat(objects(ready)).hasSize(1);

		this.jdbc.sql("UPDATE zip_jobs SET expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND) WHERE zip_job_id IN (:ids)")
			.param("ids", List.of(failed, ready))
			.update();
		this.owner.get("/api/filebox/zip-jobs/" + ready).andExpect(jsonPath("$.state").value("expired"));
		this.owner.get("/api/filebox/zip-jobs/" + ready + "/download").andExpect(status().isGone());

		while (this.worker.expireArchives() > 0) {
			// 다음 묶음
		}

		assertThat(objects(ready)).isEmpty();
		for (long job : List.of(failed, ready)) {
			assertThat(this.jdbc.sql("SELECT zip_state, purged_at IS NOT NULL AS purged FROM zip_jobs WHERE zip_job_id = :id")
				.param("id", job)
				.query()
				.singleRow()).containsEntry("zip_state", "expired").containsEntry("purged", 1L);
		}
		// 만료한 생성물은 같은 항목 집합의 새 요청이 다시 만든다.
		assertThat(jobId(request(this.owner, "kept-again", kept).andExpect(status().isAccepted()))).isNotEqualTo(ready);
	}

	private ResultActions request(ApiClient client, String key, long... itemIds) throws Exception {
		return client.send(MockMvcRequestBuilders.post("/api/filebox/zip-jobs")
			.header("Idempotency-Key", key)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"itemIds\":" + FileboxCalls.ids(itemIds) + "}"));
	}

	private static long jobId(ResultActions result) throws Exception {
		return ApiClient.json(result).get("jobId").asLong();
	}

	private void drain() {
		while (this.worker.runOnce() > 0) {
			// 다음 묶음
		}
	}

	private ApiClient user() {
		return new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	private List<S3Object> objects(long jobId) {
		return this.s3.listObjectsV2((b) -> b.bucket(this.buckets.content()).prefix(ZipJobWorker.prefix(jobId)))
			.contents();
	}

	private String resultKey(long jobId) {
		return this.jdbc.sql("SELECT result_storage_key FROM zip_jobs WHERE zip_job_id = :id")
			.param("id", jobId)
			.query(String.class)
			.single();
	}

	private static Map<String, byte[]> unzip(byte[] zip) throws Exception {
		Map<String, byte[]> entries = new LinkedHashMap<>();
		try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip), StandardCharsets.UTF_8)) {
			for (ZipEntry entry; (entry = in.getNextEntry()) != null;) {
				ByteArrayOutputStream out = new ByteArrayOutputStream();
				in.transferTo(out);
				entries.put(entry.getName(), out.toByteArray());
			}
		}
		return entries;
	}

	// PNG signature 뒤에 압축되지 않는 임의 byte.
	private static byte[] png(int size) {
		byte[] bytes = new byte[size];
		new Random().nextBytes(bytes);
		byte[] signature = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
		System.arraycopy(signature, 0, bytes, 0, signature.length);
		return bytes;
	}

	private static <T> List<T> concurrently(int threads, Indexed<T> work) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<Future<T>> runs = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				int index = i;
				runs.add(pool.submit((Callable<T>) () -> {
					start.await();
					return work.run(index);
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> run : runs) {
				results.add(run.get(120, TimeUnit.SECONDS));
			}
			return results;
		}
	}

	private interface Indexed<T> {

		T run(int index) throws Exception;

	}

}
