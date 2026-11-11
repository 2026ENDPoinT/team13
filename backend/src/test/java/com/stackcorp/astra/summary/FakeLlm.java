package com.stackcorp.astra.summary;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

import com.stackcorp.astra.summary.api.LlmClient;
import com.stackcorp.astra.summary.api.LlmContent;
import com.stackcorp.astra.summary.api.LlmMessage;
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.api.LlmResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * 요청한 쪽마다 해설을, story 요청에는 흐름 하나를 돌려주는 가짜 모델. 실제 모델 호출 없이 job 흐름을 시험한다.
 * 학습 대화 요청에는 search_source, read_page(1)를 차례로 부르고 1쪽을 밝힌 답을 돌려준다.
 */
public final class FakeLlm implements LlmClient {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final Pattern REQUESTED = Pattern.compile("이번에 해설할 페이지: ([\\d, ]+)");

	public final List<LlmRequest> log = new CopyOnWriteArrayList<>();

	private volatile Function<LlmRequest, LlmResponse> script = FakeLlm::honest;

	public void reset(Function<LlmRequest, LlmResponse> script) {
		this.log.clear();
		this.script = script;
	}

	@Override
	public String model() {
		return "fake-model";
	}

	@Override
	public boolean acceptsImages() {
		return true;
	}

	@Override
	public LlmResponse complete(LlmRequest request) {
		this.log.add(request);
		return this.script.apply(request);
	}

	public static LlmResponse honest(LlmRequest request) {
		if (request.purpose() == LlmRequest.Purpose.CHAT) {
			return honestChat(request);
		}
		if (isStory(request)) {
			int total = Integer.parseInt(text(request).replaceAll("(?s).*1–(\\d+) 범위예요.*", "$1"));
			return ok(Map.of("question", "이 자료는 무엇을 설명하나요?", "story", List.of(Map.of("from", 1, "to", total,
					"title", "핵심 흐름", "problem", "기존 방식이 불편했어요.", "answer", "새 개념이 그 문제를 풀어요."))));
		}
		Matcher m = REQUESTED.matcher(text(request));
		m.find();
		List<Map<String, Object>> slides = List.of(m.group(1).split(","))
			.stream()
			.map(String::strip)
			.map(Integer::valueOf)
			.map((p) -> Map.<String, Object>of("page", p, "tag", "core", "title", "개념 " + p, "explain",
					"- **Concept " + p + "**: 정의예요.", "digest", "p" + p + " 요약"))
			.toList();
		return ok(Map.of("slides", slides));
	}

	/** 도구를 주면 찾고, 읽고, 답한다. 도구가 없으면 바로 답한다. */
	public static LlmResponse honestChat(LlmRequest request) {
		long toolTurns = request.messages().stream().filter((m) -> m.role() == LlmMessage.Role.TOOL).count();
		if (request.tools().isEmpty() || toolTurns >= 2) {
			return answer("1쪽은 **sigma**를 설명해요 [p.1].");
		}
		return toolTurns == 0 ? toolCall("call-1", "search_source", "{\"query\":\"sigma\"}")
				: toolCall("call-2", "read_page", "{\"page\":1}");
	}

	public static LlmResponse toolCall(String id, String name, String arguments) {
		return new LlmResponse("", LlmResponse.StopReason.TOOL_USE, new LlmResponse.Usage(10, 5),
				List.of(new LlmContent.ToolCall(id, name, arguments)));
	}

	public static LlmResponse answer(String text) {
		return new LlmResponse(text, LlmResponse.StopReason.END, new LlmResponse.Usage(10, 5));
	}

	/** 요청의 모든 message에 든 글. 도구 결과를 포함한다. */
	public static String transcript(LlmRequest request) {
		StringBuilder out = new StringBuilder();
		for (LlmMessage message : request.messages()) {
			for (LlmContent c : message.content()) {
				switch (c) {
					case LlmContent.Text t -> out.append(t.text()).append('\n');
					case LlmContent.ToolResult r -> out.append(r.text()).append('\n');
					case LlmContent.ToolCall call -> out.append(call.arguments()).append('\n');
					case LlmContent.Image image -> {
					}
				}
			}
		}
		return out.toString();
	}

	/** 요청에 든 도구 결과. */
	public static List<LlmContent.ToolResult> toolResults(LlmRequest request) {
		return request.messages()
			.stream()
			.flatMap((m) -> m.content().stream())
			.filter(LlmContent.ToolResult.class::isInstance)
			.map(LlmContent.ToolResult.class::cast)
			.toList();
	}

	public static String text(LlmRequest request) {
		StringBuilder out = new StringBuilder();
		for (LlmContent c : request.messages().getFirst().content()) {
			if (c instanceof LlmContent.Text t) {
				out.append(t.text()).append('\n');
			}
		}
		return out.toString();
	}

	public static boolean isStory(LlmRequest request) {
		return text(request).contains("question과 story");
	}

	private static LlmResponse ok(Object value) {
		return new LlmResponse(JSON.writeValueAsString(value), LlmResponse.StopReason.END, new LlmResponse.Usage(10, 5));
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class Configuration {

		@Bean
		FakeLlm fakeLlm() {
			return new FakeLlm();
		}

	}

}
