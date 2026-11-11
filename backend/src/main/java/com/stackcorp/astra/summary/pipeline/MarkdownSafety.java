package com.stackcorp.astra.summary.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 생성된 Markdown에 raw HTML과 이미지를 허용하지 않는다(ADR-0008 5번, CHG-07). 화면도 따로 정화하지만 서버가 먼저 막는다.
 * 코드와 수식 안의 {@code <}는 검사하지 않는다.
 */
final class MarkdownSafety {

	private static final Pattern CODE_FENCE = Pattern.compile("```[\\s\\S]*?```");

	private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");

	private static final Pattern MATH = Pattern.compile("\\$\\$[\\s\\S]+?\\$\\$|\\$[^$\\n]+?\\$");

	private static final Pattern HTML = Pattern.compile("<[A-Za-z!/?]");

	private static final Pattern IMAGE = Pattern.compile("!\\[");

	private MarkdownSafety() {
	}

	static List<String> errors(String where, String text) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return out;
		}
		String prose = CODE_FENCE.matcher(text).replaceAll("");
		prose = INLINE_CODE.matcher(prose).replaceAll("");
		prose = MATH.matcher(prose.replace("\\$", "")).replaceAll("");
		if (HTML.matcher(prose).find()) {
			out.add(where + ": HTML 태그를 쓰지 않음(부등호는 $...$ 안에, 코드는 `...` 안에)");
		}
		if (IMAGE.matcher(prose).find()) {
			out.add(where + ": 이미지를 넣지 않음");
		}
		return out;
	}

}
