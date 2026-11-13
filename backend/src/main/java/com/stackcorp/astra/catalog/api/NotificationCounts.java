package com.stackcorp.astra.catalog.api;

import java.time.LocalDateTime;

/**
 * 안 읽은 알림 수(EP-0138). 메인 화면 집계(home)처럼 같은 DB 시각으로 여러 값을 읽는 곳에 쓴다. 쿼리 한 번이다.
 */
public interface NotificationCounts {

	/**
	 * 알림 목록의 안 읽음 필터와 같은 조건이다. 보존 기간이 지났거나 지금 구성원이 아닌 과목의 알림은 세지 않는다.
	 * @param now DB 시계 값
	 */
	int unread(long userId, LocalDateTime now);

}
