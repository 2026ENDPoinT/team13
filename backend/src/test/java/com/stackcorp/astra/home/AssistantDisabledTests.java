package com.stackcorp.astra.home;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;
import com.stackcorp.astra.summary.FakeLlm;

/**
 * 도우미 스위치 {@code astra.assistant.enabled=false}(ADR-0044). 모델이 있어도 규칙 답까지 모두 503 {@code llm_disabled}다. 배포
 * 설정이 다르므로 Spring context를 따로 쓴다.
 */
@TestPropertySource(properties = "astra.assistant.enabled=false")
class AssistantDisabledTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Autowired
	FakeLlm llm;

	@Test
	void theSwitchTurnsTheWholeRouteOff() throws Exception {
		this.llm.reset(FakeLlm::honest);
		ApiClient student = new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT")));
		for (String text : new String[] { "할 일 목록 보여줘", "아무거나 찾아줘" }) {
			student.postJson("/api/assistant", "{\"text\":\"" + text + "\"}")
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("llm_disabled"));
		}
		assertThat(this.llm.log).isEmpty();
	}

}
