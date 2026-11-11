package com.stackcorp.astra.files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.FileDerivatives;
import com.stackcorp.astra.files.api.RenditionException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

/**
 * worker의 저장소 GC. 만료 업로드, 지운 파일의 파생물, 참조 없는 원본 객체를 정리한다. 여러 번 돌려도 결과가 같고,
 * 같은 내용을 다시 올리는 업로드와 엇갈려도 살아 있는 파일의 객체를 지우지 않는다.
 */
class StorageGcTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Autowired
	S3Client s3;

	@Autowired
	TestBuckets buckets;

	@Autowired
	DocumentRenditions renditions;

	@Autowired
	FileDerivatives derivatives;

	long course;

	ApiClient professor;

	ApiClient student;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, stu);
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.student = new ApiClient(this.mvc, this.data.session(stu));
	}

	@Test
	void expiresUnattachedUploadsOnceAndReleasesOnlyTheirReference() throws Exception {
		byte[] shared = unique(1);
		long expiring = this.professor.uploaded(this.course, "old.pdf", shared);
		long sibling = this.professor.uploaded(this.course, "same.pdf", shared);
		long attached = this.professor.uploaded(this.course, "kept.pdf", unique(1));
		long material = create(attached);
		assertThat(referenceCountOfUpload(expiring)).isEqualTo(2);
		this.jdbc.sql("UPDATE upload_sessions SET expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 MINUTE) WHERE upload_id IN (:ids)")
			.param("ids", List.of(expiring, attached))
			.update();

		drain();
		drain();

		this.professor.get("/api/uploads/" + expiring).andExpect(jsonPath("$.state").value("expired"));
		assertThat(fileStateOfUpload(expiring)).isEqualTo("deleted");
		// 같은 내용을 쓰는 다른 업로드가 남아 있어 참조 수는 한 번만 줄어든다.
		assertThat(referenceCountOfUpload(sibling)).isOne();
		this.professor.get("/api/uploads/" + sibling).andExpect(jsonPath("$.state").value("clean"));
		// 첨부한 업로드는 만료와 관계없다.
		this.professor.get("/api/uploads/" + attached).andExpect(jsonPath("$.state").value("attached"));
		this.student.get("/api/courses/" + this.course + "/activities/" + material).andExpect(status().isOk());
		this.professor.postJson(materials(), "{\"weekNo\":2,\"title\":\"만료\",\"uploadIds\":[" + expiring + "]}")
			.andExpect(status().isConflict());
	}

	@Test
	void purgesDerivativesAndRenditionsOfDeletedFilesAfterTheGracePeriod() throws Exception {
		long upload = this.professor.uploaded(this.course, "week3.pdf", unique(2));
		long material = create(upload);
		long fileId = fileOfUpload(upload);
		String key = this.renditions.prepare(fileId).key();
		this.derivatives.put(fileId, "summary/jobs/1/result.json", "{}".getBytes(), "application/json");
		String blobKey = blobKeyOfUpload(upload);
		this.professor
			.send(MockMvcRequestBuilders.delete("/api/courses/" + this.course + "/materials/" + material).header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());

		drain();
		// 지운 직후에는 그 전에 시작한 쓰기가 끝나기를 기다린다. 읽기는 이미 막혀 있다.
		assertThat(exists(this.buckets.delivery(), "doc/" + key + "/")).isTrue();
		assertThat(derivativesPurged(fileId)).isFalse();

		this.jdbc.sql("UPDATE files SET deleted_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 HOUR) WHERE file_id = :id")
			.param("id", fileId)
			.update();
		drain();
		drain();

		assertThat(exists(this.buckets.delivery(), "doc/" + key + "/")).isFalse();
		assertThat(exists(this.buckets.content(), "renditions/" + key + "/")).isFalse();
		assertThat(exists(this.buckets.content(), "derivatives/" + fileId + "/")).isFalse();
		assertThat(derivativesPurged(fileId)).isTrue();
		assertThat(this.jdbc.sql("SELECT DISTINCT conversion_state FROM conversion_jobs WHERE file_id = :id")
			.param("id", fileId)
			.query(String.class)
			.list()).containsExactly("expired");
		// 원본 객체는 참조가 0이 된 뒤의 유예 시간이 지나야 지운다.
		assertThat(exists(this.buckets.content(), blobKey)).isTrue();
		// 지운 파일에는 파생물도 rendition도 다시 생기지 않는다.
		assertThatIllegalStateException()
			.isThrownBy(() -> this.derivatives.put(fileId, "summary/jobs/2/result.json", "{}".getBytes(), "application/json"));
		assertThat(catchThrowableOfType(RenditionException.class, () -> this.renditions.prepare(fileId)).code())
			.isEqualTo(RenditionException.Code.NOT_FOUND);
		assertThat(this.renditions.readyKey(fileId, 1)).isEmpty();
	}

	@Test
	void purgesUnreferencedBlobAndStoresTheSameContentUnderAFreshKeyWhenItComesBack() throws Exception {
		byte[] pdf = unique(1);
		long first = this.professor.uploaded(this.course, "a.pdf", pdf);
		String oldKey = blobKeyOfUpload(first);
		long blobId = blobOfUpload(first);
		this.professor.delete("/api/uploads/" + first).andExpect(status().isNoContent());

		drain();
		assertThat(exists(this.buckets.content(), oldKey)).isTrue();

		age(blobId);
		drain();
		drain();

		assertThat(exists(this.buckets.content(), oldKey)).isFalse();
		assertThat(blob(blobId)).containsEntry("reference_count", 0L).containsEntry("purged", 1L);

		long again = this.professor.uploaded(this.course, "a-again.pdf", pdf);
		long material = create(again);

		Map<String, Object> revived = blob(blobId);
		assertThat(revived).containsEntry("reference_count", 1L).containsEntry("purged", 0L);
		assertThat((String) revived.get("storage_key")).startsWith(oldKey + "-");
		assertThat(blobOfUpload(again)).isEqualTo(blobId);
		JsonNode detail = ApiClient.json(this.student.get("/api/courses/" + this.course + "/activities/" + material));
		this.student.get("/api/courses/" + this.course + "/files/" + detail.get("files").get(0).get("fileId").asLong() + "/content")
			.andExpect(status().isOk())
			.andExpect(content().bytes(pdf));
		age(blobId);
		drain();
		assertThat(exists(this.buckets.content(), (String) revived.get("storage_key"))).isTrue();
	}

	@Test
	void keepsABlobThatTheSameContentReferencedAgainBeforeGcRan() throws Exception {
		byte[] pdf = unique(1);
		long first = this.professor.uploaded(this.course, "a.pdf", pdf);
		long blobId = blobOfUpload(first);
		String key = blobKeyOfUpload(first);
		this.professor.delete("/api/uploads/" + first).andExpect(status().isNoContent());
		age(blobId);

		long again = this.professor.uploaded(this.course, "a-again.pdf", pdf);
		age(blobId);
		drain();

		assertThat(blob(blobId)).containsEntry("reference_count", 1L).containsEntry("purged", 0L).containsEntry("storage_key", key);
		assertThat(exists(this.buckets.content(), key)).isTrue();
		long material = create(again);
		JsonNode detail = ApiClient.json(this.student.get("/api/courses/" + this.course + "/activities/" + material));
		this.student.get("/api/courses/" + this.course + "/files/" + detail.get("files").get(0).get("fileId").asLong() + "/content")
			.andExpect(status().isOk())
			.andExpect(content().bytes(pdf));
	}

	private void drain() {
		StorageGcDriver.drain(this.context);
	}

	private long create(long uploadId) throws Exception {
		return ApiClient.json(this.professor
			.postJson(materials(), "{\"weekNo\":1,\"title\":\"자료\",\"uploadIds\":[" + uploadId + "]}")
			.andExpect(status().isCreated())).get("activityId").asLong();
	}

	// 참조 수가 0이 된 지 오래된 것처럼 만든다. 명시한 updated_at은 ON UPDATE로 덮어쓰지 않는다.
	private void age(long blobId) {
		this.jdbc.sql("UPDATE file_blobs SET updated_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE file_blob_id = :id")
			.param("id", blobId)
			.update();
	}

	private Map<String, Object> blob(long blobId) {
		return this.jdbc.sql("""
				SELECT reference_count, CAST(purged_at IS NOT NULL AS SIGNED) AS purged, storage_key FROM file_blobs
				 WHERE file_blob_id = :id
				""").param("id", blobId).query().singleRow();
	}

	private boolean derivativesPurged(long fileId) {
		return this.jdbc.sql("SELECT derivatives_purged_at IS NOT NULL FROM files WHERE file_id = :id")
			.param("id", fileId)
			.query(Boolean.class)
			.single();
	}

	private long fileOfUpload(long uploadId) {
		return this.jdbc.sql("SELECT file_id FROM upload_sessions WHERE upload_id = :id")
			.param("id", uploadId)
			.query(Long.class)
			.single();
	}

	private long blobOfUpload(long uploadId) {
		return this.jdbc.sql("""
				SELECT f.file_blob_id FROM upload_sessions u JOIN files f ON f.file_id = u.file_id WHERE u.upload_id = :id
				""").param("id", uploadId).query(Long.class).single();
	}

	private String blobKeyOfUpload(long uploadId) {
		return this.jdbc.sql("SELECT storage_key FROM file_blobs WHERE file_blob_id = :id")
			.param("id", blobOfUpload(uploadId))
			.query(String.class)
			.single();
	}

	private long referenceCountOfUpload(long uploadId) {
		return this.jdbc.sql("SELECT reference_count FROM file_blobs WHERE file_blob_id = :id")
			.param("id", blobOfUpload(uploadId))
			.query(Long.class)
			.single();
	}

	private String fileStateOfUpload(long uploadId) {
		return this.jdbc.sql("SELECT state FROM files WHERE file_id = :id")
			.param("id", fileOfUpload(uploadId))
			.query(String.class)
			.single();
	}

	private boolean exists(String bucket, String prefix) {
		return !this.s3.listObjectsV2((b) -> b.bucket(bucket).prefix(prefix)).contents().isEmpty();
	}

	private String materials() {
		return "/api/courses/" + this.course + "/materials";
	}

	private static byte[] unique(int pages) {
		return TestPdfs.pdf(pages, null, UUID.randomUUID().toString());
	}

}
