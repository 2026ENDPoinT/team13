package com.stackcorp.astra.summary.job;

import java.util.List;

import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.DocumentRenditions.Rendition;
import com.stackcorp.astra.summary.pipeline.SourceDocument;
import com.stackcorp.astra.summary.pipeline.TextRedactor;

/**
 * files의 rendition을 요약 입력으로 쓴다. 쪽 텍스트와 제목은 가림 hook을 거친 값이다(ADR-0032).
 */
final class RenditionSource implements SourceDocument {

	private final DocumentRenditions renditions;

	private final Rendition rendition;

	private final List<Page> pages;

	private final String title;

	RenditionSource(DocumentRenditions renditions, Rendition rendition, TextRedactor redactor) {
		this.renditions = renditions;
		this.rendition = rendition;
		this.title = redactor.redact(rendition.title());
		this.pages = rendition.pages()
			.stream()
			.map((p) -> new Page(p.page(), redactor.redact(p.text()), p.textTruncated(), p.modelImageBytes()))
			.toList();
	}

	@Override
	public String sha256() {
		return this.rendition.sourceSha256();
	}

	@Override
	public String title() {
		return this.title;
	}

	@Override
	public List<Page> pages() {
		return this.pages;
	}

	@Override
	public byte[] modelImage(int page) {
		return this.renditions.modelImage(this.rendition.key(), page);
	}

}
