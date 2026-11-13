package com.stackcorp.astra.catalog.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Map;

/**
 * 내 과목 목록의 안 읽은 공지 수(강의실 왼쪽 메뉴의 공지 배지). 공지는 communication이 가지고 catalog는 communication에 의존하지
 * 않으므로({@link RosterLearning}과 같은 방향) catalog가 이 port를 정의하고 communication이 메인 화면과 같은 집계로 구현한다.
 * <p>
 * 과목 권한은 판정하지 않는다. 부르는 쪽이 구성원임을 확인한 과목만 넘긴다. 과목 수와 관계없이 쿼리 한 번이다.
 */
public interface UnreadCourseNotices {

	/**
	 * @param now DB 시계 값. 게시 일시가 이때까지인 공지만 센다
	 * @return 안 읽은 공지가 있는 과목만. 없는 과목은 넣지 않는다
	 */
	Map<Long, Integer> of(long userId, Collection<Long> courseIds, LocalDateTime now);

}
