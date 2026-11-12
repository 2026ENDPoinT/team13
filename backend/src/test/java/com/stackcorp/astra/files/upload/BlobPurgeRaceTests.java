package com.stackcorp.astra.files.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.files.StorageGcDriver;
import com.stackcorp.astra.files.upload.UploadRecords.Blob;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * 같은 내용의 업로드가 "살아 있는 blob이니 저장하지 않는다"고 판단한 뒤, 참조 수에 반영하기 전에 GC가 그 blob을 지운 경우.
 * 업로드는 지운 blob을 옛 객체로 되살리지 않고, 새 key에 저장한 뒤에만 되살린다.
 */
class BlobPurgeRaceTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Autowired
	UploadRecords records;

	@Autowired
	TransactionTemplate transaction;

	@Autowired
	S3Client s3;

	@Autowired
	TestBuckets buckets;

	@Test
	void uploadThatSawALiveBlobDoesNotReviveItAfterGcPurgedIt() throws Exception {
		Purged purged = purgedBlobAfterAnUploadSawItLive();
		String sha = purged.sha();
		byte[] pdf = purged.content();

		Optional<Blob> withoutContent = this.transaction
			.execute((tx) -> this.records.referenceBlob(sha, pdf.length, "application/pdf", null, "clean"));
		Optional<Blob> withOldKey = this.transaction
			.execute((tx) -> this.records.referenceBlob(sha, pdf.length, "application/pdf", purged.oldKey(), "clean"));

		assertThat(withoutContent).isEmpty();
		assertThat(withOldKey).isEmpty();
		assertThat(blob(sha)).containsEntry("reference_count", 0L).containsEntry("purged", 1L);

		String freshKey = freshObject(purged);
		Optional<Blob> revived = this.transaction
			.execute((tx) -> this.records.referenceBlob(sha, pdf.length, "application/pdf", freshKey, "clean"));

		assertThat(revived).hasValueSatisfying((b) -> assertThat(b.storageKey()).isEqualTo(freshKey));
		assertThat(blob(sha)).containsEntry("reference_count", 1L)
			.containsEntry("purged", 0L)
			.containsEntry("storage_key", freshKey);
	}

	@Test
	void revivedBlobStartsOverInTheScannersInitialStateInsteadOfInheritingClean() throws Exception {
		Purged purged = purgedBlobAfterAnUploadSawItLive();
		this.jdbc.sql("""
				UPDATE file_blobs SET scan_attempt_count = 2, scanned_at = CURRENT_TIMESTAMP(3), scan_lease_owner = 'gone'
				 WHERE content_sha256 = :sha
				""").param("sha", purged.sha()).update();
		String freshKey = freshObject(purged);

		// 검사기가 있는 배포(ScanMode.initialBlobState = quarantined)에서 같은 내용이 다시 왔다.
		Optional<Blob> revived = this.transaction.execute((tx) -> this.records.referenceBlob(purged.sha(),
				purged.content().length, "application/pdf", freshKey, "quarantined"));

		assertThat(revived).hasValueSatisfying((b) -> assertThat(b.state()).isEqualTo("quarantined"));
		assertThat(blob(purged.sha())).containsEntry("blob_state", "quarantined")
			.containsEntry("scan_attempt_count", 0L)
			.containsEntry("scanned", 0L)
			.containsEntry("scan_lease_owner", null)
			.containsEntry("purged", 0L)
			.containsEntry("storage_key", freshKey);
	}

	@Test
	void rejectedContentIsNeverRevivedEvenAfterItsObjectWasPurged() throws Exception {
		Purged purged = purgedBlobAfterAnUploadSawItLive();
		this.jdbc.sql("UPDATE file_blobs SET blob_state = 'rejected', scan_signature = 'Eicar-Test' WHERE content_sha256 = :sha")
			.param("sha", purged.sha())
			.update();
		String freshKey = freshObject(purged);

		Optional<Blob> referenced = this.transaction.execute((tx) -> this.records.referenceBlob(purged.sha(),
				purged.content().length, "application/pdf", freshKey, "quarantined"));

		// 거부한 상태를 그대로 돌려주고 아무것도 바꾸지 않는다. 부르는 쪽이 422로 거부한다.
		assertThat(referenced).hasValueSatisfying((b) -> assertThat(b.state()).isEqualTo("rejected"));
		assertThat(blob(purged.sha())).containsEntry("blob_state", "rejected")
			.containsEntry("reference_count", 0L)
			.containsEntry("purged", 1L)
			.containsEntry("storage_key", purged.oldKey());
		this.s3.deleteObject((b) -> b.bucket(this.buckets.content()).key(freshKey));

		purged.professor()
			.upload(purged.course(), "lecture_material", "again.pdf", purged.content(), null)
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("content_rejected"));
		// 거부한 내용은 저장하지 않는다.
		assertThat(this.s3.listObjectsV2((b) -> b.bucket(this.buckets.content()).prefix(purged.oldKey())).contents())
			.isEmpty();
	}

	/**
	 * 첫 업로드를 지워 참조를 0으로 만든다. 두 번째 업로드가 blob을 조회해 살아 있으니 저장하지 않기로 한 뒤, 그 사이 GC가 blob을
	 * 지운 상태를 만든다.
	 */
	private Purged purgedBlobAfterAnUploadSawItLive() throws Exception {
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		byte[] pdf = TestPdfs.pdf(1, null, UUID.randomUUID().toString());
		long upload = professor.uploaded(course, "a.pdf", pdf);
		Map<String, Object> row = this.jdbc.sql("""
				SELECT b.content_sha256, b.storage_key FROM upload_sessions u
				  JOIN files f ON f.file_id = u.file_id JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE u.upload_id = :id
				""").param("id", upload).query().singleRow();
		String sha = (String) row.get("content_sha256");
		String oldKey = (String) row.get("storage_key");
		professor.delete("/api/uploads/" + upload).andExpect(status().isNoContent());
		this.jdbc.sql("UPDATE file_blobs SET updated_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE content_sha256 = :sha")
			.param("sha", sha)
			.update();

		assertThat(this.records.blob(sha)).hasValueSatisfying((b) -> assertThat(b.purged()).isFalse());
		StorageGcDriver.drain(this.context);
		assertThat(this.s3.listObjectsV2((b) -> b.bucket(this.buckets.content()).prefix(oldKey)).contents()).isEmpty();
		return new Purged(course, professor, pdf, sha, oldKey);
	}

	private String freshObject(Purged purged) {
		String freshKey = purged.oldKey() + "-" + UUID.randomUUID().toString().replace("-", "");
		this.s3.putObject((b) -> b.bucket(this.buckets.content()).key(freshKey), RequestBody.fromBytes(purged.content()));
		return freshKey;
	}

	private Map<String, Object> blob(String sha) {
		return this.jdbc.sql("""
				SELECT reference_count, CAST(purged_at IS NOT NULL AS SIGNED) AS purged, storage_key, blob_state,
				       scan_attempt_count, CAST(scanned_at IS NOT NULL AS SIGNED) AS scanned, scan_lease_owner
				  FROM file_blobs WHERE content_sha256 = :sha
				""").param("sha", sha).query().singleRow();
	}

	private record Purged(long course, ApiClient professor, byte[] content, String sha, String oldKey) {
	}

}
