package com.stackcorp.astra.summary.api;

import java.util.Collection;
import java.util.Set;

/**
 * 파일별 요약 게시 여부. 게시 pointer(summary_publications)만 읽고 결과 객체는 읽지 않는다. 게시본이 있으면
 * {@code GET /api/courses/{courseId}/files/{fileId}/summary}가 그 파일을 읽을 수 있는 사람에게 게시본을 돌려준다.
 * 권한은 판정하지 않는다. 부르는 쪽이 읽을 수 있는 파일의 ID만 넘긴다. 파일 수와 관계없이 쿼리 한 번이다.
 */
public interface PublishedSummaries {

	/** @return 넘긴 파일 가운데 게시본이 있는 파일 */
	Set<Long> publishedAmong(Collection<Long> fileIds);

}
