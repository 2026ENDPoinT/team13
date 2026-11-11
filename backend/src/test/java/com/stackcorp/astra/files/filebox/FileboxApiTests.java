package com.stackcorp.astra.files.filebox;

import static com.stackcorp.astra.files.filebox.FileboxCalls.command;
import static com.stackcorp.astra.files.filebox.FileboxCalls.file;
import static com.stackcorp.astra.files.filebox.FileboxCalls.folder;
import static com.stackcorp.astra.files.filebox.FileboxCalls.list;
import static com.stackcorp.astra.files.filebox.FileboxCalls.text;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.TestcontainersConfiguration.TestBuckets;
import com.stackcorp.astra.files.StorageGcDriver;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;

/**
 * 개인 파일함. 소유자만 자기 항목을 보고 바꾸며(SEC-08), 하위 항목은 서버가 계산하고(FILES-07), 목록은 정렬 allowlist와 keyset으로
 * 쪽을 나눈다(SEC-18). 용량은 동시에 올려도 넘지 않고, 영구 삭제는 파일 참조를 한 번만 놓아 GC가 원본을 회수한다.
 */
class FileboxApiTests extends IntegrationTest {

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

	long ownerId;

	ApiClient owner;

	ApiClient other;

	@BeforeEach
	void users() {
		this.ownerId = this.data.user("STUDENT");
		this.owner = new ApiClient(this.mvc, this.data.session(this.ownerId));
		this.other = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	@Test
	void anotherUserCanNeverReadOrChangeItemsByAnyIdOrParentId() throws Exception {
		long folder = folder(this.owner, null, "과제");
		long inner = file(this.owner, folder, "report.txt", text());
		long trashed = file(this.owner, null, "old.txt", text());
		command(this.owner, "trash", trashed).andExpect(status().isOk());
		long theirs = file(this.other, null, "mine.txt", text());
		long theirFolder = folder(this.other, null, "내 폴더");

		this.other.get("/api/filebox/items?parentId=" + folder).andExpect(status().isNotFound());
		assertThat(list(this.other, "").get("items")).extracting((n) -> n.get("id").asLong())
			.containsExactlyInAnyOrder(theirs, theirFolder);
		this.other.get("/api/filebox/items/" + inner + "/content").andExpect(status().isNotFound());
		this.other.get("/api/filebox/items/" + inner + "/references").andExpect(status().isNotFound());
		this.other
			.send(MockMvcRequestBuilders.patch("/api/filebox/items/" + inner)
				.header("If-Match", "\"1\"")
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"stolen.txt\"}"))
			.andExpect(status().isNotFound());
		FileboxCalls.createFolder(this.other, folder, "침입").andExpect(status().isNotFound());
		FileboxCalls.upload(this.other, folder, "x.txt", text(), null).andExpect(status().isNotFound());
		// 남의 폴더로 옮기기, 남의 항목을 섞어 옮기기
		move(this.other, folder, theirs, 1).andExpect(status().isNotFound());
		this.other
			.postJson("/api/filebox/items/move",
					"{\"targetFolderId\":" + theirFolder + ",\"items\":[{\"id\":" + theirs + ",\"version\":1},{\"id\":"
							+ inner + ",\"version\":1}]}")
			.andExpect(status().isNotFound());
		command(this.other, "trash", theirs, inner).andExpect(status().isNotFound());
		command(this.other, "restore", trashed).andExpect(status().isNotFound());
		command(this.other, "purge", trashed).andExpect(status().isNotFound());
		this.other.postJson("/api/filebox/trash/empty", "").andExpect(status().isOk()).andExpect(jsonPath("$.count").value(0));
		// ZIP 작업도 남의 항목이 하나라도 섞이면 404다.
		this.other.send(MockMvcRequestBuilders.post("/api/filebox/zip-jobs")
			.header("Idempotency-Key", "zip-foreign")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"itemIds\":[" + theirs + "," + inner + "]}")).andExpect(status().isNotFound());
		assertThat(ApiClient.json(this.other.get("/api/filebox/trash")).get("items")).isEmpty();

		// 아무것도 바뀌지 않았다.
		assertThat(this.jdbc.sql("SELECT item_state, parent_item_id, item_name FROM filebox_items WHERE filebox_item_id = :id")
			.param("id", inner)
			.query()
			.singleRow()).containsEntry("item_state", "active").containsEntry("item_name", "report.txt");
		assertThat(state(theirs)).isEqualTo("active");
		assertThat(state(trashed)).isEqualTo("trashed");
		this.owner.get("/api/filebox/items/" + inner + "/content").andExpect(status().isOk());

		new ApiClient(this.mvc, null).get("/api/filebox/items").andExpect(status().isUnauthorized());
		this.mvc
			.perform(MockMvcRequestBuilders.post("/api/filebox/folders")
				.cookie(new Cookie("ASTRA_SESSION", this.data.session(this.ownerId)))
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"name\":\"csrf\"}"))
			.andExpect(status().isForbidden());
	}

	@Test
	void uploadsIntoAFolderWithSuffixOnNameCollisionAndDownloadsWithAttachmentHeaders() throws Exception {
		long folder = folder(this.owner, null, "자료");
		byte[] first = text();
		JsonNode created = ApiClient.json(FileboxCalls.upload(this.owner, folder, "..\\9주차 <노트>.txt", first, "fb-1")
			.andExpect(status().isCreated())
			.andExpect(header().string("Cache-Control", "no-store"))
			.andExpect(jsonPath("$.name").value("9주차 _노트_.txt"))
			.andExpect(jsonPath("$.kind").value("file"))
			.andExpect(jsonPath("$.state").value("ready"))
			.andExpect(jsonPath("$.parentId").value(folder)));
		long id = created.get("id").asLong();
		// 같은 key는 처음 항목을 돌려준다.
		FileboxCalls.upload(this.owner, folder, "other.txt", text(), "fb-1")
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.id").value(id));
		FileboxCalls.upload(this.owner, folder, "9주차 _노트_.txt", text(), null)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.name").value("9주차 _노트_ (1).txt"));
		FileboxCalls.upload(this.owner, folder, "page.html", "<html>".getBytes(StandardCharsets.UTF_8), null)
			.andExpect(status().is(422))
			.andExpect(jsonPath("$.code").value("extension_not_allowed"));

		this.owner.get("/api/filebox/items/" + id + "/content")
			.andExpect(status().isOk())
			.andExpect(content().bytes(first))
			.andExpect(content().contentTypeCompatibleWith("text/plain"))
			.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment;")))
			.andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.containsString("filename*=UTF-8''"
					+ URLEncoder.encode("9주차 _노트_.txt", StandardCharsets.UTF_8).replace("+", "%20"))))
			.andExpect(header().string("Cache-Control", "private, no-store"))
			.andExpect(header().string("X-Content-Type-Options", "nosniff"))
			.andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"));
		assertThat(this.jdbc.sql("""
				SELECT u.upload_state, f.source_menu_code, f.course_id IS NULL AS no_course
				  FROM filebox_items i JOIN files f ON f.file_id = i.file_id JOIN upload_sessions u ON u.file_id = f.file_id
				 WHERE i.filebox_item_id = :id
				""").param("id", id).query().singleRow()).containsEntry("upload_state", "attached")
			.containsEntry("source_menu_code", "filebox")
			.containsEntry("no_course", 1L);
		this.owner.get("/api/filebox/items/" + id + "/content").andExpect(status().isOk());
		// 검사를 마치지 않은 파일은 내려받지 못한다.
		this.jdbc.sql("UPDATE files f JOIN filebox_items i ON i.file_id = f.file_id SET f.scan_state = 'pending' WHERE i.filebox_item_id = :id")
			.param("id", id)
			.update();
		this.owner.get("/api/filebox/items/" + id + "/content")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("not_ready"));
		JsonNode pending = null;
		for (JsonNode item : list(this.owner, "?parentId=" + folder).get("items")) {
			if (item.get("id").asLong() == id) {
				pending = item;
			}
		}
		assertThat(pending.get("state").asString()).isEqualTo("scanning");
	}

	@Test
	void listsFoldersFirstWithAStableKeysetCursorAndRejectsUnknownSortKeys() throws Exception {
		for (int i = 0; i < 25; i++) {
			folder(this.owner, null, String.format("f%02d", i));
		}
		long fileId = file(this.owner, null, "a-file.txt", text());

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			JsonNode page = list(this.owner, "?sort=name&size=10" + (cursor == null ? "" : "&cursor=" + cursor));
			page.get("items").forEach((n) -> seen.add(n.get("name").asString()));
			cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").asString();
			if (pages == 0) {
				// 이미 지난 위치와 아직 오지 않은 위치에 항목이 생겨도 겹치거나 빠지지 않는다.
				folder(this.owner, null, "f005");
				folder(this.owner, null, "f155");
			}
			pages++;
		}
		while (cursor != null);
		List<String> expected = new ArrayList<>();
		for (int i = 0; i < 25; i++) {
			expected.add(String.format("f%02d", i));
			if (i == 15) {
				expected.add("f155");
			}
		}
		expected.add("a-file.txt");
		assertThat(seen).containsExactlyElementsOf(expected);
		assertThat(pages).isEqualTo(3);

		JsonNode bySize = list(this.owner, "?sort=size&order=desc&size=100");
		assertThat(bySize.get("items").get(0).get("kind").asString()).isEqualTo("folder");
		assertThat(bySize.get("items").get(bySize.get("items").size() - 1).get("id").asLong()).isEqualTo(fileId);
		JsonNode byDate = list(this.owner, "?size=2");
		assertThat(byDate.get("items")).extracting((n) -> n.get("name").asString()).containsExactly("f155", "f005");
		assertThat(list(this.owner, "?size=2&cursor=" + byDate.get("nextCursor").asString()).get("items").get(0).get("name")
			.asString()).isEqualTo("f24");
		assertThat(byDate.get("usage").get("itemCount").asInt()).isEqualTo(28);

		this.owner.get("/api/filebox/items?sort=item_name").andExpect(status().isBadRequest());
		this.owner.get("/api/filebox/items?sort=name;DROP").andExpect(status().isBadRequest());
		this.owner.get("/api/filebox/items?order=sideways").andExpect(status().isBadRequest());
		this.owner.get("/api/filebox/items?size=0").andExpect(status().isBadRequest());
		this.owner.get("/api/filebox/items?size=101").andExpect(status().isBadRequest());
		this.owner.get("/api/filebox/items?sort=date&cursor=" + byDate.get("nextCursor").asString().substring(2))
			.andExpect(status().isBadRequest());
		// 다른 정렬에서 받은 cursor는 받지 않는다.
		this.owner.get("/api/filebox/items?sort=name&cursor=" + byDate.get("nextCursor").asString())
			.andExpect(status().isBadRequest());
	}

	@Test
	void searchesNamesWithWildcardCharactersTakenLiterally() throws Exception {
		folder(this.owner, null, "a_b");
		folder(this.owner, null, "axb");
		folder(this.owner, null, "50%");
		folder(this.owner, null, "100");

		assertThat(names(list(this.owner, "?sort=name&q=_"))).containsExactly("a_b");
		// URL template이 '%'를 다시 encode하므로 query 값으로 따로 보낸다.
		JsonNode percent = ApiClient.json(this.mvc
			.perform(MockMvcRequestBuilders.get("/api/filebox/items")
				.param("sort", "name")
				.param("q", "%")
				.cookie(new Cookie("ASTRA_SESSION", this.data.session(this.ownerId))))
			.andExpect(status().isOk()));
		assertThat(names(percent)).containsExactly("50%");
		assertThat(names(list(this.owner, "?sort=name&q=0"))).containsExactly("100", "50%");
		this.owner.get("/api/filebox/items?q=" + "x".repeat(101)).andExpect(status().isBadRequest());
	}

	@Test
	void renamesWithIfMatchAndKeepsNamesUniqueAmongSiblings() throws Exception {
		long folder = folder(this.owner, null, "자료");
		folder(this.owner, null, "기타");
		long fileId = file(this.owner, null, "note.txt", text());

		rename(fileId, null, "x.txt").andExpect(status().isPreconditionRequired());
		rename(folder, "\"2\"", "새 이름").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("version_conflict"));
		rename(folder, "\"1\"", "기타").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("name_conflict"));
		rename(folder, "\"1\"", "a/b").andExpect(status().isBadRequest());
		rename(fileId, "\"1\"", "note.pdf").andExpect(status().isBadRequest());
		rename(folder, "\"1\"", " 강의 자료 ")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.name").value("강의 자료"))
			.andExpect(jsonPath("$.version").value(2));
		rename(folder, "\"1\"", "다시").andExpect(status().isConflict());
		rename(fileId, "\"1\"", "renamed.txt").andExpect(status().isOk()).andExpect(jsonPath("$.name").value("renamed.txt"));
		FileboxCalls.createFolder(this.owner, null, "기타").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("name_conflict"));
	}

	@Test
	void movesAtomicallyAndRejectsMovingAFolderIntoItselfOrADescendant() throws Exception {
		long top = folder(this.owner, null, "top");
		long child = folder(this.owner, top, "child");
		long grandchild = folder(this.owner, child, "grandchild");
		long x = file(this.owner, null, "x.txt", text());
		long y = file(this.owner, null, "y.txt", text());

		move(this.owner, grandchild, top, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cycle"));
		move(this.owner, top, top, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("cycle"));
		// version이 하나라도 틀리면 아무것도 옮기지 않는다.
		this.owner
			.postJson("/api/filebox/items/move", "{\"targetFolderId\":" + child + ",\"items\":[{\"id\":" + x
					+ ",\"version\":1},{\"id\":" + y + ",\"version\":5}]}")
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("version_conflict"));
		assertThat(parent(x)).isNull();
		this.owner
			.postJson("/api/filebox/items/move", "{\"targetFolderId\":" + child + ",\"items\":[{\"id\":" + x
					+ ",\"version\":1},{\"id\":" + y + ",\"version\":1}]}")
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.count").value(2));
		assertThat(parent(x)).isEqualTo(child);
		assertThat(names(list(this.owner, "?parentId=" + child + "&sort=name"))).containsExactly("grandchild", "x.txt",
				"y.txt");
		assertThat(list(this.owner, "?parentId=" + grandchild).get("path")).extracting((n) -> n.get("id").asLong())
			.containsExactly(top, child, grandchild);
		// 같은 이름이 있는 폴더로는 옮기지 않는다.
		long other = file(this.owner, null, "x.txt", text());
		move(this.owner, child, other, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("name_conflict"));
		move(this.owner, null, grandchild, 1).andExpect(status().isOk());
		assertThat(parent(grandchild)).isNull();
	}

	@Test
	void boundsFolderDepth() throws Exception {
		Long parent = null;
		for (int depth = 1; depth <= 10; depth++) {
			parent = folder(this.owner, parent, "d" + depth);
		}
		FileboxCalls.createFolder(this.owner, parent, "d11").andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value("too_deep"));
		long other = folder(this.owner, null, "other");
		folder(this.owner, other, "sub");
		move(this.owner, parent, other, 1).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("too_deep"));
	}

	@Test
	void trashesTheWholeSubtreeAndRestoresItWithSuffixesOnCollisions() throws Exception {
		long top = folder(this.owner, null, "자료");
		long sub = folder(this.owner, top, "sub");
		long early = file(this.owner, sub, "early.txt", text());
		long late = file(this.owner, sub, "late.txt", text());

		// 먼저 하나를 따로 버리고, 그다음 위 폴더를 버린다. 고른 항목 안의 항목을 함께 골라도 위 폴더의 묶음이 된다.
		command(this.owner, "trash", early).andExpect(jsonPath("$.count").value(1));
		command(this.owner, "trash", sub, top).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));
		assertThat(list(this.owner, "").get("items")).isEmpty();
		JsonNode trash = ApiClient.json(this.owner.get("/api/filebox/trash"));
		assertThat(trash.get("items")).extracting((n) -> n.get("id").asLong()).containsExactly(top, early);
		assertThat(trash.get("items").get(0).get("itemCount").asInt()).isEqualTo(3);
		// 휴지통 항목은 옮기거나 내려받지 못한다. root가 아닌 항목은 복원하지 못한다.
		move(this.owner, null, late, 1).andExpect(status().isConflict());
		this.owner.get("/api/filebox/items/" + late + "/content").andExpect(status().isNotFound());
		command(this.owner, "restore", sub).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("not_in_trash"));
		command(this.owner, "restore", top, 999_999_999L).andExpect(status().isNotFound());

		folder(this.owner, null, "자료");
		file(this.owner, null, "early.txt", text());
		// 원래 폴더(sub)가 아직 휴지통에 있으면 루트로, 이름이 겹치면 접미사를 붙인다.
		command(this.owner, "restore", early).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
		assertThat(parent(early)).isNull();
		command(this.owner, "restore", top).andExpect(status().isOk()).andExpect(jsonPath("$.count").value(3));
		assertThat(names(list(this.owner, "?sort=name"))).containsExactly("자료", "자료 (1)", "early (1).txt",
				"early.txt");
		assertThat(parent(sub)).isEqualTo(top);
		assertThat(parent(late)).isEqualTo(sub);
		assertThat(state(late)).isEqualTo("active");
		assertThat(ApiClient.json(this.owner.get("/api/filebox/trash")).get("items")).isEmpty();
	}

	@Test
	void purgeReleasesEachFileReferenceExactlyOnceAndGcReclaimsTheBlob() throws Exception {
		byte[] shared = text();
		long a = file(this.owner, null, "a.txt", shared);
		long b = file(this.owner, null, "b.txt", shared);
		long folderId = folder(this.owner, null, "folder");
		long c = file(this.owner, folderId, "c.txt", text());
		long blob = blobOf(a);
		assertThat(blobOf(b)).isEqualTo(blob);
		assertThat(referenceCount(blob)).isEqualTo(2);
		long usedBefore = used();

		command(this.owner, "purge", a).andExpect(status().isConflict());
		command(this.owner, "trash", a, folderId).andExpect(status().isOk());
		assertThat(used()).isEqualTo(usedBefore);
		List<Integer> codes = concurrently(2, () -> command(this.owner, "purge", a, folderId).andReturn().getResponse().getStatus());
		assertThat(codes).containsExactlyInAnyOrder(200, 409);
		command(this.owner, "purge", a).andExpect(status().isConflict());

		assertThat(referenceCount(blob)).isOne();
		assertThat(fileState(a)).isEqualTo("deleted");
		assertThat(fileState(b)).isEqualTo("active");
		assertThat(fileState(c)).isEqualTo("deleted");
		assertThat(state(c)).isEqualTo("purged");
		assertThat(used()).isEqualTo(usedBefore - shared.length - sizeOf(c));
		assertThat(ApiClient.json(this.owner.get("/api/filebox/items")).get("usage").get("itemCount").asInt()).isEqualTo(1);

		long cBlob = blobOf(c);
		String cKey = storageKey(cBlob);
		age(cBlob);
		StorageGcDriver.drain(this.context);
		assertThat(exists(cKey)).isFalse();
		assertThat(this.jdbc.sql("SELECT purged_at IS NOT NULL FROM file_blobs WHERE file_blob_id = :id")
			.param("id", cBlob)
			.query(Boolean.class)
			.single()).isTrue();
		// 같은 내용을 쓰는 다른 항목이 남은 blob은 지우지 않는다.
		age(blob);
		StorageGcDriver.drain(this.context);
		assertThat(exists(storageKey(blob))).isTrue();
		this.owner.get("/api/filebox/items/" + b + "/content").andExpect(status().isOk()).andExpect(content().bytes(shared));

		command(this.owner, "trash", b).andExpect(status().isOk());
		this.owner.postJson("/api/filebox/trash/empty", "").andExpect(status().isOk()).andExpect(jsonPath("$.count").value(1));
		assertThat(referenceCount(blob)).isZero();
		assertThat(used()).isZero();
	}

	@Test
	void concurrentUploadsNeverExceedTheQuota() throws Exception {
		this.jdbc.sql("INSERT INTO filebox_quotas (owner_user_id, quota_bytes) VALUES (:u, 250)")
			.param("u", this.ownerId)
			.update();
		List<byte[]> contents = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			byte[] content = new byte[100];
			java.util.Arrays.fill(content, (byte) ('a' + i));
			System.arraycopy(text(), 0, content, 0, 20);
			contents.add(content);
		}
		java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
		List<Integer> codes = concurrently(6, () -> {
			int i = next.getAndIncrement();
			return FileboxCalls.upload(this.owner, null, "q" + i + ".txt", contents.get(i), null)
				.andReturn()
				.getResponse()
				.getStatus();
		});

		assertThat(codes).filteredOn((c) -> c == 201).hasSize(2);
		assertThat(codes).filteredOn((c) -> c == 409).hasSize(4);
		assertThat(used()).isEqualTo(200);
		assertThat(this.jdbc.sql("SELECT COUNT(*) FROM filebox_items WHERE owner_user_id = :u")
			.param("u", this.ownerId)
			.query(Integer.class)
			.single()).isEqualTo(2);
		Map<String, Object> row = this.jdbc.sql("SELECT used_bytes, item_count FROM filebox_quotas WHERE owner_user_id = :u")
			.param("u", this.ownerId)
			.query()
			.singleRow();
		assertThat(row).containsEntry("item_count", 2L);
	}

	private org.springframework.test.web.servlet.ResultActions rename(long id, String ifMatch, String name)
			throws Exception {
		var request = MockMvcRequestBuilders.patch("/api/filebox/items/" + id)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\":\"" + name + "\"}");
		if (ifMatch != null) {
			request.header("If-Match", ifMatch);
		}
		return this.owner.send(request);
	}

	private static org.springframework.test.web.servlet.ResultActions move(ApiClient client, Long target, long id,
			int version) throws Exception {
		return client.postJson("/api/filebox/items/move",
				"{\"targetFolderId\":" + target + ",\"items\":[{\"id\":" + id + ",\"version\":" + version + "}]}");
	}

	private static List<String> names(JsonNode page) {
		List<String> names = new ArrayList<>();
		page.get("items").forEach((n) -> names.add(n.get("name").asString()));
		return names;
	}

	private static <T> List<T> concurrently(int threads, Callable<T> work) throws Exception {
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			List<Future<T>> runs = new ArrayList<>();
			for (int i = 0; i < threads; i++) {
				runs.add(pool.submit(() -> {
					start.await();
					return work.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> run : runs) {
				results.add(run.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
	}

	private String state(long id) {
		return this.jdbc.sql("SELECT item_state FROM filebox_items WHERE filebox_item_id = :id")
			.param("id", id)
			.query(String.class)
			.single();
	}

	private Long parent(long id) {
		return this.jdbc.sql("SELECT parent_item_id FROM filebox_items WHERE filebox_item_id = :id")
			.param("id", id)
			.query(Long.class)
			.optional()
			.orElse(null);
	}

	private long blobOf(long item) {
		return this.jdbc.sql("""
				SELECT f.file_blob_id FROM filebox_items i JOIN files f ON f.file_id = i.file_id WHERE i.filebox_item_id = :id
				""").param("id", item).query(Long.class).single();
	}

	private String fileState(long item) {
		return this.jdbc.sql("""
				SELECT f.state FROM filebox_items i JOIN files f ON f.file_id = i.file_id WHERE i.filebox_item_id = :id
				""").param("id", item).query(String.class).single();
	}

	private long sizeOf(long item) {
		return this.jdbc.sql("""
				SELECT f.file_size_bytes FROM filebox_items i JOIN files f ON f.file_id = i.file_id WHERE i.filebox_item_id = :id
				""").param("id", item).query(Long.class).single();
	}

	private long referenceCount(long blob) {
		return this.jdbc.sql("SELECT reference_count FROM file_blobs WHERE file_blob_id = :id")
			.param("id", blob)
			.query(Long.class)
			.single();
	}

	private String storageKey(long blob) {
		return this.jdbc.sql("SELECT storage_key FROM file_blobs WHERE file_blob_id = :id")
			.param("id", blob)
			.query(String.class)
			.single();
	}

	private long used() {
		return this.jdbc.sql("SELECT used_bytes FROM filebox_quotas WHERE owner_user_id = :u")
			.param("u", this.ownerId)
			.query(Long.class)
			.single();
	}

	// 참조 수가 0이 된 지 오래된 것처럼 만든다.
	private void age(long blob) {
		this.jdbc.sql("UPDATE file_blobs SET updated_at = DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 1 DAY) WHERE file_blob_id = :id")
			.param("id", blob)
			.update();
	}

	private boolean exists(String key) {
		return !this.s3.listObjectsV2((b) -> b.bucket(this.buckets.content()).prefix(key)).contents().isEmpty();
	}

}
