package com.stackcorp.astra.identity.session;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import jakarta.servlet.http.HttpServletResponse;

/**
 * filter 단계의 오류 응답. 컨트롤러 밖이라 RFC 9457 problem JSON을 직접 쓴다.
 */
final class ProblemResponses {

	private ProblemResponses() {
	}

	static void write(HttpServletResponse response, int status, String code, String detail) throws IOException {
		response.setStatus(status);
		response.setContentType("application/problem+json");
		response.setHeader("Cache-Control", "no-store");
		String body = "{\"status\":" + status + ",\"code\":\"" + code + "\",\"detail\":\"" + detail + "\"}";
		response.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
	}

}
