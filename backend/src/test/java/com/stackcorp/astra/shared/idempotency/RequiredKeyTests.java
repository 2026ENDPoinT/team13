package com.stackcorp.astra.shared.idempotency;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * key가 반드시 있어야 하는 명령은 header가 없으면 모두 같은 code(400 {@code idempotency_key_required})로 거부한다. 형식이 틀린 key는
 * 400 {@code invalid_request}다. 수강신청, 과제·퀴즈 제출, 설문 완료, 학습 대화 질문은 각 영역의 테스트가 같은 code를 확인한다.
 */
class RequiredKeyTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Test
	void messageSendAndZipJobRejectAMissingKeyWithOneCode() throws Exception {
		ApiClient client = new ApiClient(this.mvc, this.data.session(this.data.user("PROFESSOR")));

		client.postJson("/api/messages", "{\"title\":\"안내\",\"body\":\"본문\",\"recipientIds\":[1]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("idempotency_key_required"));
		client.postJson("/api/filebox/zip-jobs", "{\"itemIds\":[1]}")
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("idempotency_key_required"));
		client.send(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/messages")
			.header(Idempotency.HEADER, "bad key!")
			.contentType(org.springframework.http.MediaType.APPLICATION_JSON)
			.content("{\"title\":\"안내\",\"body\":\"본문\",\"recipientIds\":[1]}"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value("invalid_request"));
	}

}
