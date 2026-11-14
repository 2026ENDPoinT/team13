package com.stackcorp.astra.summary.pipeline;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import com.stackcorp.astra.summary.api.LlmClient;
import com.stackcorp.astra.summary.api.LlmContent;
import com.stackcorp.astra.summary.api.LlmException;
import com.stackcorp.astra.summary.api.LlmRequest;
import com.stackcorp.astra.summary.api.LlmRequest.Purpose;
import com.stackcorp.astra.summary.api.LlmResponse;
import com.stackcorp.astra.summary.api.LlmResponse.StopReason;
import com.stackcorp.astra.summary.pack.Slide;
import com.stackcorp.astra.summary.pipeline.GenerateInput.Limits;
import com.stackcorp.astra.summary.pipeline.GenerationException.Code;

class SummaryPipelineTests {

	private static final Pattern REQUESTED = Pattern.compile("이번에 해설할 페이지: ([\\d, ]+)");

	@Test
	void coversEveryPageOnceInOrderAndAttachesStory() {
		FakeLlm llm = honest(14);
		List<Integer> progress = new ArrayList<>();
		GenerateResult result = new SummaryPipeline(llm).generate(new GenerateInput("doc-1", "Test", document(14),
				new MemoryCheckpoints(), Cancellation.NEVER, (p) -> progress.add(p.pagesDone()), Limits.DEFAULT));

		assertThat(result.pack().slides()).extracting(Slide::page)
			.containsExactlyElementsOf(IntStream.rangeClosed(1, 14).boxed().toList());
		assertThat(result.pack().lecture()).isEqualTo("doc-1");
		assertThat(result.pack().story()).singleElement().satisfies((beat) -> {
			assertThat(beat.id()).isEqualTo("b1");
			assertThat(beat.from()).isEqualTo(2);
			assertThat(beat.to()).isEqualTo(14);
		});
		// 10 + 4쪽 묶음과 story 한 번
		assertThat(llm.log).filteredOn((r) -> r.purpose() == Purpose.PAGES).hasSize(2);
		assertThat(llm.log).allMatch((r) -> r.maxOutputTokens() <= 16000);
		assertThat(result.usage().calls()).isEqualTo(3);
		assertThat(result.usage().inputTokens()).isEqualTo(30);
		assertThat(progress).last().isEqualTo(14);
	}

	@Test
	void sendsChunksAtTheSameTimeEachWithTheWholeDocumentOutline() {
		CountDownLatch allInFlight = new CountDownLatch(3);
		AtomicBoolean overlapped = new AtomicBoolean(true);
		FakeLlm honest = honest(30);
		FakeLlm llm = new FakeLlm((req) -> {
			if (!isStory(req)) {
				allInFlight.countDown();
				try {
					if (!allInFlight.await(5, TimeUnit.SECONDS)) {
						overlapped.set(false);
					}
				}
				catch (InterruptedException ex) {
					throw new IllegalStateException(ex);
				}
			}
			return honest.complete(req);
		});

		GenerateResult result = run(llm, document(30));

		assertThat(overlapped).isTrue();
		assertThat(result.pack().slides()).extracting(Slide::page)
			.containsExactlyElementsOf(IntStream.rangeClosed(1, 30).boxed().toList());
		// 첫 묶음도 뒤쪽 쪽의 첫 줄을 목차로 받는다.
		assertThat(llm.log).filteredOn((r) -> r.purpose() == Purpose.PAGES)
			.hasSize(3)
			.allSatisfy((r) -> assertThat(firstText(r.messages().getFirst().content())).contains("p30 Slide 30 text"));
	}

	@Test
	void reportsTheFailingChunksOwnErrorWhenOneOfTheParallelChunksFails() {
		FakeLlm honest = honest(30);
		FakeLlm llm = new FakeLlm((req) -> requestedPages(req).contains(11) ? text("not json") : honest.complete(req));

		GenerationException ex = catchThrowableOfType(GenerationException.class, () -> run(llm, document(30)));

		assertThat(ex.code()).isEqualTo(Code.INVALID_OUTPUT);
		assertThat(ex.getMessage()).contains("p11");
	}

	@Test
	void asksModelToRepairUnparseableJson() {
		AtomicBoolean first = new AtomicBoolean(true);
		FakeLlm llm = new FakeLlm((req) -> {
			if (isStory(req)) {
				return ok(story(2));
			}
			if (first.getAndSet(false)) {
				return text("{\"slides\": [ {\"page\": 1, ");
			}
			return text("```json\n" + json(Map.of("slides", List.of(slide(1), slide(2)))) + "\n```");
		});

		GenerateResult result = run(llm, document(2));

		assertThat(result.pack().slides()).hasSize(2);
		LlmRequest repair = llm.log.stream().filter((r) -> r.purpose() == Purpose.REPAIR).findFirst().orElseThrow();
		assertThat(repair.messages()).hasSize(3);
		assertThat(firstText(repair.messages().get(2).content())).contains("JSON 파싱 실패");
	}

	@Test
	void failsInsteadOfInventingPagesTheModelNeverGetsRight() {
		FakeLlm llm = new FakeLlm((req) -> {
			if (isStory(req)) {
				return ok(story(3));
			}
			// p3을 항상 빼먹고 p2는 tag가 틀리고 호칭을 쓴다.
			List<Map<String, Object>> slides = requestedPages(req).stream()
				.filter((p) -> p != 3)
				.map((p) -> p == 2 ? slide(p, Map.of("tag", "important", "explain", "오빠, 이건 중요해요.")) : slide(p))
				.toList();
			return ok(Map.of("slides", slides));
		});

		GenerationException ex = catchThrowableOfType(GenerationException.class, () -> run(llm, document(3)));

		assertThat(ex.code()).isEqualTo(Code.INVALID_OUTPUT);
		assertThat(ex.getMessage()).containsPattern("p[23]");
	}

	@Test
	void splitsChunkWhenOutputIsTruncated() {
		List<Integer> sizes = new ArrayList<>();
		FakeLlm llm = new FakeLlm((req) -> {
			if (isStory(req)) {
				return ok(story(4));
			}
			List<Integer> pages = requestedPages(req);
			sizes.add(pages.size());
			if (pages.size() > 2) {
				return new LlmResponse("{\"slides\":[", StopReason.MAX_TOKENS, LlmResponse.Usage.NONE);
			}
			return ok(Map.of("slides", pages.stream().map(SummaryPipelineTests::slide).toList()));
		});

		GenerateResult result = run(llm, document(4));

		assertThat(sizes).containsExactly(4, 2, 2);
		assertThat(result.pack().slides()).extracting(Slide::page).containsExactly(1, 2, 3, 4);
	}

	@Test
	void reportsNonRetryableProviderFailureWithoutRetrying() {
		FakeLlm llm = new FakeLlm((req) -> {
			throw new LlmException(LlmException.Code.AUTH, "401 unauthorized");
		});

		GenerationException ex = catchThrowableOfType(GenerationException.class, () -> run(llm, document(2)));

		assertThat(ex.code()).isEqualTo(Code.PROVIDER);
		assertThat(ex.getMessage()).contains("auth");
		assertThat(llm.log).hasSize(1);
	}

	@Test
	void retriesTransientProviderFailure() {
		FakeLlm honest = honest(2);
		AtomicBoolean first = new AtomicBoolean(true);
		FakeLlm llm = new FakeLlm((req) -> {
			if (first.getAndSet(false)) {
				throw new LlmException(LlmException.Code.RATE_LIMIT, "429");
			}
			return honest.complete(req);
		});

		GenerateResult result = new SummaryPipeline(llm, Duration.ofMillis(1)).generate(input(document(2),
				new MemoryCheckpoints(), Cancellation.NEVER));

		assertThat(result.pack().slides()).hasSize(2);
		assertThat(result.usage().calls()).isEqualTo(3);
	}

	@Test
	void reportsCancellationEvenWhenTheCallInFlightFails() {
		AtomicBoolean cancelled = new AtomicBoolean();
		FakeLlm llm = new FakeLlm((req) -> {
			cancelled.set(true);
			throw new IllegalStateException("aborted");
		});

		GenerationException ex = catchThrowableOfType(GenerationException.class,
				() -> new SummaryPipeline(llm).generate(input(document(8), new MemoryCheckpoints(), cancelled::get)));

		assertThat(ex.code()).isEqualTo(Code.CANCELLED);
	}

	@Test
	void retryReusesCheckpointsWithoutCallingModel() {
		MemoryCheckpoints checkpoints = new MemoryCheckpoints();
		new SummaryPipeline(honest(5)).generate(input(document(5), checkpoints, Cancellation.NEVER));
		FakeLlm second = honest(5);

		GenerateResult result = new SummaryPipeline(second).generate(input(document(5), checkpoints,
				Cancellation.NEVER));

		assertThat(second.log).isEmpty();
		assertThat(result.pack().slides()).hasSize(5);
	}

	@Test
	void ignoresCheckpointsOfDifferentSourceBytes() {
		MemoryCheckpoints checkpoints = new MemoryCheckpoints();
		new SummaryPipeline(honest(3)).generate(input(document(3), checkpoints, Cancellation.NEVER));
		FakeLlm second = honest(3);

		new SummaryPipeline(second).generate(input(new FakeDocument("other-sha", pages(3)), checkpoints,
				Cancellation.NEVER));

		assertThat(second.log).hasSize(2);
	}

	@Test
	void movesUncertainNotesToReview() {
		FakeLlm llm = new FakeLlm((req) -> {
			if (isStory(req)) {
				return ok(story(2));
			}
			return ok(Map.of("slides", requestedPages(req).stream()
				.map((p) -> p == 2 ? slide(p, Map.of("uncertain", "그림 속 숫자가 흐려서 읽지 못했어요.")) : slide(p))
				.toList()));
		});

		GenerateResult result = run(llm, document(2));

		assertThat(result.review()).containsExactly(Map.entry(2, "그림 속 숫자가 흐려서 읽지 못했어요."));
	}

	@Test
	void asksModelToRepairBrokenMathNotation() {
		AtomicInteger pageCalls = new AtomicInteger();
		FakeLlm llm = new FakeLlm((req) -> {
			if (isStory(req)) {
				return ok(story(2));
			}
			boolean bad = pageCalls.incrementAndGet() == 1;
			return ok(Map.of("slides", requestedPages(req).stream()
				.map((p) -> p == 2 && bad ? slide(p, Map.of("explain", "- 식 $\\frac{1}{$ 를 봐요.")) : slide(p))
				.toList()));
		});

		GenerateResult result = run(llm, document(2));

		assertThat(pageCalls).hasValue(2);
		assertThat(result.pack().slides().get(1).explain()).doesNotContain("\\frac{1}{$");
	}

	@Test
	void sendsNoImagesAndFlagsTextlessPagesWhenModelIsTextOnly() {
		FakeLlm honest = honest(2);
		FakeLlm llm = new FakeLlm(honest::complete, false);
		List<SourceDocument.Page> pages = List.of(new SourceDocument.Page(1, "Slide 1 text", false, 3, false),
				new SourceDocument.Page(2, "", false, 3, false));

		GenerateResult result = run(llm, new FakeDocument("sha-1", pages));

		assertThat(llm.log).flatExtracting((r) -> r.messages().getFirst().content())
			.noneMatch((c) -> c instanceof LlmContent.Image);
		assertThat(result.review()).containsOnlyKeys(2);
	}

	@Test
	void sendsAPageWhoseImageIsUnavailableAsTextOnlyEvenToAVisionModel() {
		FakeLlm llm = honest(3);
		List<SourceDocument.Page> pages = List.of(new SourceDocument.Page(1, "Slide 1 text", false, 3, false),
				new SourceDocument.Page(2, "Slide 2 text", false, 3, true),
				new SourceDocument.Page(3, "Slide 3 text", false, 3, false));

		GenerateResult result = run(llm, new FakeDocument("sha-1", pages));

		List<LlmContent> sent = llm.log.stream()
			.filter((r) -> r.purpose() == Purpose.PAGES)
			.flatMap((r) -> r.messages().getFirst().content().stream())
			.toList();
		// 대체 그림 쪽(p2)의 이미지는 보내지 않고, 나머지 쪽의 이미지만 보낸다.
		assertThat(sent).filteredOn((c) -> c instanceof LlmContent.Image)
			.extracting((c) -> (int) ((LlmContent.Image) c).data()[3])
			.containsExactly(1, 3);
		assertThat(sent).filteredOn((c) -> c instanceof LlmContent.Text t && t.text().startsWith("── p2 "))
			.singleElement()
			.satisfies((c) -> assertThat(((LlmContent.Text) c).text()).contains("Slide 2 text", "이미지 없이 추출 텍스트만"));
		assertThat(result.pack().slides()).hasSize(3);
		assertThat(result.review()).containsOnlyKeys(2);
		assertThat(result.review().get(2)).contains("이미지로 바꾸지 못해");
	}

	// ── 가짜 ──

	private static GenerateResult run(FakeLlm llm, SourceDocument document) {
		return new SummaryPipeline(llm).generate(input(document, new MemoryCheckpoints(), Cancellation.NEVER));
	}

	private static GenerateInput input(SourceDocument document, CheckpointStore checkpoints,
			Cancellation cancellation) {
		return new GenerateInput("doc-1", "Test", document, checkpoints, cancellation, null, Limits.DEFAULT);
	}

	private static SourceDocument document(int pageCount) {
		return new FakeDocument("sha-1", pages(pageCount));
	}

	private static List<SourceDocument.Page> pages(int pageCount) {
		return IntStream.rangeClosed(1, pageCount)
			.mapToObj((n) -> new SourceDocument.Page(n, "Slide " + n + " text", false, 3, false))
			.toList();
	}

	// 쪽 요청에는 요청한 쪽의 slides를, story 요청에는 story를 돌려준다.
	private static FakeLlm honest(int total) {
		return new FakeLlm((req) -> isStory(req) ? ok(story(total))
				: ok(Map.of("slides", requestedPages(req).stream().map(SummaryPipelineTests::slide).toList())));
	}

	private static Map<String, Object> slide(int page) {
		return slide(page, Map.of());
	}

	private static Map<String, Object> slide(int page, Map<String, Object> overrides) {
		Map<String, Object> slide = new LinkedHashMap<>();
		slide.put("page", page);
		slide.put("tag", page == 1 ? "meta" : "core");
		slide.put("title", "개념 " + page);
		slide.put("explain", "- **Concept " + page + "**: 페이지 " + page + "의 정의예요.\n- $x_" + page + " + 1$을 계산해요.");
		slide.put("digest", "p" + page + " 요약");
		slide.putAll(overrides);
		return slide;
	}

	private static Map<String, Object> story(int total) {
		return Map.of("question", "이 자료는 무엇을 설명하나요?", "story", List.of(Map.of("from", 2, "to", total, "title",
				"핵심 흐름", "problem", "기존 방식이 불편했어요.", "answer", "새 개념이 그 문제를 풀어요.")));
	}

	private static String json(Object value) {
		return ModelJson.MAPPER.writeValueAsString(value);
	}

	private static LlmResponse ok(Object value) {
		return text(json(value));
	}

	private static LlmResponse text(String text) {
		return new LlmResponse(text, StopReason.END, new LlmResponse.Usage(10, 5));
	}

	private static String firstText(List<LlmContent> content) {
		return content.getFirst() instanceof LlmContent.Text t ? t.text() : "";
	}

	private static boolean isStory(LlmRequest request) {
		return firstText(request.messages().getFirst().content()).contains("question과 story");
	}

	private static List<Integer> requestedPages(LlmRequest request) {
		Matcher m = REQUESTED.matcher(firstText(request.messages().getFirst().content()));
		if (!m.find()) {
			return List.of();
		}
		return List.of(m.group(1).split(",")).stream().map((s) -> Integer.valueOf(s.strip())).toList();
	}

	private record FakeDocument(String sha256, List<Page> pages) implements SourceDocument {

		@Override
		public String title() {
			return "Test";
		}

		@Override
		public byte[] modelImage(int page) {
			return new byte[] { 'j', 'p', 'g', (byte) page };
		}

	}

	private static final class MemoryCheckpoints implements CheckpointStore {

		private final Map<String, String> entries = new ConcurrentHashMap<>();

		@Override
		public Optional<String> read(String name) {
			return Optional.ofNullable(this.entries.get(name));
		}

		@Override
		public void write(String name, String json) {
			this.entries.put(name, json);
		}

	}

	private static final class FakeLlm implements LlmClient {

		private final List<LlmRequest> log = Collections.synchronizedList(new ArrayList<>());

		private final Function<LlmRequest, LlmResponse> script;

		private final boolean acceptsImages;

		FakeLlm(Function<LlmRequest, LlmResponse> script) {
			this(script, true);
		}

		FakeLlm(Function<LlmRequest, LlmResponse> script, boolean acceptsImages) {
			this.script = script;
			this.acceptsImages = acceptsImages;
		}

		@Override
		public String model() {
			return "fake";
		}

		@Override
		public boolean acceptsImages() {
			return this.acceptsImages;
		}

		@Override
		public LlmResponse complete(LlmRequest request) {
			this.log.add(request);
			return this.script.apply(request);
		}

	}

}
