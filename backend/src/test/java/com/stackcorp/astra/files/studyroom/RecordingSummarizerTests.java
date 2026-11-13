package com.stackcorp.astra.files.studyroom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.stackcorp.astra.files.api.SpeechToText.Utterance;
import com.stackcorp.astra.files.api.TextModel;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Section;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Segment;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Summary;
import com.stackcorp.astra.files.studyroom.RecordingStudyViews.Transcription;

import tools.jackson.databind.json.JsonMapper;

/**
 * 모델 출력 검증(ADR-0041): 계약 형식, 시각은 녹음 길이 안의 전사 줄 시각, 섹션은 시각 오름차순, 글은 일반 텍스트. 전사 정리와 실제
 * 길이 판정도 여기서 본다.
 */
class RecordingSummarizerTests {

	private static final List<Segment> SEGMENTS = List.of(new Segment(0, "a"), new Segment(10.5, "b"), new Segment(30, "c"));

	private final List<String> prompts = new ArrayList<>();

	@Test
	void outputIsCheckedSnappedSortedAndMadePlain() {
		Summary summary = summarizer(List.of()).parse("""
				앞말 ```json
				{"overview": "**강의** 개요\\n  둘째 줄", "extra": 1, "sections": [
				  {"title": "## 둘째", "at": 29.9, "points": [{"term": "`x`", "text": "설명", "at": 11}]},
				  {"title": "첫째", "at": 0.2, "points": [{"term": "t", "text": "u", "at": -0.4}],
				   "table": {"head": ["열"], "rows": [["값"], [3]]}}
				]}
				``` 뒷말""", SEGMENTS, 31);
		assertThat(summary.overview()).isEqualTo("강의 개요 둘째 줄");
		assertThat(summary.note()).isEqualTo(RecordingSummarizer.NOTE);
		assertThat(summary.sections()).extracting(Section::title).containsExactly("첫째", "둘째");
		assertThat(summary.sections()).extracting(Section::at).containsExactly(0.0, 10.5);
		assertThat(summary.sections().get(1).points().getFirst().term()).isEqualTo("x");
		assertThat(summary.sections().get(1).points().getFirst().at()).isEqualTo(10.5);
		assertThat(summary.sections().get(0).points().getFirst().at()).isEqualTo(0.0);
		assertThat(summary.sections().get(0).table().rows()).containsExactly(List.of("값"), List.of("3"));
	}

	@Test
	void malformedOutputIsRejected() {
		RecordingSummarizer summarizer = summarizer(List.of());
		for (String output : List.of("요약입니다", "[1, 2]", "{\"overview\": \"\", \"sections\": []}",
				"{\"overview\": \"o\", \"sections\": []}",
				"{\"overview\": \"o\", \"sections\": [{\"title\": \"t\", \"at\": 33, \"points\": [{\"term\": \"a\", \"text\": \"b\", \"at\": 1}]}]}",
				"{\"overview\": \"o\", \"sections\": [{\"title\": \"t\", \"at\": \"1\", \"points\": [{\"term\": \"a\", \"text\": \"b\", \"at\": 1}]}]}",
				"{\"overview\": \"o\", \"sections\": [{\"title\": \"t\", \"at\": 1, \"points\": []}]}",
				"{\"overview\": \"o\", \"sections\": [{\"title\": \" \", \"at\": 1, \"points\": [{\"term\": \"a\", \"text\": \"b\", \"at\": 1}]}]}",
				"{\"overview\": \"o\", \"sections\": [{\"title\": \"t\", \"at\": 1, \"points\": [{\"term\": \"a\", \"at\": 1}]}]}")) {
			assertThatThrownBy(() -> summarizer.parse(output, SEGMENTS, 31)).as(output)
				.isInstanceOf(RecordingSummarizer.InvalidOutput.class);
		}
	}

	@Test
	void longTextsAndListsAreCutAndBadTablesDropped() {
		String points = "{\"term\": \"a\", \"text\": \"b\", \"at\": 1},".repeat(12);
		Summary summary = summarizer(List.of()).parse("{\"overview\": \"" + "가".repeat(2000)
				+ "\", \"sections\": [{\"title\": \"t\", \"at\": 1, \"points\": [" + points.substring(0, points.length() - 1)
				+ "], \"table\": {\"head\": [\"a\", \"b\"], \"rows\": [[\"1\"]]}}]}", SEGMENTS, 31);
		assertThat(summary.overview().codePointCount(0, summary.overview().length())).isEqualTo(RecordingSummarizer.MAX_OVERVIEW);
		assertThat(summary.overview()).endsWith("…");
		assertThat(summary.sections().getFirst().points()).hasSize(RecordingSummarizer.MAX_POINTS);
		assertThat(summary.sections().getFirst().table()).isNull();
	}

	@Test
	void aLongTranscriptIsSummarizedInChunksWithOneOverview() {
		List<Segment> segments = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			segments.add(new Segment(i * 10, "문장 ".repeat(20) + i));
		}
		List<String> replies = new ArrayList<>();
		List<String> chunks = RecordingSummarizer.chunks(segments, 2000);
		assertThat(chunks).hasSizeGreaterThan(1);
		assertThat(String.join("", chunks)).startsWith("[0.00] 문장").contains("[590.00] ");
		for (int i = chunks.size() - 1; i >= 0; i--) {
			replies.add("{\"overview\": \"부분\", \"sections\": [{\"title\": \"s" + i + "\", \"at\": " + (590 - i * 100)
					+ ", \"points\": [{\"term\": \"a\", \"text\": \"b\", \"at\": 0}]}]}");
		}
		replies.add("{\"overview\": \"전체\"}");
		Summary summary = summarizer(replies).summarize(segments, 600);
		assertThat(summary.overview()).isEqualTo("전체");
		assertThat(summary.sections()).extracting(Section::at).isSorted();
		assertThat(this.prompts).hasSize(chunks.size() + 1);
		assertThat(this.prompts.getFirst()).startsWith("이 전사문은 강의의 1/" + chunks.size() + " 부분이다.");
	}

	@Test
	void onlyPairedEmphasisAndCodeWrappersAndHeadingsAreRemoved() {
		assertThat(RecordingSummarizer.plain("**핵심** 개념과 `map` 함수", 100)).isEqualTo("핵심 개념과 map 함수");
		assertThat(RecordingSummarizer.plain("## 정리", 100)).isEqualTo("정리");
		assertThat(RecordingSummarizer.plain("2**10 = 1024", 100)).isEqualTo("2**10 = 1024");
		assertThat(RecordingSummarizer.plain("a**2 + b**2 = c**2", 100)).isEqualTo("a**2 + b**2 = c**2");
		assertThat(RecordingSummarizer.plain("__init__ 메서드와 x_y", 100)).isEqualTo("__init__ 메서드와 x_y");
		assertThat(RecordingSummarizer.plain("C# 언어, 3 # 4", 100)).isEqualTo("C# 언어, 3 # 4");
	}

	@Test
	void transcriptsAreTrimmedClampedAndOrdered() {
		Transcription transcription = RecordingTranscripts.build(List.of(new Utterance(7.019, "뒤\u200b 말"),
				new Utterance(-1, "앞"), new Utterance(3, "\t"), new Utterance(99, "넘침")), 8.5);
		assertThat(transcription.segments()).extracting(Segment::at).containsExactly(0.0, 7.01, 8.5);
		assertThat(transcription.text()).isEqualTo("앞\n뒤 말\n넘침");
		assertThat(RecordingTranscripts.actualSeconds(5.2, 1000, List.of())).isEqualTo(6);
		assertThat(RecordingTranscripts.actualSeconds(null, 1000, List.of(new Utterance(9.1, "x")))).isEqualTo(10);
		assertThat(RecordingTranscripts.understated(70, 10_000)).isFalse();
		assertThat(RecordingTranscripts.understated(71, 10_000)).isTrue();
		assertThat(RecordingTranscripts.understated(3600 + 360, 3_600_000)).isFalse();
		assertThat(RecordingTranscripts.understated(3600 + 361, 3_600_000)).isTrue();
	}

	private RecordingSummarizer summarizer(List<String> replies) {
		List<String> queue = new ArrayList<>(replies);
		TextModel model = new TextModel() {

			@Override
			public boolean available() {
				return true;
			}

			@Override
			public String complete(String system, String user, int maxOutputTokens) {
				RecordingSummarizerTests.this.prompts.add(user);
				return queue.removeFirst();
			}

		};
		return new RecordingSummarizer(model, JsonMapper.builder().build(), 2000);
	}

}
