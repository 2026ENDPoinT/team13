package com.stackcorp.astra.communication.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 메인 화면에 쓰는 강의실 공지 요약(EP-0191, 원본 {@code course_unread_list}). 게시 일시가 지난 공지만 다룬다. 담당자에게도 예약·임시 공지는
 * 넣지 않는다. 과목 권한은 판정하지 않는다. 부르는 쪽이 구성원임을 확인한 과목만 넘긴다. 과목 수와 관계없이 메서드마다 쿼리 한 번이다.
 */
public interface CourseNoticeDigest {

	/**
	 * 과목별 안 읽은 공지 수. 읽음은 명시적 읽음 기록(post_read_receipts)으로만 정한다(SC-04, CHG-08). 자기가 쓴 공지는 세지 않는다.
	 * 안 읽은 공지가 없는 과목은 결과에 넣지 않는다.
	 * @param now DB 시계 값({@code SELECT CURRENT_TIMESTAMP(3)})
	 */
	Map<Long, Integer> unreadCounts(long userId, Collection<Long> courseIds, LocalDateTime now);

	/** 과목들의 최근 공지. 고정 공지 먼저, 그다음 게시 일시와 ID의 역순이다. */
	List<NoticeHeadline> latest(long userId, Collection<Long> courseIds, LocalDateTime now, int limit);

	/**
	 * @param read 요청한 사용자가 읽음으로 표시했는지
	 */
	record NoticeHeadline(long noticeId, long courseId, String title, boolean pinned, LocalDateTime publishedAt,
			boolean read) {
	}

}
