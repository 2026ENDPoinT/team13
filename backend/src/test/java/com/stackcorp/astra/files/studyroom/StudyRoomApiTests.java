package com.stackcorp.astra.files.studyroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 학습실의 내 폴더, 배치, 강의 녹음(ADR-0040). 소유자만 자기 행을 보고 바꾸고(SEC-08), 녹음 바이트는 소유자의 재생 경로로만 나간다.
 * 변경은 version으로 엇갈림을 막고, 녹음은 업로드 검사(형식, 크기, 악성코드 검사 상태)와 사용자별 용량을 따른다.
 */
class StudyRoomApiTests extends IntegrationTest {

	static final String BASE = "/api/me/study-room";

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	long ownerId;

	ApiClient owner;

	long otherId;

	ApiClient other;

	@BeforeEach
	void users() {
		this.course = this.data.course();
		this.ownerId = this.data.user("STUDENT");
		this.data.enroll(this.course, this.ownerId);
		this.owner = new ApiClient(this.mvc, this.data.session(this.ownerId));
		this.otherId = this.data.user("STUDENT");
		this.data.enroll(this.course, this.otherId);
		this.other = new ApiClient(this.mvc, this.data.session(this.otherId));
	}

	@Test
	void foldersAreCreatedInOrderRenamedAndDeletedWithVersions() throws Exception {
		JsonNode first = json(createFolder(this.owner, this.course, "  중간고사  ").andExpect(status().isCreated()));
		assertThat(first.get("name").asString()).isEqualTo("중간고사");
		assertThat(first.get("position").asInt()).isEqualTo(1);
		assertThat(first.get("version").asInt()).isEqualTo(1);
		assertThat(first.get("courseId").asLong()).isEqualTo(this.course);
		long second = folder(this.owner, this.course, "Week 1");

		// 같은 과목의 같은 이름은 대소문자 구분 없이 409, 다른 과목이면 된다.
		createFolder(this.owner, this.course, "week 1").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("name_conflict"));
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.ownerId);
		createFolder(this.owner, otherCourse, "Week 1").andExpect(status().isCreated());
		// 구성원이 아닌 과목, 잘못된 이름
		createFolder(this.owner, this.data.course(), "몰래").andExpect(status().isNotFound());
		createFolder(this.owner, this.course, " ").andExpect(status().isBadRequest());
		createFolder(this.owner, this.course, "a/b").andExpect(status().isBadRequest());
		createFolder(this.owner, this.course, "x".repeat(61)).andExpect(status().isBadRequest());
		this.owner.postJson(BASE + "/courses/" + this.course + "/folders", "{\"name\":\"a\",\"parentId\":1}")
			.andExpect(status().isBadRequest());

		JsonNode view = course(this.owner, this.course);
		assertThat(ids(view.get("folders"))).containsExactly(first.get("id").asLong(), second);

		// 이름 바꾸기: If-Match 없으면 428, 다르면 409, 맞으면 version이 오른다.
		long id = first.get("id").asLong();
		this.owner.send(patch(BASE + "/folders/" + id, null, "{\"name\":\"기말\"}"))
			.andExpect(status().isPreconditionRequired())
			.andExpect(jsonPath("$.code").value("precondition_required"));
		this.owner.send(patch(BASE + "/folders/" + id, "W/\"1\"", "{\"name\":\"기말\"}")).andExpect(status().isBadRequest());
		this.owner.send(patch(BASE + "/folders/" + id, "\"1\"", "{\"name\":\"기말\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("기말"))
			.andExpect(jsonPath("$.version").value(2));
		this.owner.send(patch(BASE + "/folders/" + id, "\"1\"", "{\"name\":\"다시\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		this.owner.send(patch(BASE + "/folders/" + id, "\"2\"", "{\"name\":\"WEEK 1\"}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("name_conflict"));

		this.owner.send(delete(BASE + "/folders/" + second, "\"2\"")).andExpect(status().isConflict());
		this.owner.send(delete(BASE + "/folders/" + second, "\"1\"")).andExpect(status().isNoContent());
		assertThat(ids(course(this.owner, this.course).get("folders"))).containsExactly(id);
		// 지운 뒤에 만든 폴더는 끝에 붙는다.
		assertThat(json(createFolder(this.owner, this.course, "새 폴더")).get("position").asInt()).isEqualTo(2);
	}

	@Test
	void folderCreationIsIdempotentAndBoundedPerCourse() throws Exception {
		JsonNode first = json(this.owner.send(post(BASE + "/courses/" + this.course + "/folders", "{\"name\":\"과제\"}")
			.header("Idempotency-Key", "import-folder-1")).andExpect(status().isCreated()));
		JsonNode again = json(this.owner.send(post(BASE + "/courses/" + this.course + "/folders", "{\"name\":\"과제\"}")
			.header("Idempotency-Key", "import-folder-1")).andExpect(status().isCreated()));
		assertThat(again.get("id").asLong()).isEqualTo(first.get("id").asLong());
		this.owner.send(post(BASE + "/courses/" + this.course + "/folders", "{\"name\":\"다른 이름\"}")
			.header("Idempotency-Key", "import-folder-1"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));

		for (int i = 2; i <= 50; i++) {
			folder(this.owner, this.course, "폴더 " + i);
		}
		createFolder(this.owner, this.course, "51번째").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("too_many_folders"));
		// 다른 사람의 한도와는 따로 센다.
		createFolder(this.other, this.course, "51번째").andExpect(status().isCreated());
	}

	@Test
	void reorderNeedsTheWholeCurrentSetWithMatchingVersions() throws Exception {
		long a = folder(this.owner, this.course, "A");
		long b = folder(this.owner, this.course, "B");
		long c = folder(this.owner, this.course, "C");

		JsonNode reordered = json(reorder(this.owner, this.course, a, 1, c, 1, b, 1).andExpect(status().isOk()));
		assertThat(ids(reordered.get("folders"))).containsExactly(a, c, b);
		// 자리가 바뀐 폴더만 version이 오른다.
		assertThat(versions(reordered.get("folders"))).containsExactly(1, 2, 2);
		assertThat(ids(course(this.owner, this.course).get("folders"))).containsExactly(a, c, b);

		reorder(this.owner, this.course, a, 1, c, 1, b, 1).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		reorder(this.owner, this.course, a, 1, c, 2).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("folder_set_changed"));
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.ownerId);
		long elsewhere = folder(this.owner, otherCourse, "D");
		reorder(this.owner, this.course, a, 1, c, 2, b, 2, elsewhere, 1).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("folder_set_changed"));
		reorder(this.owner, this.course, a, 1, a, 1).andExpect(status().isBadRequest());
		this.owner.send(put(BASE + "/courses/" + this.course + "/folder-order", null, "{\"folders\":[]}"))
			.andExpect(status().isBadRequest());
		assertThat(ids(course(this.owner, this.course).get("folders"))).containsExactly(a, c, b);
	}

	@Test
	void concurrentReordersWithTheSameVersionsChangeTheOrderOnce() throws Exception {
		long a = folder(this.owner, this.course, "A");
		long b = folder(this.owner, this.course, "B");
		long c = folder(this.owner, this.course, "C");
		List<Integer> statuses = concurrently(List.of(
				() -> reorder(this.owner, this.course, c, 1, b, 1, a, 1).andReturn().getResponse().getStatus(),
				() -> reorder(this.owner, this.course, b, 1, a, 1, c, 1).andReturn().getResponse().getStatus()));
		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		List<Long> order = ids(course(this.owner, this.course).get("folders"));
		assertThat(order).isIn(List.of(c, b, a), List.of(b, a, c));
	}

	@Test
	void courseFilesArePlacedMovedAndUnplacedWithVersionsAndOnlyWhileReadable() throws Exception {
		long fileId = material(true);
		long hidden = material(false);
		long f1 = folder(this.owner, this.course, "1");
		long f2 = folder(this.owner, this.course, "2");

		placeFile(fileId, null, f1).andExpect(status().isPreconditionRequired());
		placeFile(fileId, "\"1\"", f1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_conflict"));
		placeFile(fileId, "\"0\"", f1).andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("course_file"))
			.andExpect(jsonPath("$.itemId").value(fileId))
			.andExpect(jsonPath("$.folderId").value(f1))
			.andExpect(jsonPath("$.version").value(1));
		// 이미 놓인 항목에 "0"은 엇갈린 요청이다.
		placeFile(fileId, "\"0\"", f2).andExpect(status().isConflict());
		// 같은 폴더에 다시 두면 그대로다.
		placeFile(fileId, "\"1\"", f1).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(1));
		placeFile(fileId, "\"1\"", f2).andExpect(status().isOk()).andExpect(jsonPath("$.folderId").value(f2))
			.andExpect(jsonPath("$.version").value(2));
		JsonNode placements = course(this.owner, this.course).get("placements");
		assertThat(placements).hasSize(1);
		assertThat(placements.get(0).get("folderId").asLong()).isEqualTo(f2);

		// 공개 전 자료, 다른 과목의 폴더, 없는 파일은 404다.
		placeFile(hidden, "\"0\"", f1).andExpect(status().isNotFound());
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.ownerId);
		long elsewhere = folder(this.owner, otherCourse, "다른 과목");
		placeFile(fileId, "\"2\"", elsewhere).andExpect(status().isNotFound());
		placeFile(Long.MAX_VALUE / 2, "\"0\"", f1).andExpect(status().isNotFound());
		this.owner.send(put(BASE + "/placements/documents/" + fileId, "\"2\"", "{\"folderId\":" + f1 + "}"))
			.andExpect(status().isNotFound());

		// 빼기
		this.owner.send(delete(BASE + "/placements/course-files/" + fileId, "\"1\"")).andExpect(status().isConflict());
		this.owner.send(delete(BASE + "/placements/course-files/" + fileId, "\"2\"")).andExpect(status().isNoContent());
		this.owner.send(delete(BASE + "/placements/course-files/" + fileId, "\"2\"")).andExpect(status().isNotFound());
		assertThat(course(this.owner, this.course).get("placements")).isEmpty();

		// 자료를 지우면(파일 삭제) 배치는 보이지 않는다. 폴더를 지우면 배치도 사라진다.
		placeFile(fileId, "\"0\"", f1).andExpect(status().isOk());
		this.jdbc.sql("UPDATE files SET state = 'deleted' WHERE file_id = :id").param("id", fileId).update();
		assertThat(course(this.owner, this.course).get("placements")).isEmpty();
		this.owner.send(delete(BASE + "/folders/" + f1, "\"1\"")).andExpect(status().isNoContent());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM study_room_placements WHERE owner_user_id = :o")
			.param("o", this.ownerId)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void concurrentMovesOfOneItemWithTheSameVersionApplyOnce() throws Exception {
		long fileId = material(true);
		long f1 = folder(this.owner, this.course, "1");
		long f2 = folder(this.owner, this.course, "2");
		List<Integer> statuses = concurrently(List.of(
				() -> placeFile(fileId, "\"0\"", f1).andReturn().getResponse().getStatus(),
				() -> placeFile(fileId, "\"0\"", f2).andReturn().getResponse().getStatus()));
		assertThat(statuses).containsExactlyInAnyOrder(200, 409);
		JsonNode placement = course(this.owner, this.course).get("placements").get(0);
		assertThat(placement.get("version").asInt()).isEqualTo(1);

		long target = placement.get("folderId").asLong() == f1 ? f2 : f1;
		long back = target == f1 ? f2 : f1;
		statuses = concurrently(List.of(
				() -> placeFile(fileId, "\"1\"", target).andReturn().getResponse().getStatus(),
				() -> this.owner.send(delete(BASE + "/placements/course-files/" + fileId, "\"1\""))
					.andReturn()
					.getResponse()
					.getStatus()));
		assertThat(statuses).containsAnyOf(200, 204).contains(409);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM study_room_placements WHERE owner_user_id = :o AND study_room_folder_id = :f")
			.param("o", this.ownerId)
			.param("f", back)
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void recordingsAreStoredWithTheirMetadataAndPlayedWithRanges() throws Exception {
		long folderId = folder(this.owner, this.course, "강의");
		byte[] audio = webm();
		Instant startedAt = Instant.now().minus(2, ChronoUnit.HOURS).truncatedTo(ChronoUnit.MILLIS);
		JsonNode created = json(record(this.owner, this.course, "lecture.webm", audio, "녹음 11.13 14:05", "mic", "3120450",
				startedAt.toString(), folderId, null).andExpect(status().isCreated()).andExpect(header().string("ETag", "\"1\"")));
		long id = created.get("id").asLong();
		assertThat(created.get("courseId").asLong()).isEqualTo(this.course);
		assertThat(created.get("title").asString()).isEqualTo("녹음 11.13 14:05");
		assertThat(created.get("source").asString()).isEqualTo("mic");
		assertThat(created.get("mimeType").asString()).isEqualTo("audio/webm");
		assertThat(created.get("sizeBytes").asLong()).isEqualTo(audio.length);
		assertThat(created.get("durationMs").asLong()).isEqualTo(3120450);
		assertThat(Instant.parse(created.get("createdAt").asString())).isEqualTo(startedAt);
		assertThat(created.get("fileName").isNull()).isTrue();
		assertThat(created.get("state").asString()).isEqualTo("ready");

		JsonNode view = course(this.owner, this.course);
		assertThat(ids(view.get("recordings"))).containsExactly(id);
		assertThat(view.get("placements").get(0).get("kind").asString()).isEqualTo("recording");
		assertThat(view.get("placements").get(0).get("itemId").asLong()).isEqualTo(id);
		assertThat(view.get("placements").get(0).get("folderId").asLong()).isEqualTo(folderId);
		assertThat(view.get("usage").get("usedBytes").asLong()).isEqualTo(audio.length);
		assertThat(view.get("usage").get("recordingCount").asInt()).isEqualTo(1);
		assertThat(view.get("usage").get("maxFileBytes").asLong()).isEqualTo(52428800);

		// 전체, 앞 구간, 열린 구간, 뒤에서부터, 내용 밖, 여러 구간
		this.owner.get(BASE + "/recordings/" + id + "/content")
			.andExpect(status().isOk())
			.andExpect(header().string("Accept-Ranges", "bytes"))
			.andExpect(header().string("Content-Type", "audio/webm"))
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Content-Disposition",
					allOf(startsWith("attachment;"), containsString("filename*=UTF-8''"))))
			.andExpect(content().bytes(audio));
		range(id, "bytes=0-9").andExpect(status().isPartialContent())
			.andExpect(header().string("Content-Range", "bytes 0-9/" + audio.length))
			.andExpect(header().longValue("Content-Length", 10))
			.andExpect(content().bytes(Arrays.copyOfRange(audio, 0, 10)));
		range(id, "bytes=20-").andExpect(status().isPartialContent())
			.andExpect(header().string("Content-Range", "bytes 20-" + (audio.length - 1) + "/" + audio.length))
			.andExpect(content().bytes(Arrays.copyOfRange(audio, 20, audio.length)));
		range(id, "bytes=-5").andExpect(status().isPartialContent())
			.andExpect(content().bytes(Arrays.copyOfRange(audio, audio.length - 5, audio.length)));
		range(id, "bytes=10-999999").andExpect(status().isPartialContent())
			.andExpect(header().string("Content-Range", "bytes 10-" + (audio.length - 1) + "/" + audio.length));
		range(id, "bytes=" + audio.length + "-").andExpect(status().isRequestedRangeNotSatisfiable())
			.andExpect(header().string("Content-Range", "bytes */" + audio.length))
			.andExpect(jsonPath("$.code").value("range_not_satisfiable"));
		range(id, "bytes=0-1,4-5").andExpect(status().isOk()).andExpect(content().bytes(audio));
		range(id, "bytes=9-3").andExpect(status().isOk()).andExpect(content().bytes(audio));

		// 처음부터 읽은 요청만 감사 기록에 남는다.
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM audit_events WHERE actor_user_id = :u AND operation = 'study_room.recording.play'
				""").param("u", this.ownerId).query(Long.class).single()).isEqualTo(4);

		// 제목 바꾸기
		this.owner.send(patch(BASE + "/recordings/" + id, "\"1\"", "{\"title\":\"알고리즘 9주차\"}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.title").value("알고리즘 9주차"))
			.andExpect(jsonPath("$.version").value(2))
			.andExpect(header().string("ETag", "\"2\""));
		this.owner.send(patch(BASE + "/recordings/" + id, "\"1\"", "{\"title\":\"x\"}")).andExpect(status().isConflict());
		this.owner.send(patch(BASE + "/recordings/" + id, "\"2\"", "{\"title\":\" \"}")).andExpect(status().isBadRequest());
		this.owner.get(BASE + "/recordings/" + id + "/content")
			.andExpect(header().string("Content-Disposition", containsString(".webm")));
		this.owner.get(BASE + "/recordings/" + id).andExpect(status().isOk()).andExpect(header().string("ETag", "\"2\""));
	}

	@Test
	void importedAudioKeepsItsFileNameAndRecordingsAreListedNewestFirst() throws Exception {
		Instant older = Instant.parse("2026-09-01T01:00:00Z");
		Instant newer = Instant.parse("2026-10-01T01:00:00Z");
		long a = id(record(this.owner, this.course, "수업.ogg", ogg(), "수업", "audio", "60000", older.toString(), null,
				null).andExpect(status().isCreated()).andExpect(jsonPath("$.fileName").value("수업.ogg"))
			.andExpect(jsonPath("$.mimeType").value("audio/ogg")));
		long b = id(record(this.owner, this.course, "tab.webm", webm(), "탭", "tab", "1000", newer.toString(), null, null)
			.andExpect(status().isCreated()));
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.ownerId);
		long c = id(record(this.owner, otherCourse, "m.webm", webm(), "다른 과목", "mic", "1000",
				Instant.parse("2026-11-01T01:00:00Z").toString(), null, null).andExpect(status().isCreated()));
		assertThat(ids(course(this.owner, this.course).get("recordings"))).containsExactly(b, a);
		assertThat(ids(json(this.owner.get(BASE + "/recordings").andExpect(status().isOk())).get("recordings")))
			.containsExactly(c, b, a);
		this.owner.get(BASE + "/recordings/" + a + "/content")
			.andExpect(header().string("Content-Disposition", containsString("ogg")));
	}

	@Test
	void recordingUploadsAreValidated() throws Exception {
		String at = Instant.now().toString();
		record(this.owner, this.course, "a.webm", webm(), "t", "phone", "10", at, null, null).andExpect(status().isBadRequest());
		record(this.owner, this.course, "a.webm", webm(), null, "mic", "10", at, null, null).andExpect(status().isBadRequest());
		record(this.owner, this.course, "a.webm", webm(), "t".repeat(201), "mic", "10", at, null, null)
			.andExpect(status().isBadRequest());
		record(this.owner, this.course, "a.webm", webm(), "t", "mic", "0", at, null, null).andExpect(status().isBadRequest());
		record(this.owner, this.course, "a.webm", webm(), "t", "mic", "12.5", at, null, null).andExpect(status().isBadRequest());
		record(this.owner, this.course, "a.webm", webm(), "t", "mic", "86400001", at, null, null)
			.andExpect(status().isBadRequest());
		record(this.owner, this.course, "a.webm", webm(), "t", "mic", "10", Instant.now().plus(1, ChronoUnit.HOURS).toString(),
				null, null).andExpect(status().isBadRequest());
		record(this.owner, this.course, "a.webm", webm(), "t", "mic", "10", "2026-11-13 14:05", null, null)
			.andExpect(status().isBadRequest());
		// 확장자, 내용 형식, 빈 파일
		record(this.owner, this.course, "a.pdf", webm(), "t", "mic", "10", at, null, null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("extension_not_allowed"));
		record(this.owner, this.course, "a.mp3", ogg(), "t", "audio", "10", at, null, null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("content_mismatch"));
		record(this.owner, this.course, "a.webm", new byte[0], "t", "mic", "10", at, null, null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("empty_file"));
		// 구성원이 아닌 과목, 다른 과목의 폴더, 남의 폴더
		record(this.owner, this.data.course(), "a.webm", webm(), "t", "mic", "10", at, null, null)
			.andExpect(status().isNotFound());
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.ownerId);
		long elsewhere = folder(this.owner, otherCourse, "다른");
		record(this.owner, this.course, "a.webm", webm(), "t", "mic", "10", at, elsewhere, null)
			.andExpect(status().isNotFound());
		long theirs = folder(this.other, this.course, "남의 폴더");
		record(this.owner, this.course, "a.webm", webm(), "t", "mic", "10", at, theirs, null).andExpect(status().isNotFound());
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM study_room_recordings WHERE owner_user_id = :o")
			.param("o", this.ownerId)
			.query(Long.class)
			.single()).isZero();

		// 오디오 형식마다 내용으로 판정한 MIME
		assertThat(mime("a.mp3", concat(new byte[] { 'I', 'D', '3', 4, 0, 0 }, random()))).isEqualTo("audio/mpeg");
		assertThat(mime("b.mp3", concat(new byte[] { (byte) 0xFF, (byte) 0xFB, (byte) 0x90, 0x64 }, random())))
			.isEqualTo("audio/mpeg");
		assertThat(mime("c.m4a", isoMedia("M4A "))).isEqualTo("audio/mp4");
		assertThat(mime("d.mp4", isoMedia("isom"))).isEqualTo("audio/mp4");
		assertThat(mime("e.wav", concat("RIFF\0\0\0\0WAVEfmt ".getBytes(StandardCharsets.US_ASCII), random())))
			.isEqualTo("audio/wav");
		assertThat(mime("f.aac", concat(new byte[] { (byte) 0xFF, (byte) 0xF1, 0x50, (byte) 0x80 }, random())))
			.isEqualTo("audio/aac");
		assertThat(mime("g.flac", concat("fLaC".getBytes(StandardCharsets.US_ASCII), random()))).isEqualTo("audio/flac");
	}

	@Test
	void recordingUploadsAreIdempotentPerKey() throws Exception {
		byte[] audio = webm();
		String at = "2026-11-12T05:00:00Z";
		long first = id(record(this.owner, this.course, "r.webm", audio, "녹음", "mic", "1000", at, null, "import-rec-1")
			.andExpect(status().isCreated()));
		long again = id(record(this.owner, this.course, "r.webm", audio, "녹음", "mic", "1000", at, null, "import-rec-1")
			.andExpect(status().isCreated()));
		assertThat(again).isEqualTo(first);
		record(this.owner, this.course, "r.webm", audio, "다른 제목", "mic", "1000", at, null, "import-rec-1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "1000", at, null, "import-rec-1")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		assertThat(course(this.owner, this.course).get("recordings")).hasSize(1);
		assertThat(course(this.owner, this.course).get("usage").get("recordingCount").asInt()).isEqualTo(1);
	}

	@Test
	void quotaCountAndFileSizeLimitsAreEnforced() throws Exception {
		String at = Instant.now().toString();
		long id = id(record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", at, null, null)
			.andExpect(status().isCreated()));
		this.jdbc.sql("UPDATE study_room_quotas SET used_bytes = :b WHERE owner_user_id = :o")
			.param("b", 2L * 1024 * 1024 * 1024 - 10)
			.param("o", this.ownerId)
			.update();
		record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", at, null, null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("quota_exceeded"));
		this.jdbc.sql("UPDATE study_room_quotas SET used_bytes = 0, recording_count = 500 WHERE owner_user_id = :o")
			.param("o", this.ownerId)
			.update();
		record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", at, null, null)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("too_many_recordings"));
		// 지우면 사용량이 준다.
		this.owner.send(delete(BASE + "/recordings/" + id, "\"1\"")).andExpect(status().isNoContent());
		assertThat(course(this.owner, this.course).get("usage").get("recordingCount").asInt()).isEqualTo(499);

		this.jdbc.sql("UPDATE upload_policies SET max_file_bytes = 20 WHERE policy_code = 'study_recording'").update();
		try {
			record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", at, null, null)
				.andExpect(status().isContentTooLarge())
				.andExpect(jsonPath("$.code").value("too_large"));
		}
		finally {
			this.jdbc.sql("UPDATE upload_policies SET max_file_bytes = 52428800 WHERE policy_code = 'study_recording'")
				.update();
		}
	}

	@Test
	void deletingARecordingReleasesItsFileAndPlacement() throws Exception {
		long folderId = folder(this.owner, this.course, "강의");
		long id = id(record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", Instant.now().toString(), folderId,
				null).andExpect(status().isCreated()));
		long fileId = this.jdbc.sql("SELECT file_id FROM study_room_recordings WHERE study_room_recording_id = :id")
			.param("id", id)
			.query(Long.class)
			.single();
		this.owner.send(delete(BASE + "/recordings/" + id, null)).andExpect(status().isPreconditionRequired());
		this.owner.send(delete(BASE + "/recordings/" + id, "\"2\"")).andExpect(status().isConflict());
		this.owner.send(delete(BASE + "/recordings/" + id, "\"1\"")).andExpect(status().isNoContent());

		assertThat(this.jdbc.sql("SELECT state FROM files WHERE file_id = :id").param("id", fileId).query(String.class).single())
			.isEqualTo("deleted");
		assertThat(this.jdbc.sql("SELECT upload_state FROM upload_sessions WHERE file_id = :id")
			.param("id", fileId)
			.query(String.class)
			.single()).isEqualTo("deleted");
		JsonNode view = course(this.owner, this.course);
		assertThat(view.get("recordings")).isEmpty();
		assertThat(view.get("placements")).isEmpty();
		assertThat(view.get("usage").get("usedBytes").asLong()).isZero();
		this.owner.get(BASE + "/recordings/" + id).andExpect(status().isNotFound());
		this.owner.get(BASE + "/recordings/" + id + "/content").andExpect(status().isNotFound());
		// 폴더는 그대로다.
		assertThat(ids(view.get("folders"))).containsExactly(folderId);
	}

	@Test
	void scanStateDecidesWhetherARecordingCanBePlayed() throws Exception {
		long id = id(record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", Instant.now().toString(), null,
				null).andExpect(status().isCreated()));
		scanState(id, "quarantined", "pending");
		this.owner.get(BASE + "/recordings/" + id).andExpect(jsonPath("$.state").value("scanning"));
		this.owner.get(BASE + "/recordings/" + id + "/content")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_ready"));
		scanState(id, "rejected", "infected");
		this.owner.get(BASE + "/recordings/" + id).andExpect(jsonPath("$.state").value("rejected"));
		this.owner.get(BASE + "/recordings/" + id + "/content")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("content_rejected"));
		// 거부한 녹음도 지울 수 있다.
		this.owner.send(delete(BASE + "/recordings/" + id, "\"1\"")).andExpect(status().isNoContent());
	}

	@Test
	void recordingsAreNeverReachableThroughCoursePathsOrByStaff() throws Exception {
		long professorId = this.data.user("PROFESSOR");
		this.data.instructor(this.course, professorId);
		ApiClient professor = new ApiClient(this.mvc, this.data.session(professorId));
		long id = id(record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", Instant.now().toString(), null,
				null).andExpect(status().isCreated()));
		long fileId = this.jdbc.sql("SELECT file_id FROM study_room_recordings WHERE study_room_recording_id = :id")
			.param("id", id)
			.query(Long.class)
			.single();
		assertThat(this.jdbc.sql("SELECT course_id FROM files WHERE file_id = :id").param("id", fileId).query().singleRow())
			.containsEntry("course_id", null);
		for (ApiClient client : List.of(professor, this.owner)) {
			client.get("/api/courses/" + this.course + "/files/" + fileId + "/content").andExpect(status().isNotFound());
			client.postJson("/api/courses/" + this.course + "/files/" + fileId + "/preview-sessions", "")
				.andExpect(status().isNotFound());
			client.get("/api/courses/" + this.course + "/files/" + fileId + "/summary/overview")
				.andExpect(status().isNotFound());
		}
		professor.get(BASE + "/recordings/" + id).andExpect(status().isNotFound());
		professor.get(BASE + "/recordings/" + id + "/content").andExpect(status().isNotFound());
		assertThat(course(professor, this.course).get("recordings")).isEmpty();
		// 녹음 업로드는 일반 첨부로 쓸 수 없다(업로드는 기록과 함께 attached).
		assertThat(this.jdbc.sql("SELECT upload_state FROM upload_sessions WHERE file_id = :id")
			.param("id", fileId)
			.query(String.class)
			.single()).isEqualTo("attached");
	}

	@Test
	void anotherUserCanNeverSeeOrChangeFoldersRecordingsOrPlacements() throws Exception {
		long fileId = material(true);
		long folderId = folder(this.owner, this.course, "내 폴더");
		long recordingId = id(record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", Instant.now().toString(),
				folderId, null).andExpect(status().isCreated()));
		placeFile(fileId, "\"0\"", folderId).andExpect(status().isOk());
		long theirFolder = folder(this.other, this.course, "남의 폴더");

		JsonNode theirView = course(this.other, this.course);
		assertThat(ids(theirView.get("folders"))).containsExactly(theirFolder);
		assertThat(theirView.get("recordings")).isEmpty();
		assertThat(theirView.get("placements")).isEmpty();
		assertThat(json(this.other.get(BASE + "/recordings")).get("recordings")).isEmpty();

		this.other.send(patch(BASE + "/folders/" + folderId, "\"1\"", "{\"name\":\"뺏기\"}")).andExpect(status().isNotFound());
		this.other.send(delete(BASE + "/folders/" + folderId, "\"1\"")).andExpect(status().isNotFound());
		reorder(this.other, this.course, theirFolder, 1, folderId, 1).andExpect(status().isNotFound());
		this.other.get(BASE + "/recordings/" + recordingId).andExpect(status().isNotFound());
		this.other.get(BASE + "/recordings/" + recordingId + "/content").andExpect(status().isNotFound());
		this.other.send(patch(BASE + "/recordings/" + recordingId, "\"1\"", "{\"title\":\"x\"}"))
			.andExpect(status().isNotFound());
		this.other.send(delete(BASE + "/recordings/" + recordingId, "\"1\"")).andExpect(status().isNotFound());
		// 남의 녹음을 내 폴더에, 내 항목을 남의 폴더에
		this.other.send(put(BASE + "/placements/recordings/" + recordingId, "\"1\"", "{\"folderId\":" + theirFolder + "}"))
			.andExpect(status().isNotFound());
		this.other.send(put(BASE + "/placements/course-files/" + fileId, "\"1\"", "{\"folderId\":" + folderId + "}"))
			.andExpect(status().isNotFound());
		this.other.send(delete(BASE + "/placements/recordings/" + recordingId, "\"1\"")).andExpect(status().isNotFound());
		// 같은 강의자료를 자기 폴더에 두는 것은 따로다.
		this.other.send(put(BASE + "/placements/course-files/" + fileId, "\"0\"", "{\"folderId\":" + theirFolder + "}"))
			.andExpect(status().isOk());
		record(this.other, this.course, "r.webm", webm(), "t", "mic", "10", Instant.now().toString(), folderId, null)
			.andExpect(status().isNotFound());

		// 아무것도 바뀌지 않았다.
		JsonNode mine = course(this.owner, this.course);
		assertThat(mine.get("folders").get(0).get("name").asString()).isEqualTo("내 폴더");
		assertThat(mine.get("folders").get(0).get("version").asInt()).isEqualTo(1);
		assertThat(ids(mine.get("recordings"))).containsExactly(recordingId);
		assertThat(mine.get("placements")).hasSize(2);

		new ApiClient(this.mvc, null).get(BASE + "/courses/" + this.course).andExpect(status().isUnauthorized());
		this.mvc
			.perform(MockMvcRequestBuilders.post(BASE + "/courses/" + this.course + "/folders")
				.cookie(new Cookie("ASTRA_SESSION", this.data.session(this.ownerId)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"csrf\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void losingCourseAccessHidesMaterialPlacementsButKeepsOwnRecordings() throws Exception {
		long fileId = material(true);
		long folderId = folder(this.owner, this.course, "폴더");
		long recordingId = id(record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", Instant.now().toString(),
				folderId, null).andExpect(status().isCreated()));
		placeFile(fileId, "\"0\"", folderId).andExpect(status().isOk());

		this.jdbc.sql("DELETE FROM course_enrollments WHERE course_id = :c AND user_id = :u")
			.param("c", this.course)
			.param("u", this.ownerId)
			.update();

		JsonNode view = course(this.owner, this.course);
		assertThat(ids(view.get("folders"))).containsExactly(folderId);
		assertThat(ids(view.get("recordings"))).containsExactly(recordingId);
		assertThat(view.get("placements")).hasSize(1);
		assertThat(view.get("placements").get(0).get("kind").asString()).isEqualTo("recording");
		this.owner.get(BASE + "/recordings/" + recordingId + "/content").andExpect(status().isOk());
		// 새로 만들기는 구성원만 한다. 내 것을 고치고 지우는 일은 된다.
		createFolder(this.owner, this.course, "새 폴더").andExpect(status().isNotFound());
		record(this.owner, this.course, "r.webm", webm(), "t", "mic", "10", Instant.now().toString(), null, null)
			.andExpect(status().isNotFound());
		this.owner.send(patch(BASE + "/folders/" + folderId, "\"1\"", "{\"name\":\"지난 학기\"}")).andExpect(status().isOk());
		this.owner.send(delete(BASE + "/recordings/" + recordingId, "\"1\"")).andExpect(status().isNoContent());
	}

	@Test
	void recordingsCanOnlyBePlacedInFoldersOfTheirCourse() throws Exception {
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.ownerId);
		long here = folder(this.owner, this.course, "여기");
		long there = folder(this.owner, otherCourse, "저기");
		long recordingId = id(record(this.owner, this.course, "r.webm", webm(), "녹음", "mic", "10", Instant.now().toString(),
				null, null).andExpect(status().isCreated()));
		this.owner.send(put(BASE + "/placements/recordings/" + recordingId, "\"0\"", "{\"folderId\":" + there + "}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("course_mismatch"));
		this.owner.send(put(BASE + "/placements/recordings/" + recordingId, "\"0\"", "{\"folderId\":" + here + "}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("recording"));
		this.owner.send(put(BASE + "/placements/recordings/" + recordingId, "\"1\"", "{}")).andExpect(status().isBadRequest());
	}

	// ---------- helpers ----------

	private ResultActions createFolder(ApiClient client, long courseId, String name) throws Exception {
		return client.postJson(BASE + "/courses/" + courseId + "/folders", "{\"name\":\"" + name + "\"}");
	}

	private long folder(ApiClient client, long courseId, String name) throws Exception {
		return json(createFolder(client, courseId, name).andExpect(status().isCreated())).get("id").asLong();
	}

	private ResultActions reorder(ApiClient client, long courseId, long... idVersionPairs) throws Exception {
		List<String> entries = new ArrayList<>();
		for (int i = 0; i < idVersionPairs.length; i += 2) {
			entries.add("{\"id\":" + idVersionPairs[i] + ",\"version\":" + idVersionPairs[i + 1] + "}");
		}
		return client.send(put(BASE + "/courses/" + courseId + "/folder-order", null,
				"{\"folders\":[" + String.join(",", entries) + "]}"));
	}

	private ResultActions placeFile(long fileId, String ifMatch, long folderId) throws Exception {
		return this.owner.send(put(BASE + "/placements/course-files/" + fileId, ifMatch, "{\"folderId\":" + folderId + "}"));
	}

	private ResultActions range(long id, String range) throws Exception {
		return this.owner.send(MockMvcRequestBuilders.get(BASE + "/recordings/" + id + "/content").header("Range", range));
	}

	private ResultActions record(ApiClient client, long courseId, String fileName, byte[] content, String title,
			String source, String durationMs, String recordedAt, Long folderId, String key) throws Exception {
		var request = MockMvcRequestBuilders.multipart(BASE + "/courses/" + courseId + "/recordings")
			.file(new MockMultipartFile("file", fileName, "audio/webm", content))
			.param("source", source)
			.param("durationMs", durationMs)
			.param("recordedAt", recordedAt);
		if (title != null) {
			request.param("title", title);
		}
		if (folderId != null) {
			request.param("folderId", Long.toString(folderId));
		}
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return client.send(request);
	}

	// 업로드 요청 제한(테스트 설정 6회)에 걸리지 않도록 형식마다 새 사용자로 올린다.
	private String mime(String name, byte[] content) throws Exception {
		long userId = this.data.user("STUDENT");
		this.data.enroll(this.course, userId);
		ApiClient client = new ApiClient(this.mvc, this.data.session(userId));
		return json(record(client, this.course, name, content, "형식", "audio", "10", Instant.now().toString(), null, null)
			.andExpect(status().isCreated())).get("mimeType").asString();
	}

	private JsonNode course(ApiClient client, long courseId) throws Exception {
		return json(client.get(BASE + "/courses/" + courseId).andExpect(status().isOk()));
	}

	/** 공개 여부를 정한 강의자료 활동에 PDF 하나를 첨부한다. */
	private long material(boolean published) {
		long fileId = this.data.pdfFile(("%PDF-1.4 " + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII), "clean");
		this.data.activity(this.course, "lecture_material", published, null, this.data.attachmentGroup(fileId));
		return fileId;
	}

	private void scanState(long recordingId, String blobState, String fileState) {
		this.jdbc.sql("""
				UPDATE file_blobs b JOIN files f ON f.file_blob_id = b.file_blob_id
				  JOIN study_room_recordings r ON r.file_id = f.file_id
				   SET b.blob_state = :blob, f.scan_state = :file
				 WHERE r.study_room_recording_id = :id
				""").param("blob", blobState).param("file", fileState).param("id", recordingId).update();
	}

	private static List<Integer> concurrently(List<Callable<Integer>> calls) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(calls.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Integer>> futures = new ArrayList<>();
			for (Callable<Integer> call : calls) {
				futures.add(pool.submit(() -> {
					start.await();
					return call.call();
				}));
			}
			start.countDown();
			List<Integer> statuses = new ArrayList<>();
			for (Future<Integer> future : futures) {
				statuses.add(future.get(30, TimeUnit.SECONDS));
			}
			return statuses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private static MockHttpServletRequestBuilder patch(String path, String ifMatch, String json) {
		return withIfMatch(MockMvcRequestBuilders.patch(path).contentType(MediaType.APPLICATION_JSON).content(json), ifMatch);
	}

	private static MockHttpServletRequestBuilder put(String path, String ifMatch, String json) {
		return withIfMatch(MockMvcRequestBuilders.put(path).contentType(MediaType.APPLICATION_JSON).content(json), ifMatch);
	}

	private static MockHttpServletRequestBuilder post(String path, String json) {
		return MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private static MockHttpServletRequestBuilder delete(String path, String ifMatch) {
		return withIfMatch(MockMvcRequestBuilders.delete(path), ifMatch);
	}

	private static MockHttpServletRequestBuilder withIfMatch(MockHttpServletRequestBuilder request, String ifMatch) {
		return ifMatch == null ? request : request.header("If-Match", ifMatch);
	}

	private static JsonNode json(ResultActions result) throws Exception {
		return ApiClient.json(result);
	}

	private static long id(ResultActions result) throws Exception {
		return json(result).get("id").asLong();
	}

	private static List<Long> ids(JsonNode array) {
		List<Long> ids = new ArrayList<>();
		array.forEach((n) -> ids.add(n.get("id").asLong()));
		return ids;
	}

	private static List<Integer> versions(JsonNode array) {
		List<Integer> versions = new ArrayList<>();
		array.forEach((n) -> versions.add(n.get("version").asInt()));
		return versions;
	}

	/** 내용이 겹치지 않는 WebM(EBML DocType webm) 오디오. */
	static byte[] webm() {
		return concat(new byte[] { 0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, (byte) 0x9F, 0x42, (byte) 0x86, (byte) 0x81, 0x01,
				0x42, (byte) 0x82, (byte) 0x84, 'w', 'e', 'b', 'm' }, random());
	}

	private static byte[] ogg() {
		return concat("OggS".getBytes(StandardCharsets.US_ASCII), random());
	}

	private static byte[] isoMedia(String brand) {
		byte[] head = new byte[] { 0, 0, 0, 0x18, 'f', 't', 'y', 'p', 0, 0, 0, 0, 0, 0, 0, 0 };
		byte[] b = brand.getBytes(StandardCharsets.US_ASCII);
		System.arraycopy(b, 0, head, 8, 4);
		return concat(head, random());
	}

	private static byte[] random() {
		return (" audio " + UUID.randomUUID() + UUID.randomUUID()).getBytes(StandardCharsets.US_ASCII);
	}

	private static byte[] concat(byte[] a, byte[] b) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(a);
		out.writeBytes(b);
		return out.toByteArray();
	}

}
