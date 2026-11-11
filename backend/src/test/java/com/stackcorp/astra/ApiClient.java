package com.stackcorp.astra;

import jakarta.servlet.http.Cookie;

import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 브라우저처럼 session 쿠키와 CSRF token을 붙여 API를 부른다.
 */
public final class ApiClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final MockMvc mvc;

	private final Cookie session;

	public ApiClient(MockMvc mvc, String sessionToken) {
		this.mvc = mvc;
		this.session = sessionToken == null ? null : new Cookie("ASTRA_SESSION", sessionToken);
	}

	public ResultActions get(String path) throws Exception {
		return this.mvc.perform(withSession(MockMvcRequestBuilders.get(path)));
	}

	public ResultActions postJson(String path, String json) throws Exception {
		return this.mvc.perform(mutating(MockMvcRequestBuilders.post(path).contentType(MediaType.APPLICATION_JSON).content(json)));
	}

	public ResultActions post(String path) throws Exception {
		return this.mvc.perform(mutating(MockMvcRequestBuilders.post(path)));
	}

	public ResultActions delete(String path) throws Exception {
		return this.mvc
			.perform(mutating(MockMvcRequestBuilders.delete(path)));
	}

	public ResultActions upload(long courseId, String purpose, String fileName, byte[] content, String idempotencyKey)
			throws Exception {
		var request = MockMvcRequestBuilders.multipart("/api/uploads")
			.file(new MockMultipartFile("file", fileName, "application/octet-stream", content))
			.param("courseId", Long.toString(courseId))
			.param("purpose", purpose);
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey);
		}
		return this.mvc.perform(mutating(request));
	}

	/** 올린 뒤 uploadId를 돌려준다. */
	public long uploaded(long courseId, String fileName, byte[] content) throws Exception {
		String body = upload(courseId, "lecture_material", fileName, content, null).andReturn()
			.getResponse()
			.getContentAsString();
		return JSON.readTree(body).get("uploadId").asLong();
	}

	public static JsonNode json(ResultActions result) throws Exception {
		return json(result.andReturn().getResponse());
	}

	public static JsonNode json(MockHttpServletResponse response) {
		return JSON.readTree(response.getContentAsByteArray());
	}

	private <B extends AbstractMockHttpServletRequestBuilder<B>> B mutating(B request) throws Exception {
		Cookie csrf = this.mvc.perform(MockMvcRequestBuilders.get("/api/auth/csrf")).andReturn().getResponse().getCookie("XSRF-TOKEN");
		request.cookie(csrf).header("X-XSRF-TOKEN", csrf.getValue());
		return withSession(request);
	}

	private <B extends AbstractMockHttpServletRequestBuilder<B>> B withSession(B request) {
		return this.session == null ? request : request.cookie(this.session);
	}

}
