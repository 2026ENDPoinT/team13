package com.stackcorp.astra.summary.job;

import java.util.Collection;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.stackcorp.astra.summary.api.PublishedSummaries;

/**
 * {@link PublishedSummaries} 구현. 게시 여부는 {@link SummaryJobService#published}와 같은 게시 pointer 하나로 정한다.
 */
@Service
class PublishedSummaryQueries implements PublishedSummaries {

	private final SummaryJobRepository jobs;

	PublishedSummaryQueries(SummaryJobRepository jobs) {
		this.jobs = jobs;
	}

	@Override
	public Set<Long> publishedAmong(Collection<Long> fileIds) {
		return fileIds.isEmpty() ? Set.of() : Set.copyOf(this.jobs.publishedAmong(fileIds));
	}

}
