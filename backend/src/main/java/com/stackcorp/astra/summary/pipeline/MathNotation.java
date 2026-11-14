package com.stackcorp.astra.summary.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 수식 표기 검사. LessonViewer는 KaTeX로 컴파일까지 하지만 여기서는 표기만 본다(ADR-0006).
 */
final class MathNotation {

	private static final Pattern MATH = Pattern.compile("\\$\\$([\\s\\S]+?)\\$\\$|\\$([^$\\n]+?)\\$");

	private static final Pattern RAW_FORMULA = Pattern.compile("[σΠπρ⋈θ]_|_\\{");

	// 탭·폼피드·백스페이스 등. 모델이 JSON에 \text, \frac, \beta를 백슬래시 하나로 쓰면 이렇게 바뀐다.
	private static final Pattern CONTROL = Pattern.compile("[\\u0000-\\u0009\\u000b-\\u001f]");

	private static final Pattern CODE_FENCE = Pattern.compile("```[\\s\\S]*?```");

	private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*`");

	private static final Pattern ONE_LINE_DISPLAY = Pattern.compile("\\$\\$([^$\\n]+?)\\$\\$");

	private MathNotation() {
	}

	/**
	 * 한 줄 안에서 열고 닫은 {@code $$...$$}를 문장 안 수식 {@code $...$}으로 바꾼다. 모델이 블록 수식을 글줄 안에 자주 쓰고, 고쳐
	 * 달라고 해도 같은 줄을 되풀이해 묶음 전체를 다시 보내게 했다(실제 실행의 "$$는 따로 한 줄에 써야 함"). 화면에는 문장 안 수식으로
	 * 보이는 편이 원래 뜻에 맞는다. 코드 블록 안은 건드리지 않는다.
	 */
	static String inlineOneLineDisplay(String text) {
		if (text == null || !text.contains("$$")) {
			return text;
		}
		String[] lines = text.split("\n", -1);
		boolean fenced = false;
		for (int i = 0; i < lines.length; i++) {
			if (lines[i].strip().startsWith("```")) {
				fenced = !fenced;
			}
			else if (!fenced) {
				lines[i] = ONE_LINE_DISPLAY.matcher(lines[i])
					.replaceAll((m) -> Matcher.quoteReplacement("$" + m.group(1).strip() + "$"));
			}
		}
		return String.join("\n", lines);
	}

	static List<String> errors(String where, String text, boolean allowDisplay) {
		List<String> out = new ArrayList<>();
		if (text == null || text.isEmpty()) {
			return out;
		}
		if (CONTROL.matcher(text).find()) {
			out.add(where + ": 제어 문자가 있음(LaTeX 백슬래시를 JSON에서 \\\\로 두 번 쓰지 않았을 가능성)");
		}
		String withoutFences = CODE_FENCE.matcher(text).replaceAll("");
		String prose = INLINE_CODE.matcher(withoutFences).replaceAll("").replace("\\$", "");
		Matcher math = MATH.matcher(prose);
		while (math.find()) {
			boolean display = math.group(1) != null;
			if (display && !allowDisplay) {
				out.add(where + ": $$ 블록 수식은 쓸 수 없음");
			}
			String tex = display ? math.group(1) : math.group(2);
			if (!bracesBalanced(tex)) {
				out.add(where + ": 수식의 중괄호 짝이 안 맞음 `" + tex.substring(0, Math.min(60, tex.length())) + "`");
			}
		}
		String outside = MATH.matcher(prose).replaceAll("");
		if (outside.contains("$")) {
			out.add(where + ": 짝이 안 맞는 $ (돈 표시는 \\$)");
		}
		if (RAW_FORMULA.matcher(outside).find()) {
			out.add(where + ": 수식 기호가 $...$ 밖에 있음");
		}
		for (String line : withoutFences.split("\n", -1)) {
			if (line.contains("$$") && !line.strip().equals("$$")) {
				out.add(where + ": $$는 따로 한 줄에 써야 함");
			}
		}
		return out;
	}

	private static boolean bracesBalanced(String tex) {
		int depth = 0;
		for (int i = 0; i < tex.length(); i++) {
			char c = tex.charAt(i);
			if (c == '\\') {
				i++;
			}
			else if (c == '{') {
				depth++;
			}
			else if (c == '}' && --depth < 0) {
				return false;
			}
		}
		return depth == 0;
	}

}
