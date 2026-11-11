package com.stackcorp.astra.learning.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 캘린더에 쓰는 활동 마감 조회(EP-0177, EP-0178). 마감 일시가 기간 안인 활동을 돌려준다. 담당 과목은 공개 여부와 관계없이 모두,
 * 수강 과목은 학생에게 열린 활동만이다(공개됨, {@code open_at <= now}, SC-06). 과목 권한은 판정하지 않는다. 부르는 쪽이 역할을 확인한
 * 과목만 넘긴다. 과목 수와 관계없이 쿼리 한 번이다.
 */
public interface CalendarActivities {

	/**
	 * 마감 순({@code close_at}, {@code activity_id}) 목록.
	 * @param taughtCourseIds 담당하는 과목
	 * @param enrolledCourseIds 학생으로 수강하는 과목
	 * @param now DB 시계 값
	 * @param from 이 시각부터(포함)
	 * @param to 이 시각 전까지(제외)
	 */
	List<CalendarActivity> closingBetween(Collection<Long> taughtCourseIds, Collection<Long> enrolledCourseIds,
			LocalDateTime now, LocalDateTime from, LocalDateTime to, int limit);

	/**
	 * @param type learning_activities.activity_type
	 * @param published 공개 여부. 학생에게 돌려주는 활동은 언제나 true다
	 */
	record CalendarActivity(long activityId, long courseId, String type, String title, LocalDateTime openAt,
			LocalDateTime closeAt, boolean published) {
	}

}
