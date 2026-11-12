package com.stackcorp.astra.summary.web;

import java.time.Instant;
import java.util.List;
import java.util.SortedMap;

import com.stackcorp.astra.summary.job.SummaryJob;
import com.stackcorp.astra.summary.pack.Beat;
import com.stackcorp.astra.summary.pack.Slide;

/**
 * API 응답 모양. 모델 사용량과 lease 같은 운영 값은 내보내지 않는다.
 */
final class SummaryViews {

	private SummaryViews() {
	}

	record JobView(long id, long fileId, String state, String stage, int pagesDone, Integer pagesTotal,
			boolean cancelRequested, String errorCode, String errorMessage, Long retryOfJobId, Instant createdAt,
			Instant startedAt, Instant finishedAt) {

		static JobView of(SummaryJob job) {
			return new JobView(job.id(), job.fileId(), job.state().name().toLowerCase(), job.stage(), job.pagesDone(),
					job.pagesTotal(), job.cancelRequested(), job.errorCode(), job.errorMessage(), job.retryOfJobId(),
					job.createdAt(), job.startedAt(), job.finishedAt());
		}

	}

	/**
	 * 교수자가 게시 전에 보는 후보. review는 원본과 비교해야 하는 쪽과 그 이유다.
	 */
	record CandidateView(long jobId, String question, List<Beat> story, List<Slide> slides,
			SortedMap<Integer, String> review, List<String> warnings) {
	}

	/**
	 * 학생에게 보이는 게시본.
	 *
	 * @param pageImageBase 화면용 쪽 이미지 경로. 쪽 N의 이미지는 pageImageBase + "NNN.jpg"다. rendition이 없으면 null
	 */
	record PublishedView(long fileId, Instant publishedAt, String question, List<Beat> story, List<Slide> slides,
			String pageImageBase) {
	}

}
