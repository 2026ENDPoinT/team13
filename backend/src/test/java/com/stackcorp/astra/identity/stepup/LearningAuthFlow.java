package com.stackcorp.astra.identity.stepup;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.stackcorp.astra.ApiClient;
import com.stackcorp.astra.identity.session.DevLoginConfiguration;

/**
 * 다른 package의 테스트가 학습 인증 grant를 받는 순서. 개발용 재인증이 있는 구성({@link DevLoginConfiguration})에서만 쓴다.
 */
public final class LearningAuthFlow {

	private LearningAuthFlow() {
	}

	/** challenge를 만들고 재인증한 뒤 확인해 이 session의 grant를 받는다. */
	public static void grant(ApiClient client) throws Exception {
		String challengeId = ApiClient
			.json(client.postJson("/api/me/learning-auth/challenges", "{\"channel\":\"idp\"}")
				.andExpect(status().isCreated()))
			.get("challengeId")
			.asString();
		client.post(DevLoginConfiguration.STEP_UP_PATH).andExpect(status().isNoContent());
		client.postJson("/api/me/learning-auth/challenges/" + challengeId + "/verify", "{}").andExpect(status().isOk());
	}

}
