package com.stackcorp.astra.files.filebox;

import static com.stackcorp.astra.files.filebox.FileboxCalls.command;
import static com.stackcorp.astra.files.filebox.FileboxCalls.file;
import static com.stackcorp.astra.files.filebox.FileboxCalls.folder;
import static com.stackcorp.astra.files.filebox.FileboxCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 파일함 파일을 과목 업로드로 다시 쓰기(EP-0126, POST /api/uploads/from-filebox). 대상 용도·과목의 업로드 권한, 정책, 검사 상태를
 * 다시 보고, 만든 업로드는 일반 첨부 경로로 첨부한다. 첨부는 파일함과 따로 참조하므로 파일함에서 영구 삭제해도 남는다(FILES-06).
 */
class FileboxReuseTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	JdbcClient jdbc;

	long course;

	ApiClient professor;

	ApiClient student;

	ApiClient outsider;

	@BeforeEach
	void course() {
		this.course = this.data.course();
		long prof = this.data.user("PROFESSOR");
		long stu = this.data.user("STUDENT");
		this.data.instructor(this.course, prof);
		this.data.enroll(this.course, stu);
		this.professor = new ApiClient(this.mvc, this.data.session(prof));
		this.student = new ApiClient(this.mvc, this.data.session(stu));
		this.outsider = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	@Test
	void reusesAFileboxFileAsACleanUploadThatAttachesThroughTheNormalPath() throws Exception {
		byte[] bytes = text();
		long item = file(this.professor, null, "9주차 자료.txt", bytes);

		JsonNode created = ApiClient.json(reuse(this.professor, "lecture_material", null, item)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.uploads[0].itemId").value(item))
			.andExpect(jsonPath("$.uploads[0].state").value("clean"))
			.andExpect(jsonPath("$.uploads[0].name").value("9주차 자료.txt")));
		long uploadId = created.get("uploads").get(0).get("uploadId").asLong();
		this.professor.get("/api/uploads/" + uploadId).andExpect(jsonPath("$.state").value("clean"));
		// 같은 내용 객체를 함께 쓴다. 새 파일 행은 파일함 항목을 출처로 남긴다.
		assertThat(this.jdbc.sql("""
				SELECT COUNT(DISTINCT f.file_blob_id) FROM files f
				 WHERE f.file_id IN ((SELECT file_id FROM filebox_items WHERE filebox_item_id = :item),
				                     (SELECT file_id FROM upload_sessions WHERE upload_id = :upload))
				""").param("item", item).param("upload", uploadId).query(Integer.class).single()).isOne();

		long material = createMaterial(uploadId);
		JsonNode detail = ApiClient.json(this.student.get("/api/courses/" + this.course + "/activities/" + material));
		long fileId = detail.get("files").get(0).get("fileId").asLong();
		this.student.get("/api/courses/" + this.course + "/files/" + fileId + "/content")
			.andExpect(status().isOk())
			.andExpect(content().bytes(bytes));

		JsonNode refs = ApiClient.json(this.professor.get("/api/filebox/items/" + item + "/references").andExpect(status().isOk()));
		assertThat(refs.get("references")).hasSize(1);
		assertThat(refs.get("references").get(0).get("courseId").asLong()).isEqualTo(this.course);
		assertThat(refs.get("references").get(0).get("courseName").asString()).isEqualTo("Database");
		assertThat(refs.get("references").get(0).get("purpose").asString()).isEqualTo("lecture_material");
		this.student.get("/api/filebox/items/" + item + "/references").andExpect(status().isNotFound());

		// 파일함에서 휴지통에 넣고 영구 삭제해도 첨부한 자료의 파일은 그대로 읽힌다(N-08).
		command(this.professor, "trash", item).andExpect(status().isOk());
		this.professor.get("/api/filebox/items/" + item + "/references").andExpect(jsonPath("$.references.length()").value(1));
		command(this.professor, "purge", item).andExpect(status().isOk());
		this.student.get("/api/courses/" + this.course + "/files/" + fileId + "/content")
			.andExpect(status().isOk())
			.andExpect(content().bytes(bytes));
		this.professor.get("/api/filebox/items/" + item + "/references").andExpect(status().isNotFound());
	}

	@Test
	void listsOnlyReferencesTheCallerCanStillSee() throws Exception {
		long item = file(this.professor, null, "slides.txt", text());
		long uploadId = ApiClient.json(reuse(this.professor, "lecture_material", null, item).andExpect(status().isCreated()))
			.get("uploads")
			.get(0)
			.get("uploadId")
			.asLong();
		long material = createMaterial(uploadId);
		this.professor.get("/api/filebox/items/" + item + "/references").andExpect(jsonPath("$.references.length()").value(1));

		this.professor
			.send(MockMvcRequestBuilders.delete("/api/courses/" + this.course + "/materials/" + material)
				.header("If-Match", "\"1\""))
			.andExpect(status().isNoContent());
		this.professor.get("/api/filebox/items/" + item + "/references").andExpect(jsonPath("$.references.length()").value(0));
	}

	@Test
	void respectsTheTargetPurposeRoleAndOwnership() throws Exception {
		long item = file(this.student, null, "report.txt", text());
		long folderId = folder(this.student, null, "폴더");
		long trashed = file(this.student, null, "old.txt", text());
		command(this.student, "trash", trashed).andExpect(status().isOk());

		// 수강생은 강의 자료로 올리지 못한다. 과목 밖 사람, 다른 과목, 남의 항목은 모두 404다.
		reuse(this.student, "lecture_material", null, item).andExpect(status().isNotFound());
		reuse(this.student, "assignment_submission", this.data.course(), item).andExpect(status().isNotFound());
		reuse(this.outsider, "assignment_submission", null, item).andExpect(status().isNotFound());
		reuse(this.professor, "lecture_material", null, item).andExpect(status().isNotFound());
		reuse(this.student, "filebox", null, item).andExpect(status().isBadRequest());
		reuse(this.student, "assignment_submission", null, folderId).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("folder_not_allowed"));
		reuse(this.student, "assignment_submission", null, trashed).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_active"));
		// 하나라도 쓸 수 없으면 아무 업로드도 만들지 않는다.
		reuse(this.student, "assignment_submission", null, item, trashed).andExpect(status().isConflict());
		assertThat(uploadsFromFilebox(item)).isZero();

		reuse(this.student, "assignment_submission", null, item)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.uploads[0].state").value("clean"));
		assertThat(uploadsFromFilebox(item)).isOne();
	}

	@Test
	void appliesTheTargetPolicyAndRefusesContentThatIsNotClean() throws Exception {
		long item = file(this.professor, null, "notes.txt", text());

		this.jdbc.sql("UPDATE upload_policies SET allowed_extensions = 'pdf' WHERE policy_code = 'lecture_material'").update();
		try {
			reuse(this.professor, "lecture_material", null, item).andExpect(status().is(422))
				.andExpect(jsonPath("$.code").value("extension_not_allowed"));
		}
		finally {
			this.jdbc.sql("""
					UPDATE upload_policies SET allowed_extensions = 'pdf,pptx,docx,xlsx,hwp,hwpx,zip,txt,png,jpg,jpeg'
					 WHERE policy_code = 'lecture_material'
					""").update();
		}
		this.jdbc.sql("UPDATE upload_policies SET max_file_bytes = 10 WHERE policy_code = 'lecture_material'").update();
		try {
			reuse(this.professor, "lecture_material", null, item).andExpect(status().is(413))
				.andExpect(jsonPath("$.code").value("too_large"));
		}
		finally {
			this.jdbc.sql("UPDATE upload_policies SET max_file_bytes = 52428800 WHERE policy_code = 'lecture_material'")
				.update();
		}

		setScan(item, "pending", "quarantined");
		reuse(this.professor, "lecture_material", null, item).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_ready"));
		setScan(item, "infected", "rejected");
		reuse(this.professor, "lecture_material", null, item).andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_ready"));
		assertThat(uploadsFromFilebox(item)).isZero();
	}

	private ResultActions reuse(ApiClient client, String purpose, Long courseId, long... itemIds) throws Exception {
		return client.send(MockMvcRequestBuilders.post("/api/uploads/from-filebox")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"itemIds\":" + FileboxCalls.ids(itemIds) + ",\"courseId\":"
					+ (courseId == null ? this.course : courseId) + ",\"purpose\":\"" + purpose + "\"}"));
	}

	private long createMaterial(long uploadId) throws Exception {
		return ApiClient.json(this.professor
			.postJson("/api/courses/" + this.course + "/materials",
					"{\"weekNo\":1,\"title\":\"자료\",\"uploadIds\":[" + uploadId + "]}")
			.andExpect(status().isCreated())).get("activityId").asLong();
	}

	private void setScan(long item, String scanState, String blobState) {
		this.jdbc.sql("""
				UPDATE filebox_items i JOIN files f ON f.file_id = i.file_id JOIN file_blobs b ON b.file_blob_id = f.file_blob_id
				   SET f.scan_state = :scan, b.blob_state = :blob
				 WHERE i.filebox_item_id = :id
				""").param("scan", scanState).param("blob", blobState).param("id", item).update();
	}

	private int uploadsFromFilebox(long item) {
		return this.jdbc.sql("SELECT COUNT(*) FROM files WHERE source_filebox_item_id = :id")
			.param("id", item)
			.query(Integer.class)
			.single();
	}

}
