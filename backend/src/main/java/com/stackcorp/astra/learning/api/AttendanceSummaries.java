package com.stackcorp.astra.learning.api;

import java.util.Collection;
import java.util.Map;

/**
 * 한 학생의 과목별 출석 요약. 메인 화면, 알림, 성적처럼 여러 과목의 출석을 한꺼번에 보여 줄 곳에 쓴다. 과목 수와 관계없이 쿼리 한 번이다.
 * 과목 권한은 판정하지 않는다. 부르는 쪽이 그 학생이 수강하는 과목만 넘긴다. 휴강한 차시는 세지 않는다.
 */
public interface AttendanceSummaries {

	/** @return 넘긴 과목마다 하나. 차시가 없는 과목은 모두 0이다 */
	Map<Long, AttendanceSummary> of(long userId, Collection<Long> courseIds);

	/**
	 * @param sessions 휴강하지 않은 차시 수
	 * @param unmarked 아직 판정하지 않은 차시 수
	 * @param absenceEquivalent 과목 정책의 지각 환산(지각 N번 = 결석 1번)을 적용한 결석 수. 환산하지 않는 과목은 absent와 같다
	 */
	record AttendanceSummary(long courseId, int sessions, int present, int late, int absent, int excused, int unmarked,
			int absenceEquivalent) {
	}

}
