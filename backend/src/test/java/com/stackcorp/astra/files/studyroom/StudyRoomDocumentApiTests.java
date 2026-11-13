package com.stackcorp.astra.files.studyroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

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
 * 학습실의 내 문서(ADR-0040 14번). 학생이 자기 PDF를 올리면 녹음처럼 소유자 한 사람의 것으로 서버에 남는다. 과목에 게시되지 않고, 남의
 * 문서는 없는 ID와 같은 404이며, 용량은 녹음과 함께 센다. 내용은 악성코드 검사를 마친 뒤에만 나간다. 업로드 요청 제한(테스트 설정 6회)
 * 때문에 업로드가 많은 시험은 새 구성원으로 나눠 올린다.
 */
class StudyRoomDocumentApiTests extends IntegrationTest {

	static final String BASE = "/api/me/study-room";

	static final long QUOTA = 2L * 1024 * 1024 * 1024;

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	long ownerId;

	ApiClient owner;

	ApiClient other;

	@BeforeEach
	void users() {
		this.course = this.data.course();
		this.ownerId = member();
		this.owner = client(this.ownerId);
		this.other = client(member());
	}

	@Test
	void aStudentUploadsAPdfAndReadsItBackOnlyWhenReady() throws Exception {
		byte[] pdf = pdf();
		JsonNode created = json(upload(this.owner, this.course, "3주차 정리.pdf", pdf, null, null, null)
			.andExpect(status().isCreated())
			.andExpect(header().string("ETag", "\"1\"")));
		long id = created.get("documentId").asLong();
		assertThat(created.get("courseId").asLong()).isEqualTo(this.course);
		assertThat(created.get("title").asString()).isEqualTo("3주차 정리");
		assertThat(created.get("fileName").asString()).isEqualTo("3주차 정리.pdf");
		assertThat(created.get("sizeBytes").asLong()).isEqualTo(pdf.length);
		assertThat(created.get("state").asString()).isEqualTo("ready");
		assertThat(created.get("folderId").isNull()).isTrue();
		assertThat(created.get("version").asInt()).isEqualTo(1);
		assertThat(created.get("updatedAt").asString()).isEqualTo(created.get("createdAt").asString());

		// 과목 없이 저장하고 첨부하지 않는다. 강의자료로 게시되지 않는다.
		assertThat(this.jdbc.sql("""
				SELECT COUNT(*) FROM study_room_documents d JOIN files f ON f.file_id = d.file_id
				 WHERE d.study_room_document_id = :id AND f.course_id IS NULL AND f.source_menu_code = 'study_document'
				""").param("id", id).query(Long.class).single()).isEqualTo(1);

		this.owner.get(BASE + "/documents/" + id + "/content")
			.andExpect(status().isOk())
			.andExpect(header().string("Content-Type", "application/pdf"))
			.andExpect(header().string("Content-Disposition", containsString("attachment")))
			.andExpect(header().string("Content-Disposition", containsString(".pdf")))
			.andExpect(content().bytes(pdf));

		scanState(id, "quarantined", "pending");
		this.owner.get(BASE + "/documents/" + id).andExpect(jsonPath("$.state").value("scanning"));
		this.owner.get(BASE + "/documents/" + id + "/content")
			.andExpect(status().isConflict())
			.andExpect(header().string("Retry-After", "10"))
			.andExpect(jsonPath("$.code").value("file_scanning"));
		scanState(id, "rejected", "infected");
		this.owner.get(BASE + "/documents/" + id).andExpect(jsonPath("$.state").value("rejected"));
		this.owner.get(BASE + "/documents/" + id + "/content")
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("content_rejected"));
		scanState(id, "clean", "clean");
		this.owner.get(BASE + "/documents/" + id + "/content").andExpect(status().isOk());
	}

	@Test
	void uploadsAreValidated() throws Exception {
		// 제목을 보내면 그대로, 300자까지
		JsonNode titled = json(upload(this.owner, this.course, "a.PDF", pdf(), "  기말 범위  ", null, null)
			.andExpect(status().isCreated()));
		assertThat(titled.get("title").asString()).isEqualTo("기말 범위");
		upload(this.owner, this.course, "a.pdf", pdf(), "x".repeat(301), null, null).andExpect(status().isBadRequest());
		// PDF가 아니면 저장하지 않는다.
		upload(this.owner, this.course, "notes.txt", "hello".getBytes(StandardCharsets.UTF_8), null, null, null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("extension_not_allowed"));
		upload(this.owner, this.course, "fake.pdf", ("not a pdf " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8),
				null, null, null)
			.andExpect(status().isUnprocessableContent())
			.andExpect(jsonPath("$.code").value("content_mismatch"));
		// 구성원이 아닌 과목
		upload(this.owner, this.data.course(), "a.pdf", pdf(), null, null, null).andExpect(status().isNotFound());
		assertThat(course(this.owner, this.course).get("documents")).hasSize(1);
	}

	@Test
	void uploadsAreIdempotentPerKeyAndAReplayAfterDeleteIsGone() throws Exception {
		byte[] pdf = pdf();
		long id = json(upload(this.owner, this.course, "a.pdf", pdf, "자료", null, "doc-key-1")
			.andExpect(status().isCreated())).get("documentId").asLong();
		// 같은 key는 header로도, 본문의 key로도 같은 문서다.
		var replay = MockMvcRequestBuilders.multipart(BASE + "/courses/" + this.course + "/documents")
			.file(new MockMultipartFile("file", "a.pdf", "application/pdf", pdf))
			.param("title", "자료")
			.param("key", "doc-key-1");
		this.owner.send(replay).andExpect(status().isCreated()).andExpect(jsonPath("$.documentId").value(id));
		upload(this.owner, this.course, "a.pdf", pdf, "다른 제목", null, "doc-key-1").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("idempotency_conflict"));
		assertThat(course(this.owner, this.course).get("documents")).hasSize(1);
		assertThat(usage(this.owner).get("documentCount").asInt()).isEqualTo(1);

		this.owner.send(MockMvcRequestBuilders.delete(BASE + "/documents/" + id).param("version", "1"))
			.andExpect(status().isNoContent());
		upload(this.owner, this.course, "a.pdf", pdf, "자료", null, "doc-key-1").andExpect(status().isGone())
			.andExpect(jsonPath("$.code").value("document_deleted"));
	}

	@Test
	void renameAndDeleteNeedTheCurrentVersion() throws Exception {
		byte[] pdf = pdf();
		long id = json(upload(this.owner, this.course, "원본.pdf", pdf, null, null, null).andExpect(status().isCreated()))
			.get("documentId")
			.asLong();
		long fileId = this.jdbc.sql("SELECT file_id FROM study_room_documents WHERE study_room_document_id = :id")
			.param("id", id)
			.query(Long.class)
			.single();

		this.owner.send(patch(BASE + "/documents/" + id, "{\"title\":\"새 이름\"}")).andExpect(status().isBadRequest());
		this.owner.send(patch(BASE + "/documents/" + id, "{\"title\":\" \",\"version\":1}"))
			.andExpect(status().isBadRequest());
		JsonNode renamed = json(this.owner.send(patch(BASE + "/documents/" + id, "{\"title\":\"새 이름\",\"version\":1}"))
			.andExpect(status().isOk()));
		assertThat(renamed.get("title").asString()).isEqualTo("새 이름");
		assertThat(renamed.get("version").asInt()).isEqualTo(2);
		assertThat(renamed.get("fileName").asString()).isEqualTo("원본.pdf");
		this.owner.send(patch(BASE + "/documents/" + id, "{\"title\":\"또\",\"version\":1}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));

		this.owner.send(MockMvcRequestBuilders.delete(BASE + "/documents/" + id))
			.andExpect(status().isPreconditionRequired());
		this.owner.send(MockMvcRequestBuilders.delete(BASE + "/documents/" + id).param("version", "1"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		this.owner.send(MockMvcRequestBuilders.delete(BASE + "/documents/" + id).param("version", "2"))
			.andExpect(status().isNoContent());
		this.owner.get(BASE + "/documents/" + id).andExpect(status().isNotFound());
		this.owner.get(BASE + "/documents/" + id + "/content").andExpect(status().isNotFound());

		// 파일 참조를 놓고, 지운 파일 행에 사용자가 지은 이름을 남기지 않는다.
		assertThat(this.jdbc.sql("SELECT state, original_file_name FROM files WHERE file_id = :id")
			.param("id", fileId)
			.query((rs, n) -> rs.getString(1) + "/" + rs.getString(2))
			.single()).isEqualTo("deleted/document.pdf");
		JsonNode usage = usage(this.owner);
		assertThat(usage.get("usedBytes").asLong()).isZero();
		assertThat(usage.get("documentCount").asInt()).isZero();
	}

	@Test
	void anotherUserCanNeverSeeOrChangeADocument() throws Exception {
		long folderId = folder(this.owner, "내 폴더");
		long id = json(upload(this.owner, this.course, "a.pdf", pdf(), null, folderId, null)
			.andExpect(status().isCreated())).get("documentId").asLong();
		long theirFolder = folder(this.other, "남의 폴더");

		assertThat(course(this.other, this.course).get("documents")).isEmpty();
		this.other.get(BASE + "/documents/" + id).andExpect(status().isNotFound());
		this.other.get(BASE + "/documents/" + id + "/content").andExpect(status().isNotFound());
		this.other.send(patch(BASE + "/documents/" + id, "{\"title\":\"x\",\"version\":1}"))
			.andExpect(status().isNotFound());
		this.other.send(MockMvcRequestBuilders.delete(BASE + "/documents/" + id).param("version", "1"))
			.andExpect(status().isNotFound());
		this.other.send(put(BASE + "/placements/documents/" + id, "\"1\"", "{\"folderId\":" + theirFolder + "}"))
			.andExpect(status().isNotFound());
		this.other.send(MockMvcRequestBuilders.delete(BASE + "/placements/documents/" + id).header("If-Match", "\"1\""))
			.andExpect(status().isNotFound());
		// 남의 폴더에 올리기
		upload(this.owner, this.course, "b.pdf", pdf(), null, theirFolder, null).andExpect(status().isNotFound());
		// 다른 과목의 내 폴더도 받지 않는다.
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.ownerId);
		upload(this.owner, otherCourse, "c.pdf", pdf(), null, folderId, null).andExpect(status().isNotFound());

		this.owner.get(BASE + "/documents/" + id)
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.folderId").value(folderId))
			.andExpect(jsonPath("$.title").value("a"));
		assertThat(course(this.owner, otherCourse).get("documents")).isEmpty();
	}

	@Test
	void documentsArePlacedLikeRecordingsAndListedNewestFirstWithoutChangingRecordings() throws Exception {
		long folderA = folder(this.owner, "A");
		long folderB = folder(this.owner, "B");
		long first = json(upload(this.owner, this.course, "first.pdf", pdf(), null, null, null)
			.andExpect(status().isCreated())).get("documentId").asLong();
		long second = json(upload(this.owner, this.course, "second.pdf", pdf(), null, folderA, null)
			.andExpect(status().isCreated())).get("documentId").asLong();
		long recording = json(record(this.owner, folderB).andExpect(status().isCreated())).get("id").asLong();

		JsonNode view = course(this.owner, this.course);
		assertThat(documentIds(view.get("documents"))).containsExactly(second, first);
		assertThat(view.get("recordings")).hasSize(1);
		assertThat(view.get("recordings").get(0).get("id").asLong()).isEqualTo(recording);
		assertThat(view.get("recordings").get(0).has("documentId")).isFalse();
		assertThat(placements(view)).containsExactlyInAnyOrder("document:" + second + "@" + folderA,
				"recording:" + recording + "@" + folderB);

		// 맨 위의 문서를 폴더에 두고, 옮기고, 맨 위로 돌려보낸다(녹음과 같은 If-Match 규칙).
		this.owner.send(put(BASE + "/placements/documents/" + first, "\"1\"", "{\"folderId\":" + folderA + "}"))
			.andExpect(status().isConflict());
		this.owner.send(put(BASE + "/placements/documents/" + first, "\"0\"", "{\"folderId\":" + folderA + "}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.kind").value("document"))
			.andExpect(jsonPath("$.itemId").value(first))
			.andExpect(jsonPath("$.version").value(1));
		this.owner.send(put(BASE + "/placements/documents/" + first, "\"1\"", "{\"folderId\":" + folderB + "}"))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.version").value(2));
		this.owner.get(BASE + "/documents/" + first).andExpect(jsonPath("$.folderId").value(folderB));
		this.owner.send(MockMvcRequestBuilders.delete(BASE + "/placements/documents/" + first).header("If-Match", "\"2\""))
			.andExpect(status().isNoContent());
		this.owner.get(BASE + "/documents/" + first).andExpect(jsonPath("$.folderId").isEmpty());

		// 다른 과목의 폴더에는 두지 못한다.
		long otherCourse = this.data.course();
		this.data.enroll(otherCourse, this.ownerId);
		long elsewhere = folder(this.owner, otherCourse, "다른 과목");
		this.owner.send(put(BASE + "/placements/documents/" + first, "\"0\"", "{\"folderId\":" + elsewhere + "}"))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("course_mismatch"));

		// 폴더를 지우면 문서는 남고 맨 위로 간다.
		this.owner.send(MockMvcRequestBuilders.delete(BASE + "/folders/" + folderA).header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());
		this.owner.get(BASE + "/documents/" + second).andExpect(jsonPath("$.folderId").isEmpty());

		// 녹음 쪽은 그대로다.
		JsonNode recordings = json(this.owner.get(BASE + "/recordings").andExpect(status().isOk()));
		assertThat(recordings.get("recordings")).hasSize(1);
		assertThat(recordings.get("usage").get("recordingCount").asInt()).isEqualTo(1);
		assertThat(recordings.get("usage").get("documentCount").asInt()).isEqualTo(2);
	}

	@Test
	void documentsAndRecordingsShareOneQuota() throws Exception {
		// 문서가 용량을 채우면 녹음을 받지 않는다.
		long fullOfDocuments = member();
		ApiClient documents = client(fullOfDocuments);
		byte[] pdf = pdf();
		usedBytes(fullOfDocuments, QUOTA - pdf.length);
		upload(documents, this.course, "last.pdf", pdf, null, null, null).andExpect(status().isCreated());
		JsonNode usage = usage(documents);
		assertThat(usage.get("usedBytes").asLong()).isEqualTo(QUOTA);
		assertThat(usage.get("quotaBytes").asLong()).isEqualTo(QUOTA);
		assertThat(usage.get("documentCount").asInt()).isEqualTo(1);
		assertThat(usage.get("maxDocuments").asInt()).isEqualTo(500);
		assertThat(usage.get("maxDocumentFileBytes").asLong()).isEqualTo(52428800);
		record(documents, null).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("quota_exceeded"));
		upload(documents, this.course, "more.pdf", pdf(), null, null, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("quota_exceeded"));

		// 녹음이 용량을 채우면 문서를 받지 않는다.
		long fullOfRecordings = member();
		ApiClient recordings = client(fullOfRecordings);
		byte[] audio = StudyRoomApiTests.webm();
		usedBytes(fullOfRecordings, QUOTA - audio.length);
		record(recordings, audio, null).andExpect(status().isCreated());
		assertThat(usage(recordings).get("usedBytes").asLong()).isEqualTo(QUOTA);
		upload(recordings, this.course, "a.pdf", pdf(), null, null, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("quota_exceeded"));

		// 문서 수 상한은 녹음 수와 따로다.
		long many = member();
		this.jdbc.sql("INSERT INTO study_room_quotas (owner_user_id, document_count) VALUES (:o, 500)")
			.param("o", many)
			.update();
		upload(client(many), this.course, "a.pdf", pdf(), null, null, null).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("too_many_documents"));
		record(client(many), null).andExpect(status().isCreated());
	}

	private ResultActions upload(ApiClient client, long courseId, String fileName, byte[] content, String title,
			Long folderId, String key) throws Exception {
		var request = MockMvcRequestBuilders.multipart(BASE + "/courses/" + courseId + "/documents")
			.file(new MockMultipartFile("file", fileName, "application/pdf", content));
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

	private ResultActions record(ApiClient client, Long folderId) throws Exception {
		return record(client, StudyRoomApiTests.webm(), folderId);
	}

	private ResultActions record(ApiClient client, byte[] audio, Long folderId) throws Exception {
		var request = MockMvcRequestBuilders.multipart(BASE + "/courses/" + this.course + "/recordings")
			.file(new MockMultipartFile("file", "r.webm", "audio/webm", audio))
			.param("title", "녹음")
			.param("source", "mic")
			.param("durationMs", "10")
			.param("recordedAt", Instant.now().toString());
		if (folderId != null) {
			request.param("folderId", Long.toString(folderId));
		}
		return client.send(request);
	}

	private void usedBytes(long userId, long bytes) {
		this.jdbc.sql("INSERT INTO study_room_quotas (owner_user_id, used_bytes) VALUES (:o, :b)")
			.param("o", userId)
			.param("b", bytes)
			.update();
	}

	private void scanState(long documentId, String blobState, String fileState) {
		this.jdbc.sql("""
				UPDATE file_blobs b JOIN files f ON f.file_blob_id = b.file_blob_id
				  JOIN study_room_documents d ON d.file_id = f.file_id
				   SET b.blob_state = :blob, f.scan_state = :file
				 WHERE d.study_room_document_id = :id
				""").param("blob", blobState).param("file", fileState).param("id", documentId).update();
	}

	private long folder(ApiClient client, String name) throws Exception {
		return folder(client, this.course, name);
	}

	private long folder(ApiClient client, long courseId, String name) throws Exception {
		return json(client.postJson(BASE + "/courses/" + courseId + "/folders", "{\"name\":\"" + name + "\"}")
			.andExpect(status().isCreated())).get("id").asLong();
	}

	private JsonNode course(ApiClient client, long courseId) throws Exception {
		return json(client.get(BASE + "/courses/" + courseId).andExpect(status().isOk()));
	}

	private JsonNode usage(ApiClient client) throws Exception {
		return course(client, this.course).get("usage");
	}

	private long member() {
		long userId = this.data.user("STUDENT");
		this.data.enroll(this.course, userId);
		return userId;
	}

	private ApiClient client(long userId) {
		return new ApiClient(this.mvc, this.data.session(userId));
	}

	private static List<Long> documentIds(JsonNode array) {
		List<Long> ids = new ArrayList<>();
		array.forEach((n) -> ids.add(n.get("documentId").asLong()));
		return ids;
	}

	private static List<String> placements(JsonNode view) {
		List<String> placements = new ArrayList<>();
		view.get("placements")
			.forEach((p) -> placements.add(
					p.get("kind").asString() + ":" + p.get("itemId").asLong() + "@" + p.get("folderId").asLong()));
		return placements;
	}

	private static MockHttpServletRequestBuilder patch(String path, String json) {
		return MockMvcRequestBuilders.patch(path).contentType(MediaType.APPLICATION_JSON).content(json);
	}

	private static MockHttpServletRequestBuilder put(String path, String ifMatch, String json) {
		return MockMvcRequestBuilders.put(path)
			.contentType(MediaType.APPLICATION_JSON)
			.content(json)
			.header("If-Match", ifMatch);
	}

	private static JsonNode json(ResultActions result) throws Exception {
		return ApiClient.json(result);
	}

	/** 내용이 겹치지 않는 PDF. */
	static byte[] pdf() {
		return ("%PDF-1.4\n%" + UUID.randomUUID() + UUID.randomUUID() + "\n%%EOF").getBytes(StandardCharsets.US_ASCII);
	}

}
