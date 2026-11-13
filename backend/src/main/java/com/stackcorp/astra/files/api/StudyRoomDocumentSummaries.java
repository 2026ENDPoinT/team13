package com.stackcorp.astra.files.api;

import java.util.Collection;
import java.util.Map;

/**
 * 학습실 문서 요약의 상태를 files.studyroom에 알려 주는 쪽(ADR-0040 14번). 요약 작업은 summary 모듈이 갖고, files는 summary에 의존하지
 * 않으므로 summary가 이 interface를 구현한다. 구현이 없는 배포에서는 문서 보기의 {@code summaryStatus}가 null이다.
 */
public interface StudyRoomDocumentSummaries {

	/** 화면에 보이는 요약 상태. */
	String QUEUED = "queued";

	String PROCESSING = "processing";

	String READY = "ready";

	String FAILED = "failed";

	/**
	 * @return 파일 ID마다 가장 최근 요약 작업의 상태({@link #QUEUED}, {@link #PROCESSING}, {@link #READY}, {@link #FAILED}). 요약을 요청한
	 * 적이 없는 파일은 빠진다
	 */
	Map<Long, String> statuses(Collection<Long> fileIds);

	/**
	 * 문서를 지우는 transaction에서 부른다. 대기 중인 작업은 취소하고 실행 중인 작업에는 취소를 요청한다. 결과와 쪽 이미지는 파일을 지울 때
	 * 저장소 GC가 파일의 파생물, rendition과 함께 지운다.
	 */
	void discard(long fileId);

}
