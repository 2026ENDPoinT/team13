package com.stackcorp.astra.catalog.api;

import java.util.Collection;
import java.util.Map;

/**
 * 담당자가 처리할 수강·청강 신청 수. 담당자 메인처럼 여러 과목의 대기 신청을 한꺼번에 보여 줄 곳에 쓴다. 과목 수와 관계없이 쿼리 한 번이다.
 * 처리 권한은 {@code GET /api/courses/{courseId}/enrollment-requests}와 같다(그 과목의 교수와 비정규 담당자. 조교는 아니다).
 */
public interface EnrollmentRequests {

	/**
	 * @return 넘긴 과목 가운데 그 사용자가 신청을 처리할 수 있는 과목마다 처리를 기다리는 신청(비정규 PENDING, 청강 REQUESTED) 수. 처리할
	 * 수 없는 과목은 결과에 없다
	 */
	Map<Long, Integer> pendingCounts(long userId, Collection<Long> courseIds);

}
