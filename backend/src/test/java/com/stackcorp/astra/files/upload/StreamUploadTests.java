package com.stackcorp.astra.files.upload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestVideos;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.files.StorageGcDriver;
import org.springframework.context.ApplicationContext;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.micrometer.core.instrument.MeterRegistry;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * 영상 업로드는 본문을 읽기 전에 권한, 용도, 이름, 선언한 크기를 판정하고, 본문은 디스크와 메모리에 쌓지 않고 content bucket으로
 * 흘려 보낸다. 실패하면 multipart part를 남기지 않는다(ADR-0016, SC-08).
 */
class StreamUploadTests extends IntegrationTest {

	private static final String PREFIX = "blobs/stream/";

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
	StreamUploadJanitor janitor;

	@Autowired
	ApplicationContext context;

	@Autowired
	MeterRegistry meters;

	long course;

	long student;

	byte[] clip;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		this.student = this.data.user("STUDENT");
		this.data.enroll(this.course, this.student);
		this.clip = TestVideos.clip(1, "mp4");
	}

	@Test
	void decidesAuthorizationAndDeclaredSizeBeforeReadingTheBody() throws Exception {
		long professor = professor();
		// 학생, 과목 밖 사용자, 다른 과목의 담당자
		assertUntouched(send(this.student, this.course, "lecture_video", "week1.mp4", this.clip, len(this.clip)), 404);
		assertUntouched(send(this.data.user("STUDENT"), this.course, "lecture_video", "week1.mp4", this.clip,
				len(this.clip)), 404);
		assertUntouched(send(professor, this.data.course(), "lecture_video", "week1.mp4", this.clip, len(this.clip)), 404);
		// 로그인하지 않은 요청, CSRF token이 없는 요청
		assertUntouched(this.mvc.perform(request(null, this.course, "lecture_video", "week1.mp4", this.clip,
				len(this.clip), true)).andReturn(), 401);
		assertUntouched(this.mvc.perform(request(professor, this.course, "lecture_video", "week1.mp4", this.clip,
				len(this.clip), false)).andReturn(), 403);
		// 정책 상한(2 GiB)을 넘는 선언, 크기를 알리지 않은 요청, 다른 용도, 허용하지 않은 확장자
		MvcResult oversized = send(professor, this.course, "lecture_video", "week1.mp4", this.clip, "3000000000");
		assertUntouched(oversized, 413);
		assertThat(ApiClient.json(oversized.getResponse()).get("code").asString()).isEqualTo("too_large");
		assertUntouched(send(professor, this.course, "lecture_video", "week1.mp4", this.clip, null), 411);
		assertUntouched(send(professor, this.course, "lecture_material", "week1.pdf", this.clip, len(this.clip)), 400);
		assertUntouched(send(professor, this.course, "lecture_video", "week1.avi", this.clip, len(this.clip)), 422);

		assertThat(uploadsOf(professor)).isZero();
		assertThat(uploadsOf(this.student)).isZero();
	}

	@Test
	void rejectsContainersWhoseSignatureDoesNotMatchBeforeStoringAnything() throws Exception {
		long professor = professor();
		int objects = streamObjects().size();
		byte[] mov = TestVideos.clip(1, "mov");
		byte[] mkv = TestVideos.clip(1, "mkv");
		for (var attempt : List.of(Map.entry("slides.mp4", TestPdfs.pdf(1)), Map.entry("week1.mov", this.clip),
				Map.entry("week1.mkv", this.clip), Map.entry("week1.webm", mkv), Map.entry("week1.mp4", mov))) {
			MvcResult result = send(professor, this.course, "lecture_video", attempt.getKey(), attempt.getValue(),
					len(attempt.getValue()));
			assertThat(result.getResponse().getStatus()).as(attempt.getKey()).isEqualTo(422);
			assertThat(ApiClient.json(result.getResponse()).get("code").asString()).isEqualTo("content_mismatch");
		}
		assertThat(uploadsOf(professor)).isZero();
		assertThat(streamObjects()).hasSize(objects);
		assertThat(openMultipartUploads()).isEmpty();

		// 서버가 판정한 형식을 저장한다. client가 보낸 Content-Type은 쓰지 않는다.
		long movUpload = ApiClient.json(send(professor(), this.course, "lecture_video", "week1.mov", mov, len(mov))
			.getResponse()).get("uploadId").asLong();
		long mkvUpload = ApiClient.json(send(professor(), this.course, "lecture_video", "week1.mkv", mkv, len(mkv))
			.getResponse()).get("uploadId").asLong();
		assertThat(detectedMime(movUpload)).isEqualTo("video/quicktime");
		assertThat(detectedMime(mkvUpload)).isEqualTo("video/x-matroska");
	}

	@Test
	void abortsTheMultipartUploadWhenTheBodyIsShorterOrLongerThanDeclared() throws Exception {
		byte[] body = Arrays.copyOf(this.clip, 3000);
		int objects = streamObjects().size();

		MvcResult shorter = send(professor(), this.course, "lecture_video", "week1.mp4", body, "5000");
		assertThat(shorter.getResponse().getStatus()).isEqualTo(400);
		assertThat(ApiClient.json(shorter.getResponse()).get("code").asString()).isEqualTo("upload_incomplete");
		MvcResult longer = send(professor(), this.course, "lecture_video", "week1.mp4", body, "2000");
		assertThat(longer.getResponse().getStatus()).isEqualTo(400);

		assertThat(openMultipartUploads()).isEmpty();
		assertThat(streamObjects()).hasSize(objects);
	}

	@Test
	void streamsALargeBodyInPartsAndRecordsTheSameRowsAsTheMultipartUpload() throws Exception {
		long professor = professor();
		// part 크기(8 MB)를 넘겨 part 두 개로 나뉘게 한다.
		byte[] large = Arrays.copyOf(this.clip, 9 * 1024 * 1024 + 123);
		for (int i = this.clip.length; i < large.length; i++) {
			large[i] = (byte) (i * 7);
		}
		MvcResult result = send(professor, this.course, "lecture_video", "..\\9주차 강의.mp4", large, len(large));
		assertThat(result.getResponse().getStatus()).isEqualTo(201);
		assertThat(result.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
		var view = ApiClient.json(result.getResponse());
		assertThat(view.get("state").asString()).isEqualTo("clean");
		assertThat(view.get("name").asString()).isEqualTo("9주차 강의.mp4");
		assertThat(view.get("sizeBytes").asLong()).isEqualTo(large.length);

		var row = this.jdbc.sql("""
				SELECT f.mime_type, f.source_menu_code, f.scan_state, f.file_size_bytes, b.detected_mime_type,
				       b.storage_key, b.blob_state, b.content_sha256, b.reference_count, u.upload_state
				  FROM upload_sessions u JOIN files f ON f.file_id = u.file_id JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE u.upload_id = :id
				""").param("id", view.get("uploadId").asLong()).query().singleRow();
		assertThat(row).containsEntry("mime_type", "video/mp4")
			.containsEntry("detected_mime_type", "video/mp4")
			.containsEntry("source_menu_code", "lecture_video")
			.containsEntry("scan_state", "clean")
			.containsEntry("blob_state", "clean")
			.containsEntry("upload_state", "clean")
			.containsEntry("content_sha256", sha256(large));
		String key = (String) row.get("storage_key");
		assertThat(key).startsWith(PREFIX);
		byte[] stored = this.s3.getObjectAsBytes((b) -> b.bucket(this.buckets.content()).key(key)).asByteArray();
		assertThat(sha256(stored)).isEqualTo(sha256(large));
		assertThat(openMultipartUploads()).isEmpty();

		// 같은 내용을 다시 올리면 기존 blob을 함께 쓰고 새로 올린 객체는 지운다.
		int objects = streamObjects().size();
		MvcResult again = send(professor(), this.course, "lecture_video", "copy.mp4", large, len(large));
		assertThat(again.getResponse().getStatus()).isEqualTo(201);
		assertThat(streamObjects()).hasSize(objects);
		assertThat(this.jdbc.sql("SELECT reference_count FROM file_blobs WHERE content_sha256 = :sha")
			.param("sha", sha256(large))
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

	@Test
	void replaysAnIdempotentUploadWithoutReadingTheBodyAgain() throws Exception {
		long professor = professor();
		MockHttpServletRequestBuilder first = request(professor, this.course, "lecture_video", "week1.mp4", this.clip,
				len(this.clip), true).header("Idempotency-Key", "video-1");
		long uploadId = ApiClient.json(this.mvc.perform(first).andExpect(status().isCreated())).get("uploadId").asLong();

		MvcResult replay = this.mvc.perform(request(professor, this.course, "lecture_video", "week1.mp4", this.clip,
				len(this.clip), true).header("Idempotency-Key", "video-1")).andReturn();
		assertUntouched(replay, 201);
		assertThat(ApiClient.json(replay.getResponse()).get("uploadId").asLong()).isEqualTo(uploadId);
	}

	/**
	 * 업로드를 지워 참조가 0이 된 영상 blob은 저장소 GC가 blobs/stream/ 객체까지 지운다(ADR-0011 5번). 같은 내용을 다시 올리면 이번
	 * 요청의 새 key로 되살리고 옛 key는 다시 쓰지 않는다.
	 */
	@Test
	void storageGcPurgesUnreferencedStreamBlobsAndARepeatedUploadRevivesThemUnderAFreshKey() throws Exception {
		long professor = professor();
		byte[] video = unique(this.clip);
		long first = ApiClient.json(send(professor, this.course, "lecture_video", "a.mp4", video, len(video)).getResponse())
			.get("uploadId")
			.asLong();
		String oldKey = blobOf(first).get("storage_key").toString();
		new ApiClient(this.mvc, this.data.session(professor)).delete("/api/uploads/" + first)
			.andExpect(status().isNoContent());
		this.jdbc.sql("UPDATE file_blobs SET updated_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE storage_key = :k")
			.param("k", oldKey)
			.update();

		StorageGcDriver.drain(this.context);

		assertThat(streamObjects()).doesNotContain(oldKey);
		assertThat(blobOf(first).get("purged")).isEqualTo(1L);

		long again = ApiClient.json(send(professor, this.course, "lecture_video", "b.mp4", video, len(video)).getResponse())
			.get("uploadId")
			.asLong();
		Map<String, Object> revived = blobOf(again);
		assertThat(revived.get("file_blob_id")).isEqualTo(blobOf(first).get("file_blob_id"));
		assertThat(revived.get("purged")).isEqualTo(0L);
		assertThat(revived.get("storage_key").toString()).startsWith(PREFIX).isNotEqualTo(oldKey);
		assertThat(streamObjects()).contains(revived.get("storage_key").toString());
		assertThat(sha256(this.s3.getObjectAsBytes((b) -> b.bucket(this.buckets.content())
			.key(revived.get("storage_key").toString())).asByteArray())).isEqualTo(sha256(video));
	}

	@Test
	void refusesVideoContentThatWasRejectedBeforeAndKeepsNothingOfIt() throws Exception {
		byte[] video = unique(this.clip);
		long first = ApiClient.json(send(professor(), this.course, "lecture_video", "a.mp4", video, len(video)).getResponse())
			.get("uploadId")
			.asLong();
		this.jdbc.sql("UPDATE file_blobs SET blob_state = 'rejected' WHERE file_blob_id = :b")
			.param("b", blobOf(first).get("file_blob_id"))
			.update();
		int objects = streamObjects().size();

		MvcResult again = send(professor(), this.course, "lecture_video", "b.mp4", video, len(video));

		assertThat(again.getResponse().getStatus()).isEqualTo(422);
		assertThat(ApiClient.json(again.getResponse()).get("code").asString()).isEqualTo("content_rejected");
		assertThat(streamObjects()).hasSize(objects);
		assertThat(openMultipartUploads()).isEmpty();
	}

	private Map<String, Object> blobOf(long uploadId) {
		return this.jdbc.sql("""
				SELECT b.file_blob_id, b.storage_key, CAST(b.purged_at IS NOT NULL AS SIGNED) AS purged
				  FROM upload_sessions u JOIN files f ON f.file_id = u.file_id JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				 WHERE u.upload_id = :id
				""").param("id", uploadId).query().singleRow();
	}

	// 다른 테스트의 영상과 내용이 겹치지 않게 뒤에 임의 byte를 붙인다. 앞부분 signature는 그대로다.
	private static byte[] unique(byte[] clip) {
		byte[] suffix = java.util.UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		byte[] video = Arrays.copyOf(clip, clip.length + suffix.length);
		System.arraycopy(suffix, 0, video, clip.length, suffix.length);
		return video;
	}

	@Test
	void keepsVideosOffTheMultipartFormUpload() throws Exception {
		ApiClient client = new ApiClient(this.mvc, this.data.session(professor()));
		client.upload(this.course, "lecture_video", "week1.mp4", this.clip, null).andExpect(status().isBadRequest());
	}

	@Test
	void workerAbortsMultipartUploadsLeftByAStoppedProcess() {
		String key = PREFIX + "abandoned-" + System.nanoTime();
		this.s3.createMultipartUpload((b) -> b.bucket(this.buckets.content()).key(key));
		assertThat(openMultipartUploads()).contains(key);
		double aborted = this.meters.get("astra.files.upload.stream.aborted").counter().count();

		assertThat(this.janitor.abortOlderThan(Duration.ofDays(1))).isZero();
		assertThat(this.meters.get("astra.files.upload.stream.aborted").counter().count()).isEqualTo(aborted);
		int count = this.janitor.abortOlderThan(Duration.ZERO);
		assertThat(count).isPositive();

		assertThat(openMultipartUploads()).isEmpty();
		assertThat(this.meters.get("astra.files.upload.stream.aborted").counter().count()).isEqualTo(aborted + count);
		assertThat(this.meters.get("astra.files.upload.stream.aborted").counter().getId().getTags())
			.extracting((t) -> t.getKey())
			.containsExactly("role");
	}

	private long professor() {
		long professor = this.data.user("PROFESSOR");
		this.data.instructor(this.course, professor);
		return professor;
	}

	private MvcResult send(long userId, long courseId, String purpose, String name, byte[] body, String contentLength)
			throws Exception {
		return this.mvc.perform(request(userId, courseId, purpose, name, body, contentLength, true)).andReturn();
	}

	private MockHttpServletRequestBuilder request(Long userId, long courseId, String purpose, String name, byte[] body,
			String contentLength, boolean csrf) throws Exception {
		MockHttpServletRequestBuilder request = post("/api/uploads/stream").queryParam("courseId", Long.toString(courseId))
			.queryParam("purpose", purpose)
			.queryParam("name", name)
			.contentType(MediaType.APPLICATION_OCTET_STREAM)
			.content(body);
		// MockMvc는 본문 길이로 Content-Length를 채운다. 선언 값을 바꾸거나(크기를 속인 요청) 지운다(chunked 요청).
		request.with((r) -> {
			r.removeHeader("Content-Length");
			if (contentLength != null) {
				r.addHeader("Content-Length", contentLength);
			}
			return r;
		});
		if (csrf) {
			Cookie token = this.mvc.perform(get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
			request.cookie(token).header("X-XSRF-TOKEN", token.getValue());
		}
		if (userId != null) {
			request.cookie(new Cookie("ASTRA_SESSION", this.data.session(userId)));
		}
		return request;
	}

	// handler가 본문 stream을 열지 않았으면 처음 byte부터 그대로 남아 있다.
	private static void assertUntouched(MvcResult result, int status) throws Exception {
		assertThat(result.getResponse().getStatus()).isEqualTo(status);
		byte[] content = result.getRequest().getContentAsByteArray();
		assertThat(result.getRequest().getInputStream().available()).as("unread body bytes").isEqualTo(content.length);
	}

	private int uploadsOf(long userId) {
		return this.jdbc.sql("SELECT COUNT(*) FROM upload_sessions WHERE owner_user_id = :u")
			.param("u", userId)
			.query(Integer.class)
			.single();
	}

	private String detectedMime(long uploadId) {
		return this.jdbc.sql("""
				SELECT b.detected_mime_type FROM upload_sessions u JOIN files f ON f.file_id = u.file_id
				  JOIN file_blobs b ON b.file_blob_id = f.file_blob_id WHERE u.upload_id = :id
				""").param("id", uploadId).query(String.class).single();
	}

	private List<String> streamObjects() {
		return this.s3.listObjectsV2Paginator((b) -> b.bucket(this.buckets.content()).prefix(PREFIX))
			.contents()
			.stream()
			.map(S3Object::key)
			.toList();
	}

	private List<String> openMultipartUploads() {
		return this.s3.listMultipartUploads((b) -> b.bucket(this.buckets.content()).prefix(PREFIX))
			.uploads()
			.stream()
			.map((u) -> u.key())
			.toList();
	}

	private static String len(byte[] body) {
		return Integer.toString(body.length);
	}

	private static String sha256(byte[] content) throws Exception {
		return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
	}

}
