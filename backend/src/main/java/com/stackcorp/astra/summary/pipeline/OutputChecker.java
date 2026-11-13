package com.stackcorp.astra.summary.pipeline;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.stackcorp.astra.summary.pack.Beat;
import com.stackcorp.astra.summary.pack.Pack;
import com.stackcorp.astra.summary.pack.Slide;
import com.stackcorp.astra.summary.pack.Tag;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * 모델 출력의 스키마, 쪽 범위, 분량, 표기를 검사한다. 오류 문구는 모델에 다시 보내는 문장이다.
 */
final class OutputChecker {

	// tag별 explain 권장 분량(문자). 1.5배를 넘으면 다시 쓰게 한다.
	private static final Map<Tag, Integer> LIMIT = Map.of(Tag.CORE, 1100, Tag.SUPPORT, 600, Tag.BACKGROUND, 300,
			Tag.META, 120);

	private static final double HARD_LIMIT = 1.5;

	private static final int MAX_BEATS = 12;

	private static final Pattern PERSONA = Pattern.compile("오빠|유미");

	private static final List<String> BEAT_REQUIRED = List.of("title", "problem", "answer");

	private static final List<String> BEAT_OPTIONAL = List.of("tradeoff", "extra", "bridge");

	private OutputChecker() {
	}

	static Checked<List<PageResult>> checkPages(JsonNode raw, List<Integer> expected) {
		List<String> errors = new ArrayList<>();
		List<String> soft = new ArrayList<>();
		JsonNode slides = raw == null ? null : raw.get("slides");
		if (slides == null || !slides.isArray()) {
			return Checked.failed(List.of("최상위에 \"slides\" 배열이 없음"), soft);
		}
		Set<Integer> want = new HashSet<>(expected);
		Map<Integer, PageResult> seen = new LinkedHashMap<>();
		for (JsonNode s : slides) {
			Integer page = integer(s.get("page"));
			String where = "p" + (page != null ? page.toString() : "?");
			if (page == null || !want.contains(page)) {
				errors.add(where + ": 요청하지 않은 페이지 번호");
				continue;
			}
			if (seen.containsKey(page)) {
				errors.add(where + ": 페이지가 두 번 나옴");
				continue;
			}
			int before = errors.size();
			Tag tag = Tag.of(string(s.get("tag")));
			String title = string(s.get("title"));
			String explain = string(s.get("explain"));
			String digest = string(s.get("digest"));
			JsonNode uncertainNode = s.get("uncertain");
			String uncertain = string(uncertainNode);
			if (tag == null) {
				errors.add(where + ": tag는 core|support|background|meta 중 하나여야 함 (" + display(s.get("tag")) + ")");
			}
			if (blank(title)) {
				errors.add(where + ": title 없음");
			}
			else if (title.length() > 120 || title.contains("\n")) {
				errors.add(where + ": title은 한 줄, 120자 이하");
			}
			if (blank(explain)) {
				errors.add(where + ": explain 없음");
			}
			else if (tag != null) {
				int limit = LIMIT.get(tag);
				if (explain.length() > limit * HARD_LIMIT) {
					errors.add(where + ": " + tag.value() + " explain " + explain.length() + "자, "
							+ Math.round(limit * HARD_LIMIT) + "자 이하로 줄여야 함");
				}
				else if (explain.length() > limit) {
					soft.add(where + ": " + tag.value() + " explain " + explain.length() + "자 (권장 " + limit + "자 이하)");
				}
			}
			if (blank(digest)) {
				errors.add(where + ": digest 없음");
			}
			else if (digest.length() > 600) {
				errors.add(where + ": digest는 600자 이하");
			}
			if (present(uncertainNode) && blank(uncertain)) {
				errors.add(where + ": uncertain이 빈 값 (없으면 키를 빼기)");
			}
			persona(errors, where + " title", title);
			persona(errors, where + " explain", explain);
			persona(errors, where + " digest", digest);
			persona(errors, where + " uncertain", uncertain);
			errors.addAll(MathNotation.errors(where + " title", title, false));
			errors.addAll(MathNotation.errors(where + " explain", explain, true));
			errors.addAll(MarkdownSafety.errors(where + " title", title));
			errors.addAll(MarkdownSafety.errors(where + " explain", explain));
			errors.addAll(MarkdownSafety.errors(where + " digest", digest));
			if (errors.size() > before) {
				continue;
			}
			seen.put(page, new PageResult(page, tag, title.strip(), explain.strip(), digest.strip(),
					blank(uncertain) ? null : uncertain.strip()));
		}
		List<Integer> missing = expected.stream()
			.filter((p) -> !seen.containsKey(p)
					&& errors.stream().noneMatch((e) -> e.startsWith("p" + p + ":") || e.startsWith("p" + p + " ")))
			.toList();
		if (!missing.isEmpty()) {
			errors.add("빠진 페이지: " + missing.stream().map((p) -> "p" + p).collect(Collectors.joining(", ")));
		}
		if (!errors.isEmpty()) {
			return Checked.failed(errors, soft);
		}
		return new Checked<>(expected.stream().map(seen::get).toList(), errors, soft);
	}

	/**
	 * @param tags 쪽 번호 순서의 tag. 길이가 전체 쪽 수다
	 */
	static Checked<StoryResult> checkStory(JsonNode raw, List<Tag> tags) {
		List<String> errors = new ArrayList<>();
		List<String> soft = new ArrayList<>();
		if (raw == null || !raw.isObject()) {
			return Checked.failed(List.of("JSON 객체가 아님"), soft);
		}
		int total = tags.size();
		String question = string(raw.get("question"));
		if (blank(question)) {
			errors.add("question 없음");
		}
		else {
			if (question.contains("\n") || question.length() > 300) {
				errors.add("question은 한 문장, 300자 이하");
			}
			if (PERSONA.matcher(question).find()) {
				errors.add("question: 특정 호칭·인물 이름을 쓰지 않음");
			}
			errors.addAll(MathNotation.errors("question", question, false));
			errors.addAll(MarkdownSafety.errors("question", question));
		}
		JsonNode beats = raw.get("story");
		if (beats == null || !beats.isArray() || beats.isEmpty()) {
			errors.add("story beat 없음");
			return Checked.failed(errors, soft);
		}
		if (beats.size() > MAX_BEATS) {
			errors.add("beat가 " + beats.size() + "개, " + MAX_BEATS + "개 이하로");
		}
		List<Beat> story = new ArrayList<>();
		int prevTo = 0;
		for (int i = 0; i < beats.size(); i++) {
			JsonNode b = beats.get(i);
			String where = "beat " + (i + 1);
			Integer from = integer(b.get("from"));
			Integer to = integer(b.get("to"));
			boolean okRange = from != null && to != null && from >= 1 && to <= total && from <= to;
			if (!okRange) {
				errors.add(where + ": 범위 오류 " + display(b.get("from")) + "-" + display(b.get("to")) + " (1–" + total
						+ ")");
			}
			else if (from <= prevTo) {
				errors.add(where + ": 앞 beat와 겹치거나 순서가 뒤바뀜 (" + from + " <= " + prevTo + ")");
			}
			if (okRange) {
				prevTo = Math.max(prevTo, to);
			}
			boolean complete = okRange;
			for (String field : BEAT_REQUIRED) {
				if (blank(string(b.get(field)))) {
					errors.add(where + ": " + field + " 없음");
					complete = false;
				}
			}
			for (String field : BEAT_OPTIONAL) {
				if (present(b.get(field)) && blank(string(b.get(field)))) {
					errors.add(where + ": " + field + "가 빈 값 (없으면 키를 빼기)");
				}
			}
			for (String field : List.of("title", "problem", "answer", "tradeoff", "extra", "bridge")) {
				String value = string(b.get(field));
				if (value == null) {
					continue;
				}
				if (PERSONA.matcher(value).find()) {
					errors.add(where + " " + field + ": 특정 호칭·인물 이름을 쓰지 않음");
				}
				errors.addAll(MathNotation.errors(where + " " + field, value, false));
				errors.addAll(MarkdownSafety.errors(where + " " + field, value));
			}
			if (complete) {
				story.add(new Beat("b" + (i + 1), from, to, string(b.get("title")).strip(),
						string(b.get("problem")).strip(), string(b.get("answer")).strip(), optional(b.get("tradeoff")),
						optional(b.get("extra")), optional(b.get("bridge"))));
			}
		}
		if (!errors.isEmpty()) {
			return Checked.failed(errors, soft);
		}
		List<Integer> loose = new ArrayList<>();
		for (int page = 1; page <= total; page++) {
			int p = page;
			if (tags.get(page - 1) == Tag.CORE && story.stream().noneMatch((b) -> b.from() <= p && p <= b.to())) {
				loose.add(page);
			}
		}
		if (!loose.isEmpty()) {
			soft.add("어느 beat에도 속하지 않은 core 페이지: "
					+ loose.stream().map((p) -> "p" + p).collect(Collectors.joining(", ")));
		}
		return new Checked<>(new StoryResult(question.strip(), story), errors, soft);
	}

	/**
	 * 완성한 pack을 한 번 더 검사한다.
	 *
	 * @return 오류 목록. 비어 있으면 통과
	 */
	static List<String> validatePack(Pack pack, int pageCount) {
		List<String> errors = new ArrayList<>();
		if (blank(pack.lecture())) {
			errors.add("lecture 없음");
		}
		List<Slide> slides = pack.slides();
		if (slides.size() != pageCount) {
			errors.add("slides " + slides.size() + "개, 페이지는 " + pageCount + "쪽");
		}
		for (int i = 0; i < slides.size(); i++) {
			Slide s = slides.get(i);
			if (s.page() != i + 1) {
				errors.add((i + 1) + "번째 slide의 page가 " + s.page());
			}
			if (s.tag() == null || blank(s.title()) || blank(s.explain())) {
				errors.add("p" + s.page() + ": 필수 필드 오류");
			}
		}
		ObjectNode raw = ModelJson.MAPPER.createObjectNode();
		raw.put("question", pack.question());
		raw.set("story", ModelJson.MAPPER.valueToTree(pack.story()));
		errors.addAll(checkStory(raw, slides.stream().map(Slide::tag).toList()).errors());
		return errors;
	}

	private static void persona(List<String> errors, String where, String value) {
		if (value != null && PERSONA.matcher(value).find()) {
			errors.add(where + ": 특정 호칭·인물 이름(오빠, 유미)을 쓰지 않음");
		}
	}

	private static boolean present(JsonNode node) {
		return node != null && !node.isNull() && !node.isMissingNode();
	}

	private static boolean blank(String value) {
		return value == null || value.isBlank();
	}

	/** @return 문자열 노드의 값. 문자열이 아니면 null */
	private static String string(JsonNode node) {
		return node != null && node.isString() ? node.stringValue() : null;
	}

	private static String optional(JsonNode node) {
		String value = string(node);
		return blank(value) ? null : value.strip();
	}

	/** @return 정수 값인 숫자 노드의 값. 아니면 null */
	private static Integer integer(JsonNode node) {
		if (node == null || !node.isNumber()) {
			return null;
		}
		double value = node.doubleValue();
		return value == Math.rint(value) && Math.abs(value) <= Integer.MAX_VALUE ? (int) value : null;
	}

	private static String display(JsonNode node) {
		if (node == null || node.isMissingNode()) {
			return "없음";
		}
		return node.isString() ? node.stringValue() : node.toString();
	}

}
