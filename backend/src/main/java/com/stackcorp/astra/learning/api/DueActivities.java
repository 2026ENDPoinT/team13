package com.stackcorp.astra.learning.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 할 일 계산에 쓰는 마감 전 활동 조회(EP-0144, EP-0194). 학생에게 열려 있고 마감 일시가 있는 활동 중 마감 전인 것만 돌려준다.
 * 조건은 공개됨, {@code open_at <= now}(없으면 열린 것), {@code now < close_at}이다(SC-06). 마감 일시가 없는 활동은 할 일이 아니다.
 * 과목 권한은 판정하지 않는다. 부르는 쪽이 수강을 확인한 과목만 넘긴다. 과목 수와 관계없이 쿼리 한 번이다.
 */
public interface DueActivities {

	/**
	 * 마감 임박순({@code close_at}, {@code activity_id}) 목록.
	 * @param now DB 시계 값({@code SELECT CURRENT_TIMESTAMP(3)})
	 * @param after 이 위치 다음부터. 처음이면 null
	 */
	List<DueActivity> due(Collection<Long> courseIds, LocalDateTime now, Position after, int limit);

	/** {@link #due}와 같은 조건의 전체 건수. */
	int count(Collection<Long> courseIds, LocalDateTime now);

	/**
	 * @param type learning_activities.activity_type
	 */
	record DueActivity(long activityId, long courseId, String type, String title, LocalDateTime openAt,
			LocalDateTime closeAt) {

		public Position position() {
			return new Position(this.closeAt, this.activityId);
		}

	}

	/** 정렬 순서의 한 위치. keyset paging에 쓴다. */
	record Position(LocalDateTime closeAt, long activityId) {
	}

}
