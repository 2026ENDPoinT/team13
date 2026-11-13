package com.stackcorp.astra.summary.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.stackcorp.astra.summary.pack.Beat;
import com.stackcorp.astra.summary.pack.Tag;

import tools.jackson.databind.JsonNode;

class OutputCheckerTests {

	private static final List<Tag> FOUR_CORE_PAGES = List.of(Tag.CORE, Tag.CORE, Tag.CORE, Tag.CORE);

	@Test
	void readsJsonWithLoneLatexBackslashes() {
		JsonNode node = ModelJson.parse("{\"a\": \"$\\sigma_p(r)$\"}");

		assertThat(node.get("a").stringValue()).isEqualTo("$\\sigma_p(r)$");
	}

	@Test
	void rejectsOverlappingOutOfRangeAndBlankOptionalBeats() {
		assertThat(checkStory(beat(1, 3), beat(3, 4)).errors()).isNotEmpty();
		assertThat(checkStory(beat(1, 5)).errors()).isNotEmpty();
		assertThat(checkStory(beat(1, 4, Map.of("bridge", ""))).errors()).isNotEmpty();
	}

	@Test
	void numbersBeatsInOrder() {
		Checked<StoryResult> checked = checkStory(beat(1, 2), beat(3, 4));

		assertThat(checked.errors()).isEmpty();
		assertThat(checked.value().story()).extracting(Beat::id).containsExactly("b1", "b2");
	}

	@Test
	void warnsAboutCorePagesOutsideEveryBeat() {
		Checked<StoryResult> checked = checkStory(beat(1, 2));

		assertThat(checked.errors()).isEmpty();
		assertThat(checked.soft()).singleElement().asString().contains("p3, p4");
	}

	@Test
	void rejectsRawHtmlAndImagesButAllowsThemInsideCodeAndMath() {
		Checked<List<PageResult>> bad = OutputChecker.checkPages(ModelJson.MAPPER.valueToTree(Map.of("slides",
				List.of(page(1, "- <img src=x onerror=alert(1)> 설명"), page(2, "- ![도식](https://evil.example/x.png)")))),
				List.of(1, 2));
		Checked<List<PageResult>> ok = OutputChecker.checkPages(ModelJson.MAPPER.valueToTree(Map.of("slides",
				List.of(page(1, "- 조건 $a<b$를 만족해요."), page(2, "- 태그 `<div>`는 블록 요소예요.")))), List.of(1, 2));

		assertThat(bad.errors()).anyMatch((e) -> e.startsWith("p1 explain: HTML"))
			.anyMatch((e) -> e.startsWith("p2 explain: 이미지"));
		assertThat(ok.errors()).isEmpty();
	}

	private static Map<String, Object> page(int page, String explain) {
		return Map.of("page", page, "tag", "core", "title", "제목 " + page, "explain", explain, "digest", "요약");
	}

	@SafeVarargs
	private static Checked<StoryResult> checkStory(Map<String, Object>... beats) {
		return OutputChecker.checkStory(ModelJson.MAPPER.valueToTree(Map.of("question", "q", "story", List.of(beats))),
				FOUR_CORE_PAGES);
	}

	private static Map<String, Object> beat(int from, int to) {
		return beat(from, to, Map.of());
	}

	private static Map<String, Object> beat(int from, int to, Map<String, Object> extra) {
		Map<String, Object> beat = new LinkedHashMap<>(Map.of("title", "t", "problem", "p", "answer", "a"));
		beat.put("from", from);
		beat.put("to", to);
		beat.putAll(extra);
		return beat;
	}

}
