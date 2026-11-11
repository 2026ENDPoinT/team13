package com.stackcorp.astra.identity.sso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.IntegrationTest;
import com.stackcorp.astra.identity.api.ExternalIdentityProvider;

/**
 * 공유 context에는 학교 IdP 설정이 없다. 이때 로그인 경로는 없는 것처럼 404이고 로그인 수단에는 break-glass만 있다.
 */
class SsoDisabledTests extends IntegrationTest {

	@Autowired
	MockMvc mvc;

	@Autowired
	ObjectProvider<ExternalIdentityProvider> providers;

	@Test
	void answersNotFoundWithoutIssuer() throws Exception {
		this.mvc.perform(get("/api/auth/login").param("returnTo", "/"))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("not_found"));
		this.mvc.perform(get("/api/auth/callback").param("code", "c").param("state", "s"))
			.andExpect(status().isNotFound());

		assertThat(this.providers.getIfAvailable()).isNull();
		assertThat(ApiClient.json(this.mvc.perform(get("/api/auth/methods")).andExpect(status().isOk()))
			.path("methods")
			.toString()).isEqualTo("[\"breakGlass\"]");
	}

}
