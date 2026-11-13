package com.stackcorp.astra.summary.pipeline;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모델 출력에서 JSON 객체를 꺼낸다.
 */
final class ModelJson {

	static final JsonMapper MAPPER = JsonMapper.builder().build();

	private static final Pattern FENCE = Pattern.compile("^```(?:json)?\\s*\\n([\\s\\S]*?)\\n```\\s*$");

	// JSON에서 무효인 단일 백슬래시(\sigma 등). 유효한 escape와 \\uXXXX는 건드리지 않는다.
	private static final Pattern LONE_BACKSLASH = Pattern.compile("\\\\(u(?![0-9a-fA-F]{4})|[^\"\\\\/bfnrtu])");

	private ModelJson() {
	}

	/**
	 * 코드 펜스를 벗기고 첫 '{'부터 마지막 '}'까지를 읽는다. 읽지 못하면 단일 백슬래시만 두 번으로 고쳐 한 번 더 읽는다.
	 *
	 * @throws IllegalArgumentException JSON 객체를 읽지 못했을 때
	 */
	static JsonNode parse(String text) {
		String s = text.strip();
		Matcher fence = FENCE.matcher(s);
		if (fence.matches()) {
			s = fence.group(1);
		}
		int start = s.indexOf('{');
		int end = s.lastIndexOf('}');
		if (start < 0 || end <= start) {
			throw new IllegalArgumentException("JSON 객체가 없습니다.");
		}
		s = s.substring(start, end + 1);
		try {
			return MAPPER.readTree(s);
		}
		catch (JacksonException first) {
			String fixed = LONE_BACKSLASH.matcher(s).replaceAll("\\\\\\\\$1");
			try {
				return MAPPER.readTree(fixed);
			}
			catch (JacksonException second) {
				throw new IllegalArgumentException(second.getOriginalMessage(), second);
			}
		}
	}

}
