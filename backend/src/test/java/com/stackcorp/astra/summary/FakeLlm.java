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
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.api.LlmResponse;

import tools.jackson.databind.json.JsonMapper;

/**
 * 요청한 쪽마다 해설을, story 요청에는 흐름 하나를 돌려주는 가짜 모델. 실제 모델 호출 없이 job 흐름을 시험한다.
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
