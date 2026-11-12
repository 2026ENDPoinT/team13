package com.stackcorp.astra.identity.session;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.TestData;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 개발용 로그인은 로컬 실행 구성을 불러온 때만 있다. 그 밖의 구성에서는 경로 자체가 없다.
 */
class DevLoginAbsentTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	TestData data;

	@Test
	void hasNoDeveloperLoginUnlessTheLocalRunConfigurationIsImported() throws Exception {
		String body = "{\"externalRef\":\"" + BREAK_GLASS_REF + "\"}";
		new ApiClient(this.mvc, null).postJson(DevLoginConfiguration.PATH, body).andExpect(status().isUnauthorized());
		new ApiClient(this.mvc, this.data.session(this.data.user("STUDENT"))).postJson(DevLoginConfiguration.PATH, body)
			.andExpect(status().isNotFound());
	}

}
