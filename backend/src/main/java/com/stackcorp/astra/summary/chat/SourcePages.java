package com.stackcorp.astra.summary.chat;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.stackcorp.astra.files.api.DocumentRenditions.Rendition;
import com.stackcorp.astra.summary.pack.Pack;
import com.stackcorp.astra.summary.pack.Slide;
import com.stackcorp.astra.summary.pipeline.TextRedactor;

/**
 * 학습 대화가 읽을 수 있는 전부: 근거 파일 하나의 쪽 텍스트와 게시한 쪽 해설. 서버가 그 파일의 rendition과 게시본으로 만든다.
 * 요청이나 모델이 다른 파일, 과목, 쪽 목록을 고를 수 없다(SEC-16). 글은 모두 가림 hook을 거친 값이다.
 */
final class SourcePages {

	private static final Pattern TERM_SPLIT = Pattern.compile("[^\\p{L}\\p{N}_+#.-]+");

	private static final Pattern HANGUL = Pattern.compile("\\p{IsHangul}");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final String title;

	private final List<Page> pages;

	private SourcePages(String title, List<Page> pages) {
		this.title = title;
		this.pages = List.copyOf(pages);
	}

	static SourcePages of(String title, Rendition rendition, Pack pack, TextRedactor redactor) {
		Map<Integer, Slide> slides = pack.slides()
			.stream()
			.collect(Collectors.toMap(Slide::page, Function.identity(), (a, b) -> a));
		List<Page> pages = new ArrayList<>();
		for (var p : rendition.pages()) {
			Slide slide = slides.get(p.page());
			pages.add(new Page(p.page(), slide != null ? redactor.redact(slide.title()) : "", redactor.redact(p.text() == null ? "" : p.text()),
					slide != null ? redactor.redact(slide.explain()) : null));
		}
		return new SourcePages(redactor.redact(title), pages);
	}

	String title() {
		return this.title;
	}

	int pageCount() {
		return this.pages.size();
	}

	Optional<Page> page(int number) {
		return number >= 1 && number <= this.pages.size() ? Optional.of(this.pages.get(number - 1)) : Optional.empty();
	}

	/**
	 * 낱말마다 쪽에 나온 횟수로 점수를 매긴다. 한국어 조사가 붙어도 걸리게 부분 문자열로 센다. LessonViewer
	 * server/module/chat.ts의 searchPages와 같다.
	 */
	List<Hit> search(String query, int limit) {
		Set<String> terms = new LinkedHashSet<>();
		for (String t : TERM_SPLIT.split(query.toLowerCase())) {
			if (t.length() >= 2 || (!t.isEmpty() && HANGUL.matcher(t).find())) {
				terms.add(t);
			}
			if (terms.size() == 12) {
				break;
			}
		}
		if (terms.isEmpty()) {
			return List.of();
		}
		List<Hit> hits = new ArrayList<>();
		for (Page p : this.pages) {
			String full = p.title() + "\n" + p.text() + "\n" + (p.explain() != null ? p.explain() : "");
			String hay = full.toLowerCase();
			double score = 0;
			int first = -1;
			for (String t : terms) {
				int i = hay.indexOf(t);
				int n = 0;
				if (i >= 0 && (first < 0 || i < first)) {
					first = i;
				}
				while (i >= 0 && n < 20) {
					n++;
					i = hay.indexOf(t, i + t.length());
				}
				if (n > 0) {
					score += 1 + Math.log(n) / Math.log(2) + (p.title().toLowerCase().contains(t) ? 2 : 0);
				}
			}
			if (score <= 0) {
				continue;
			}
			int from = Math.max(0, first - 80);
			String snippet = WHITESPACE.matcher(full.substring(from, Math.min(full.length(), from + 240)))
				.replaceAll(" ")
				.strip();
			hits.add(new Hit(p.page(), p.title(), snippet, score));
		}
		return hits.stream()
			.sorted(Comparator.comparingDouble(Hit::score).reversed().thenComparingInt(Hit::page))
			.limit(limit)
			.toList();
	}

	static String normalize(String text) {
		return WHITESPACE.matcher(Normalizer.normalize(text, Normalizer.Form.NFKC)).replaceAll(" ").strip().toLowerCase();
	}

	/**
	 * @param title 게시한 해설의 제목. 없으면 빈 문자열
	 * @param explain 게시한 해설. 없으면 null
	 */
	record Page(int page, String title, String text, String explain) {
	}

	record Hit(int page, String title, String snippet, double score) {
	}

}
