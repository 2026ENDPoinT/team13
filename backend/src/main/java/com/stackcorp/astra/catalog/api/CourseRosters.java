package com.stackcorp.astra.catalog.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 과목의 수강생 명단. 담당자용 학습 현황처럼 학생 전체를 보여 줄 때 쓴다. 호출하는 쪽이 먼저 담당자 권한을 판정한다.
 */
public interface CourseRosters {

	/**
	 * @return {@link CourseMemberships#roleOf}가 STUDENT로 판정하는 사용자의 ID. 오름차순
	 */
	List<Long> students(long courseId);

	/**
	 * 여러 과목의 명단을 과목 수와 관계없이 쿼리 한 번으로 읽는다. 사람은 {@link #students(long)}와 같다.
	 *
	 * @return 과목 ID → 수강생 ID. 수강생이 없는 과목은 결과에 없다
	 */
	Map<Long, Set<Long>> students(Collection<Long> courseIds);

}
