package com.stackcorp.astra.catalog.api;

import java.util.List;

/**
 * 과목의 수강생 명단. 담당자용 학습 현황처럼 학생 전체를 보여 줄 때 쓴다. 호출하는 쪽이 먼저 담당자 권한을 판정한다.
 */
public interface CourseRosters {

	/**
	 * @return {@link CourseMemberships#roleOf}가 STUDENT로 판정하는 사용자의 ID. 오름차순
	 */
	List<Long> students(long courseId);

}
