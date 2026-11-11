package com.stackcorp.astra.summary.job;

import java.util.List;

import com.stackcorp.astra.files.api.DocumentRenditions;
import com.stackcorp.astra.files.api.DocumentRenditions.Rendition;
import com.stackcorp.astra.summary.pipeline.SourceDocument;

/**
 * files의 rendition을 요약 입력으로 쓴다.
 */
final class RenditionSource implements SourceDocument {

	private final DocumentRenditions renditions;

	private final Rendition rendition;

	private final List<Page> pages;

	RenditionSource(DocumentRenditions renditions, Rendition rendition) {
		this.renditions = renditions;
		this.rendition = rendition;
		this.pages = rendition.pages()
			.stream()
			.map((p) -> new Page(p.page(), p.text(), p.textTruncated(), p.modelImageBytes()))
			.toList();
	}

	@Override
	public String sha256() {
		return this.rendition.sourceSha256();
	}

	@Override
	public String title() {
		return this.rendition.title();
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
