package com.stackcorp.astra.home;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.stackcorp.astra.home.AssistantContext.CourseRef;
import com.stackcorp.astra.home.AssistantContext.Snapshot;
import com.stackcorp.astra.shared.RateLimiter;

/**
 * 화면 AI 도우미(ADR-0044). 분명한 명령은 규칙으로 바로 답하고({@link AssistantRules}), 나머지는 서버가 모은 이 사용자의 자료로 모델에 한
 * 번 묻는다({@link AssistantModel}). 답은 보일 문장과 열 곳(최대 5개)이다. 아무것도 저장하지 않는다(감사 기록은 controller가 남긴다).
 * <p>
 * 한도: 모든 요청이 사용자별 요청 한도({@code request-rate-limit})를 쓴다. 모델을 부르는 요청은 사용자별 모델 한도({@code rate-limit})와
 * 모든 사용자의 합계 한도({@code total-rate-limit})를 더 쓴다. 규칙 답은 모델 한도를 쓰지 않는다.
 */
@Service
class AssistantService {

	static final String RULE = "rule";

	static final String MODEL = "model";

	private static final Logger log = LoggerFactory.getLogger(AssistantService.class);

	private final AssistantProperties properties;

	private final AssistantContext context;

	private final AssistantModel model;

	private final RateLimiter rateLimiter;

	AssistantService(AssistantProperties properties, AssistantContext context, AssistantModel model,
			RateLimiter rateLimiter) {
		this.properties = properties;
		this.context = context;
		this.model = model;
		this.rateLimiter = rateLimiter;
	}

	/** @throws com.stackcorp.astra.shared.ApiException 503 {@code llm_disabled} 이 기능의 스위치가 꺼졌을 때 */
	void requireEnabled() {
		if (!this.properties.enabled()) {
			throw AssistantModel.disabled();
		}
	}

	/** @return 화면이 받는 답 {@code {say, actions, source}} */
	Map<String, Object> answer(long userId, AssistantRequest request) {
		this.rateLimiter.check("assistant", userId, this.properties.requestRateLimit(), this.properties.rateWindow());
		long started = System.nanoTime();
		Snapshot snapshot = this.context.base(userId);
		AssistantRules.Answer rule = AssistantRules.answer(snapshot, request.text(), request.courseId());
		if (rule != null) {
			log.info("assistant source=rule courses={} items={} actions={} ms={}", snapshot.courses.size(),
					snapshot.items.size(), rule.actions().size(), (System.nanoTime() - started) / 1_000_000);
			return body(rule, RULE);
		}
		if (!this.model.available()) {
			throw AssistantModel.disabled();
		}
		this.rateLimiter.check("assistant-model", userId, this.properties.rateLimit(), this.properties.rateWindow());
		this.rateLimiter.checkTotal("assistant-model", this.properties.totalRateLimit(), this.properties.rateWindow());
		CourseRef focus = AssistantContext.focus(snapshot, request.courseId(), request.text());
		this.context.extend(snapshot, userId, focus);
		AssistantModel.Usage usage = new AssistantModel.Usage();
		String outcome = "error";
		try {
			AssistantRules.Answer answer = this.model.ask(snapshot, request, focus, usage);
			outcome = "ok";
			return body(answer, MODEL);
		}
		catch (RuntimeException ex) {
			outcome = ex instanceof com.stackcorp.astra.shared.ApiException api ? api.code() : "error";
			throw ex;
		}
		finally {
			// 개수와 결과만 남긴다. 문장, 과목 이름, 제목, 모델 답은 남기지 않는다.
			log.info(
					"assistant source=model outcome={} prompt={} courses={} items={} history={} proposed={} dropped={} inputTokens={} outputTokens={} ms={}",
					outcome, AssistantModel.PROMPT_VERSION, snapshot.courses.size(), snapshot.items.size(),
					request.history().size(), usage.proposed, usage.dropped, usage.inputTokens, usage.outputTokens,
					(System.nanoTime() - started) / 1_000_000);
		}
	}

	private static Map<String, Object> body(AssistantRules.Answer answer, String source) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("say", answer.say());
		body.put("actions", answer.actions().stream().map(AssistantLinks.Action::body).toList());
		body.put("source", source);
		return body;
	}

}
