package com.stackcorp.astra.home;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.stackcorp.astra.shared.ApiException;
import com.stackcorp.astra.shared.RateLimitedException;
import com.stackcorp.astra.shared.audit.AuditLog;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 화면 AI 도우미(ADR-0044). 로그인한 사용자만 쓴다(그 밖은 security의 401). 다른 POST와 같은 출처 검사와 CSRF 검사를 거친다. 본문은
 * {@value #BODY_LIMIT} bytes까지만 읽는다(넘으면 413 {@code body_too_large}). 요청마다 감사 기록 하나({@code assistant.ask})를 남긴다.
 * 기록에는 사용자, session, requestId, 결과 코드만 있고 문장과 답은 없다.
 */
@RestController
@EnableConfigurationProperties(AssistantProperties.class)
class AssistantController {

	static final int BODY_LIMIT = 16 * 1024;

	static final String OPERATION = "assistant.ask";

	private final AssistantService assistant;

	private final AuditLog audit;

	AssistantController(AssistantService assistant, AuditLog audit) {
		this.assistant = assistant;
		this.audit = audit;
	}

	@PostMapping("/api/assistant")
	ResponseEntity<Map<String, Object>> ask(@AuthenticationPrincipal(expression = "userId()") Long userId,
			@AuthenticationPrincipal(expression = "sessionId()") Long sessionId, HttpServletRequest request)
			throws IOException {
		String result = "internal_error";
		try {
			this.assistant.requireEnabled();
			AssistantRequest parsed = AssistantRequest.parse(body(request));
			if (parsed == null) {
				throw ApiException.invalid("요청 형식이 올바르지 않습니다.");
			}
			Map<String, Object> answer = this.assistant.answer(userId, parsed);
			result = AuditLog.OK;
			return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(answer);
		}
		catch (ApiException ex) {
			result = ex.code();
			throw ex;
		}
		catch (RateLimitedException ex) {
			result = "rate_limited";
			throw ex;
		}
		finally {
			this.audit.record(userId, sessionId, OPERATION, null, null, null, result);
		}
	}

	// JSON만 받는다. 선언한 길이가 상한을 넘으면 읽지 않고, 선언이 없거나 틀려도 상한 하나 넘게는 읽지 않는다.
	private static byte[] body(HttpServletRequest request) throws IOException {
		String type = request.getContentType() == null ? ""
				: request.getContentType().split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
		if (request.getContentLengthLong() > BODY_LIMIT) {
			throw tooLarge();
		}
		byte[] body;
		try (InputStream in = request.getInputStream()) {
			body = in.readNBytes(BODY_LIMIT + 1);
		}
		if (body.length > BODY_LIMIT) {
			throw tooLarge();
		}
		if (!type.equals("application/json")) {
			throw ApiException.invalid("본문은 application/json이어야 합니다.");
		}
		return body;
	}

	private static ApiException tooLarge() {
		return new ApiException(HttpStatus.CONTENT_TOO_LARGE, "body_too_large", "요청 본문은 16KiB 이하여야 합니다.");
	}

}
