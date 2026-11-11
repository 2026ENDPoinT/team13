package com.stackcorp.astra.files.upload;

import static org.assertj.core.api.Assertions.assertThat;
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
		long course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		this.data.instructor(course, prof);
		ApiClient professor = new ApiClient(this.mvc, this.data.session(prof));
		byte[] pdf = TestPdfs.pdf(1, null, UUID.randomUUID().toString());
		long upload = professor.uploaded(course, "a.pdf", pdf);
		Map<String, Object> row = this.jdbc.sql("""
				SELECT b.file_blob_id, b.content_sha256, b.storage_key, b.size_bytes FROM upload_sessions u
				  JOIN files f ON f.file_id = u.file_id JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE u.upload_id = :id
				""").param("id", upload).query().singleRow();
		String sha = (String) row.get("content_sha256");
		String oldKey = (String) row.get("storage_key");
		professor.delete("/api/uploads/" + upload).andExpect(status().isNoContent());
		this.jdbc.sql("UPDATE file_blobs SET updated_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE content_sha256 = :sha")
			.param("sha", sha)
			.update();

		// 두 번째 업로드가 blob을 조회한다. 살아 있으므로 내용을 저장하지 않기로 한다.
		assertThat(this.records.blob(sha)).hasValueSatisfying((b) -> assertThat(b.purged()).isFalse());
		// 그 사이 GC가 blob을 지운다.
		StorageGcDriver.drain(this.context);
		assertThat(this.s3.listObjectsV2((b) -> b.bucket(this.buckets.content()).prefix(oldKey)).contents()).isEmpty();

		Optional<Blob> withoutContent = this.transaction
			.execute((tx) -> this.records.referenceBlob(sha, pdf.length, "application/pdf", null));
		Optional<Blob> withOldKey = this.transaction
			.execute((tx) -> this.records.referenceBlob(sha, pdf.length, "application/pdf", oldKey));

		assertThat(withoutContent).isEmpty();
		assertThat(withOldKey).isEmpty();
		assertThat(blob(sha)).containsEntry("reference_count", 0L).containsEntry("purged", 1L);

		String freshKey = oldKey + "-" + UUID.randomUUID().toString().replace("-", "");
		this.s3.putObject((b) -> b.bucket(this.buckets.content()).key(freshKey), RequestBody.fromBytes(pdf));
		Optional<Blob> revived = this.transaction
			.execute((tx) -> this.records.referenceBlob(sha, pdf.length, "application/pdf", freshKey));

		assertThat(revived).hasValueSatisfying((b) -> assertThat(b.storageKey()).isEqualTo(freshKey));
		assertThat(blob(sha)).containsEntry("reference_count", 1L)
			.containsEntry("purged", 0L)
			.containsEntry("storage_key", freshKey);
	}

	private Map<String, Object> blob(String sha) {
		return this.jdbc.sql("""
				SELECT reference_count, CAST(purged_at IS NOT NULL AS SIGNED) AS purged, storage_key FROM file_blobs
				 WHERE content_sha256 = :sha
				""").param("sha", sha).query().singleRow();
	}

}
