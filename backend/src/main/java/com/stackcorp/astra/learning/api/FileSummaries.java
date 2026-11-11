package com.stackcorp.astra.learning.api;

import java.util.Collection;
import java.util.Set;

/**
 * 파일별 요약 게시 여부를 묻는 port. 자료 목록과 강의실 파일모음이 파일마다 요약 조회를 다시 부르지 않도록 한 번에 묻는다. 요약은 summary가
 * 가지고 learning과 summary는 서로 의존하지 않으므로(architecture.md 4.1) integration 모듈이 summary의 공개 API로 구현한다.
 * 권한은 판정하지 않는다. 부르는 쪽이 그 사용자가 읽을 수 있는 파일의 ID만 넘긴다. 파일 수와 관계없이 쿼리 한 번이다.
 */
public interface FileSummaries {

	/** @return 넘긴 파일 가운데 학생에게 보이는 요약 게시본이 있는 파일 */
	Set<Long> publishedAmong(Collection<Long> fileIds);

}
