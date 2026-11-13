package com.stackcorp.astra.home;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.summary.FakeLlm;
import com.stackcorp.astra.summary.api.LlmResponse;

/**
 * 도우미 한도(ADR-0044). 사용자별 요청 한도는 규칙 답과 모델 답 모두, 사용자별 모델 한도와 전체 모델 한도는 모델 답만 센다. 넘으면 429
 * {@code rate_limited}와 {@code Retry-After}다. 한도가 process 메모리라 다른 테스트와 섞이지 않게 작은 한도로 Spring context를 따로 쓴다.
 */
@TestPropertySource(properties = { "astra.assistant.request-rate-limit=4", "astra.assistant.rate-limit=2",
		"astra.assistant.total-rate-limit=3" })
class AssistantLimitsTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	FakeLlm llm;

	@Test
	void perUserAndTotalLimitsAnswer429WithRetryAfter() throws Exception {
		this.llm.reset((r) -> new LlmResponse("{\"intent\":\"none\",\"targets\":[],\"say\":\"없어요\"}",
				LlmResponse.StopReason.END, new LlmResponse.Usage(1, 1)));
		ApiClient a = student();

		ask(a, "아무거나 찾아줘").andExpect(status().isOk()).andExpect(jsonPath("$.source").value("model"));
		ask(a, "아무거나 찾아줘").andExpect(status().isOk());
		// 사용자별 모델 한도(2).
		limited(ask(a, "아무거나 찾아줘"));
		// 규칙 답은 모델 한도를 쓰지 않는다.
		ask(a, "할 일 목록 보여줘").andExpect(status().isOk()).andExpect(jsonPath("$.source").value("rule"));
		// 사용자별 요청 한도(4)는 규칙 답도 센다.
		limited(ask(a, "할 일 목록 보여줘"));

		// 모든 사용자의 모델 호출 합계 한도(3). 앞 사용자의 성공 2번에 1번이 더해져 찬다.
		ask(student(), "아무거나 찾아줘").andExpect(status().isOk());
		limited(ask(student(), "아무거나 찾아줘"));
	}

	private ApiClient student() {
		return new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
	}

	private static ResultActions ask(ApiClient client, String text) throws Exception {
		return client.postJson("/api/assistant", "{\"text\":\"" + text + "\"}");
	}

	private static void limited(ResultActions result) throws Exception {
		result.andExpect(status().isTooManyRequests())
			.andExpect(jsonPath("$.code").value("rate_limited"))
			.andExpect(header().exists("Retry-After"));
	}

}
