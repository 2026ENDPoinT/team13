package com.stackcorp.astra.files.filebox;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import com.stackcorp.astra.ApiClient;

import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

/**
 * 파일함 API를 부르는 테스트 도우미.
 */
final class FileboxCalls {

	private FileboxCalls() {
	}

	static ResultActions createFolder(ApiClient client, Long parentId, String name) throws Exception {
		return client.postJson("/api/filebox/folders",
				"{\"parentId\":" + parentId + ",\"name\":\"" + name + "\"}");
	}

	static long folder(ApiClient client, Long parentId, String name) throws Exception {
		return ApiClient.json(createFolder(client, parentId, name).andExpect(status().isCreated())).get("id").asLong();
	}

	static ResultActions upload(ApiClient client, Long parentId, String name, byte[] content, String key)
			throws Exception {
		var request = MockMvcRequestBuilders.multipart("/api/filebox/files")
			.file(new MockMultipartFile("file", name, "application/octet-stream", content));
		if (parentId != null) {
			request.param("parentId", Long.toString(parentId));
		}
		if (key != null) {
			request.header("Idempotency-Key", key);
		}
		return client.send(request);
	}

	static long file(ApiClient client, Long parentId, String name, byte[] content) throws Exception {
		return ApiClient.json(upload(client, parentId, name, content, null).andExpect(status().isCreated()))
			.get("id")
			.asLong();
	}

	static JsonNode list(ApiClient client, String query) throws Exception {
		return ApiClient.json(client.get("/api/filebox/items" + query).andExpect(status().isOk()));
	}

	static ResultActions command(ApiClient client, String action, long... ids) throws Exception {
		return client.postJson("/api/filebox/items/" + action, "{\"ids\":" + ids(ids) + "}");
	}

	static String ids(long... ids) {
		return Arrays.stream(ids).mapToObj(Long::toString).collect(Collectors.joining(",", "[", "]"));
	}

	/** 내용이 겹치지 않는 텍스트 파일. */
	static byte[] text() {
		return ("filebox " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
	}

}
