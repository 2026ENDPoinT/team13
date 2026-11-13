package com.stackcorp.astra.integration.summary;

import java.util.Collection;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.stackcorp.astra.learning.api.FileSummaries;
import com.stackcorp.astra.summary.api.PublishedSummaries;

/**
 * learning의 요약 게시 여부 port를 summary의 게시 pointer로 구현한다. learning과 summary는 서로 의존하지 않는다(architecture.md 4.1).
 * 파일 권한은 learning이 이미 판정한 파일만 넘어온다.
 */
@Component
class MaterialFileSummaries implements FileSummaries {

	private final PublishedSummaries summaries;

	MaterialFileSummaries(PublishedSummaries summaries) {
		this.summaries = summaries;
	}

	@Override
	public Set<Long> publishedAmong(Collection<Long> fileIds) {
		return this.summaries.publishedAmong(fileIds);
	}

}
