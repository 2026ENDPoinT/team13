package com.stackcorp.astra.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestPdfs;
import com.stackcorp.astra.TestVideos;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.files.VideoRunnerDriver;
import com.stackcorp.astra.files.api.TranscodeException;
import com.stackcorp.astra.files.api.TranscodeException.Code;
import com.stackcorp.astra.files.api.VideoKeys;
import com.stackcorp.astra.files.api.VideoTranscoder;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Object;
import tools.jackson.databind.JsonNode;

/**
 * 영상 활동: 담당자가 올린 영상을 콘텐츠로 등록하면 worker가 HLS AES-128로 변환해 게시하고, 그 뒤에만 재생 허가와 key가 나간다.
 * 실패와 취소는 아무것도 게시하지 않는다(ADR-0016, ADR-0007 8번).
 */
class LectureVideoFlowTests extends IntegrationTest {

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
	VideoKeys keys;

	@Autowired
	VideoTranscoder transcoder;

	@Autowired
	ApplicationContext context;

	long course;

	long professorId;

	ApiClient professor;

	ApiClient student;

	@BeforeEach
	void course() {
		VideoRunnerDriver.drain(this.context);
		this.course = this.data.course();
		this.professorId = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, this.professorId);
		this.data.enroll(this.course, stu);
		this.professor = new ApiClient(this.mvc, this.data.session(this.professorId));
		this.student = new ApiClient(this.mvc, this.data.session(stu));
	}

	@Test
	void publishesTheTranscodedLectureAndOpensPlaybackOnlyWhenReady() throws Exception {
		long uploadId = upload(TestVideos.clip(3, "mp4"));
		JsonNode created = ApiClient.json(this.professor
			.postJson(lectures(), body(2, "2주차 영상", "[{\"title\":\"1. 개요\",\"uploadId\":" + uploadId
					+ ",\"requiredSec\":2}]"))
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.items[0].state").value("processing")));
		long activityId = created.get("activityId").asLong();
		long itemId = created.get("items").get(0).get("itemId").asLong();

		this.student.get(activity(activityId))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.type").value("lecture_weeks"))
			.andExpect(jsonPath("$.lectureItems[0].state").value("processing"))
			.andExpect(jsonPath("$.lectureItems[0].durationSec").doesNotExist());
		this.student.post(item(itemId, "playback")).andExpect(status().isNotFound());
		this.student.get(item(itemId, "hls-key")).andExpect(status().isNotFound());
		// 첨부한 업로드는 다시 쓸 수 없다.
		this.professor.postJson(lectures(), body(3, "재사용", "[{\"title\":\"a\",\"uploadId\":" + uploadId + "}]"))
			.andExpect(status().isConflict());

		assertThat(VideoRunnerDriver.runOnce(this.context)).isTrue();

		this.student.get(activity(activityId))
			.andExpect(jsonPath("$.lectureItems[0].state").value("ready"))
			.andExpect(jsonPath("$.lectureItems[0].durationSec").value(3))
			.andExpect(jsonPath("$.lectureItems[0].requiredSec").value(2));
		String asset = assetKey(itemId);
		assertThat(this.jdbc.sql("""
				SELECT j.job_state FROM video_transcode_jobs j JOIN video_assets a ON a.video_asset_id = j.video_asset_id
				 WHERE a.asset_key = :asset
				""").param("asset", asset).query(String.class).single()).isEqualTo("succeeded");
		List<String> published = delivery(asset);
		assertThat(published).contains("vod/" + asset + "/index.m3u8", "vod/" + asset + "/seg_00000.ts");
		String playlist = new String(this.s3
			.getObjectAsBytes((b) -> b.bucket(this.buckets.delivery()).key("vod/" + asset + "/index.m3u8"))
			.asByteArray(), StandardCharsets.US_ASCII);
		assertThat(playlist).startsWith("#EXTM3U")
			.contains("#EXT-X-KEY:METHOD=AES-128,URI=\"/api/courses/" + this.course + "/lecture-items/" + itemId
					+ "/hls-key\",IV=0x")
			.contains("#EXT-X-ENDLIST");

		this.student.post(item(itemId, "playback"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.manifestUrl").value("/vod/" + asset + "/index.m3u8"));
		byte[] key = this.student.get(item(itemId, "hls-key"))
			.andExpect(status().isOk())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andReturn()
			.getResponse()
			.getContentAsByteArray();
		assertThat(key).hasSize(16).isEqualTo(this.keys.find(asset).orElseThrow());
		new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT"))).get(item(itemId, "hls-key"))
			.andExpect(status().isNotFound());
		this.mvc.perform(get(item(itemId, "hls-key"))).andExpect(status().isUnauthorized());
	}

	@Test
	void letsOnlyInstructorsRegisterTheirOwnCleanVideoUploadsAndRollsBackOnAnyBadItem() throws Exception {
		long uploadId = upload(TestVideos.clip(1, "mp4"));
		String items = "[{\"title\":\"1\",\"uploadId\":" + uploadId + "}]";
		this.student.postJson(lectures(), body(1, "학생", items)).andExpect(status().isNotFound());
		new ApiClient(this.mvc, this.data.session(this.data.user("PROFESSOR"))).postJson(lectures(), body(1, "외부", items))
			.andExpect(status().isNotFound());
		this.professor.postJson("/api/courses/" + this.data.course() + "/lectures", body(1, "다른 과목", items))
			.andExpect(status().isNotFound());
		new ApiClient(this.mvc, null).postJson(lectures(), body(1, "익명", items)).andExpect(status().isUnauthorized());
		this.mvc
			.perform(post(lectures()).contentType(MediaType.APPLICATION_JSON)
				.content(body(1, "CSRF 없음", items))
				.cookie(new Cookie("ASTRA_SESSION", this.data.session(this.professorId))))
			.andExpect(status().isForbidden());
		this.professor.postJson(lectures(), body(1, "빈 목록", "[]")).andExpect(status().isBadRequest());
		this.professor.postJson(lectures(), "{\"weekNo\":1,\"title\":\"x\",\"items\":" + items + ",\"createdBy\":1}")
			.andExpect(status().isBadRequest());

		// 강의 자료로 올린 PDF, 다른 담당자의 영상은 첨부할 수 없다. 하나라도 안 되면 전체를 되돌린다.
		long pdfUpload = this.professor.uploaded(this.course, "slides.pdf", TestPdfs.pdf(1));
		long colleague = this.data.user("PROFESSOR");
		this.data.instructor(this.course, colleague);
		long colleagueUpload = TestVideos.upload(this.mvc, this.data.session(colleague), this.course, "c.mp4",
				TestVideos.clip(1, "mp4"));
		for (long other : new long[] { pdfUpload, colleagueUpload }) {
			this.professor
				.postJson(lectures(), body(1, "되돌림", "[{\"title\":\"1\",\"uploadId\":" + uploadId
						+ "},{\"title\":\"2\",\"uploadId\":" + other + "}]"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("attachment_not_ready"));
		}
		this.professor.get("/api/uploads/" + uploadId).andExpect(jsonPath("$.state").value("clean"));
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM learning_activities WHERE course_id = :c")
			.param("c", this.course)
			.query(Integer.class)
			.single()).isZero();
		assertThat(VideoRunnerDriver.runOnce(this.context)).isFalse();
	}

	@Test
	void marksTheItemFailedAndPublishesNothingWhenTheVideoCannotBeTranscoded() throws Exception {
		long itemId = lecture(upload(TestVideos.brokenMp4()));
		String asset = assetKey(itemId);

		assertThat(VideoRunnerDriver.runOnce(this.context)).isTrue();

		JsonNode item = ApiClient.json(this.student.get(activityOf(itemId))).get("lectureItems").get(0);
		assertThat(item.get("state").asString()).isEqualTo("failed");
		assertThat(item.size()).isEqualTo(5);
		assertThat(item.has("errorCode")).isFalse();
		assertThat(job(asset)).containsEntry("job_state", "failed").containsEntry("error_code", "invalid_media");
		assertThat(delivery(asset)).isEmpty();
		this.student.post(item(itemId, "playback")).andExpect(status().isNotFound());
		assertThat(VideoRunnerDriver.runOnce(this.context)).isFalse();
	}

	@Test
	void retriesTransientFailuresWithBackoffWithoutLeavingPartialOutput() throws Exception {
		long itemId = lecture(upload(TestVideos.clip(2, "mp4")));
		String asset = assetKey(itemId);
		// 첫 segment를 쓴 뒤 transcoder 연결이 끊긴 상황
		VideoTranscoder flaky = (source, sink, cancelled) -> {
			sink.accept(0, 2.0, new byte[188]);
			throw new TranscodeException(Code.UNAVAILABLE, "transcoder에 연결하지 못했습니다.");
		};

		assertThat(VideoRunnerDriver.runOnce(this.context, flaky)).isTrue();

		assertThat(job(asset)).containsEntry("job_state", "queued").containsEntry("error_code", "unavailable");
		assertThat(((Number) job(asset).get("attempt_count")).intValue()).isEqualTo(1);
		assertThat(this.jdbc.sql("""
				SELECT j.next_attempt_at > CURRENT_TIMESTAMP(3) FROM video_transcode_jobs j
				  JOIN video_assets a ON a.video_asset_id = j.video_asset_id WHERE a.asset_key = :asset
				""").param("asset", asset).query(Boolean.class).single()).isTrue();
		assertThat(delivery(asset)).isEmpty();
		this.student.get(activityOf(itemId)).andExpect(jsonPath("$.lectureItems[0].state").value("processing"));
		// 대기 시간이 지나기 전에는 다시 잡지 않는다.
		assertThat(VideoRunnerDriver.runOnce(this.context)).isFalse();

		dueNow(asset);
		assertThat(VideoRunnerDriver.runOnce(this.context)).isTrue();

		assertThat(job(asset)).containsEntry("job_state", "succeeded");
		assertThat(((Number) job(asset).get("attempt_count")).intValue()).isEqualTo(2);
		this.student.get(activityOf(itemId)).andExpect(jsonPath("$.lectureItems[0].state").value("ready"));
	}

	@Test
	void givesUpAfterTheLastAttemptAndRecoversJobsOfAStoppedWorker() throws Exception {
		long itemId = lecture(upload(TestVideos.clip(1, "mp4")));
		String asset = assetKey(itemId);
		AtomicInteger calls = new AtomicInteger();
		VideoTranscoder down = (source, sink, cancelled) -> {
			calls.incrementAndGet();
			throw new TranscodeException(Code.UNAVAILABLE, "transcoder에 연결하지 못했습니다.");
		};

		// 실행하던 worker가 멈춰 lease가 끝난 작업은 다시 대기로 돌아간다.
		this.jdbc.sql("""
				UPDATE video_transcode_jobs j JOIN video_assets a ON a.video_asset_id = j.video_asset_id
				   SET j.job_state = 'running', j.attempt_count = 1, j.lease_owner = 'stopped-worker',
				       j.lease_expires_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
				 WHERE a.asset_key = :asset
				""").param("asset", asset).update();
		VideoRunnerDriver.recoverExpired(this.context);
		assertThat(job(asset)).containsEntry("job_state", "queued").containsEntry("error_code", "interrupted");

		dueNow(asset);
		assertThat(VideoRunnerDriver.runOnce(this.context, down)).isTrue();
		assertThat(job(asset)).containsEntry("job_state", "queued");
		dueNow(asset);
		assertThat(VideoRunnerDriver.runOnce(this.context, down)).isTrue();

		assertThat(calls.get()).isEqualTo(2);
		assertThat(job(asset)).containsEntry("job_state", "failed").containsEntry("error_code", "unavailable");
		assertThat(((Number) job(asset).get("attempt_count")).intValue()).isEqualTo(3);
		this.student.get(activityOf(itemId)).andExpect(jsonPath("$.lectureItems[0].state").value("failed"));
		dueNow(asset);
		assertThat(VideoRunnerDriver.runOnce(this.context)).isFalse();
	}

	@Test
	void cancelsQueuedAndRunningJobsAndWithdrawsPublishedVideosWhenTheFileIsDeleted() throws Exception {
		// 대기 중인 작업
		long queuedItem = lecture(upload(TestVideos.clip(1, "mp4")));
		String queuedAsset = assetKey(queuedItem);
		deleteFile(queuedItem);
		VideoRunnerDriver.withdrawRemovedFiles(this.context);
		assertThat(job(queuedAsset)).containsEntry("job_state", "cancelled");
		assertThat(VideoRunnerDriver.runOnce(this.context)).isFalse();
		this.student.get(activityOf(queuedItem)).andExpect(jsonPath("$.lectureItems[0].state").value("failed"));

		// 게시를 마친 영상
		long readyItem = lecture(upload(TestVideos.clip(1, "mp4")));
		String readyAsset = assetKey(readyItem);
		assertThat(VideoRunnerDriver.runOnce(this.context)).isTrue();
		this.student.post(item(readyItem, "playback")).andExpect(status().isOk());
		deleteFile(readyItem);
		VideoRunnerDriver.withdrawRemovedFiles(this.context);
		this.student.post(item(readyItem, "playback")).andExpect(status().isNotFound());
		this.professor.get(item(readyItem, "hls-key")).andExpect(status().isNotFound());
		assertThat(delivery(readyAsset)).isEmpty();
		assertThat(this.keys.find(readyAsset)).isEmpty();

		// 변환하는 도중에 원본이 삭제되면 게시하지 않고 취소로 끝낸다.
		long runningItem = lecture(upload(TestVideos.clip(1, "mp4")));
		String runningAsset = assetKey(runningItem);
		VideoTranscoder deletedMidway = (source, sink, cancelled) -> {
			sink.accept(0, 1.0, new byte[188]);
			deleteFile(runningItem);
			VideoRunnerDriver.withdrawRemovedFiles(this.context);
			if (cancelled.getAsBoolean()) {
				throw new TranscodeException(Code.CANCELLED, "변환이 취소되었습니다.");
			}
			return new VideoTranscoder.Transcoded(1, 1.0);
		};
		assertThat(VideoRunnerDriver.runOnce(this.context, deletedMidway)).isTrue();
		assertThat(job(runningAsset)).containsEntry("job_state", "cancelled");
		assertThat(delivery(runningAsset)).isEmpty();
		VideoRunnerDriver.withdrawRemovedFiles(this.context);
		assertThat(this.keys.find(runningAsset)).isEmpty();
		assertThat(this.jdbc.sql("SELECT asset_state FROM video_assets WHERE asset_key = :a")
			.param("a", runningAsset)
			.query(String.class)
			.single()).isEqualTo("removed");
	}

	private long upload(byte[] video) throws Exception {
		return TestVideos.upload(this.mvc, this.data.session(this.professorId), this.course, "lecture.mp4", video);
	}

	/** @return 영상 하나로 만든 활동의 콘텐츠 ID */
	private long lecture(long uploadId) throws Exception {
		return ApiClient
			.json(this.professor
				.postJson(lectures(), body(4, "4주차 영상", "[{\"title\":\"강의\",\"uploadId\":" + uploadId + "}]"))
				.andExpect(status().isCreated()))
			.get("items")
			.get(0)
			.get("itemId")
			.asLong();
	}

	private void deleteFile(long itemId) {
		this.jdbc.sql("""
				UPDATE files f JOIN video_assets a ON a.file_id = f.file_id
				  JOIN online_lecture_items i ON i.video_asset_id = a.video_asset_id
				   SET f.state = 'deleted', f.deleted_at = CURRENT_TIMESTAMP(3)
				 WHERE i.online_lecture_item_id = :item
				""").param("item", itemId).update();
	}

	private void dueNow(String asset) {
		this.jdbc.sql("""
				UPDATE video_transcode_jobs j JOIN video_assets a ON a.video_asset_id = j.video_asset_id
				   SET j.next_attempt_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 SECOND)
				 WHERE a.asset_key = :asset AND j.job_state = 'queued'
				""").param("asset", asset).update();
	}

	private String assetKey(long itemId) {
		return this.jdbc.sql("""
				SELECT a.asset_key FROM online_lecture_items i JOIN video_assets a ON a.video_asset_id = i.video_asset_id
				 WHERE i.online_lecture_item_id = :item
				""").param("item", itemId).query(String.class).single();
	}

	private Map<String, Object> job(String asset) {
		return this.jdbc.sql("""
				SELECT j.job_state, j.error_code, j.attempt_count FROM video_transcode_jobs j
				  JOIN video_assets a ON a.video_asset_id = j.video_asset_id WHERE a.asset_key = :asset
				""").param("asset", asset).query().singleRow();
	}

	private List<String> delivery(String asset) {
		return this.s3.listObjectsV2Paginator((b) -> b.bucket(this.buckets.delivery()).prefix("vod/" + asset + "/"))
			.contents()
			.stream()
			.map(S3Object::key)
			.toList();
	}

	private String activityOf(long itemId) {
		long activityId = this.jdbc.sql("SELECT activity_id FROM online_lecture_items WHERE online_lecture_item_id = :i")
			.param("i", itemId)
			.query(Long.class)
			.single();
		return activity(activityId);
	}

	private String lectures() {
		return "/api/courses/" + this.course + "/lectures";
	}

	private String activity(long activityId) {
		return "/api/courses/" + this.course + "/activities/" + activityId;
	}

	private String item(long itemId, String action) {
		return "/api/courses/" + this.course + "/lecture-items/" + itemId + "/" + action;
	}

	private static String body(int weekNo, String title, String items) {
		return "{\"weekNo\":" + weekNo + ",\"title\":\"" + title + "\",\"items\":" + items + "}";
	}

}
