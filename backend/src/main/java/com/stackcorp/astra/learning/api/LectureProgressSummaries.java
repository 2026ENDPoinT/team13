package com.stackcorp.astra.learning.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * 한 학생의 과목별 영상 진도율. 메인 화면 과목 카드처럼 여러 과목의 진도를 한꺼번에 보여 줄 곳에 쓴다. 과목 수와 관계없이 쿼리 한 번이다.
 * 기준은 학생의 진도 화면({@code GET /api/courses/{courseId}/lecture-progress})과 같다: 학생에게 열린(공개됐고 시작한) 활동의 지우지
 * 않은 영상 콘텐츠 가운데 출석인정을 채운 비율이다. 학교 강의실에서 가져온 영상 활동(ADR-0042)도 하나씩 세고, 학교 화면에 그 학생의
 * 완료 표시가 있으면 채운 것으로 본다. 과목 권한은 판정하지 않는다. 부르는 쪽이 그 학생이 수강하는 과목만 넘긴다.
 */
public interface LectureProgressSummaries {

	/**
	 * @param now DB 시계 값({@code SELECT CURRENT_TIMESTAMP(3)})
	 * @return 과목 ID → 진도율(0~100, 버림). 열린 영상 콘텐츠가 없는 과목은 결과에 없다
	 */
	Map<Long, Integer> percentOf(long userId, Collection<Long> courseIds, LocalDateTime now);

}
