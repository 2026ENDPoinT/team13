package com.stackcorp.astra.communication;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.catalog.api.UnreadCourseNotices;
import com.stackcorp.astra.communication.api.CourseNoticeDigest;

import org.springframework.stereotype.Service;

/** 메인 화면의 공지 요약과, catalog가 내 과목 목록에 쓰는 안 읽은 공지 수({@link UnreadCourseNotices}). 두 응답의 수는 같은 집계다. */
@Service
class NoticeDigestQueries implements CourseNoticeDigest, UnreadCourseNotices {

	private final NoticeRepository notices;

	NoticeDigestQueries(NoticeRepository notices) {
		this.notices = notices;
	}

	@Override
	public Map<Long, Integer> unreadCounts(long userId, Collection<Long> courseIds, LocalDateTime now) {
		return courseIds.isEmpty() ? Map.of() : this.notices.unreadCounts(userId, courseIds, now);
	}

	@Override
	public Map<Long, Integer> of(long userId, Collection<Long> courseIds, LocalDateTime now) {
		return unreadCounts(userId, courseIds, now);
	}

	@Override
	public List<NoticeHeadline> latest(long userId, Collection<Long> courseIds, LocalDateTime now, int limit) {
		return courseIds.isEmpty() || limit < 1 ? List.of() : this.notices.latest(userId, courseIds, now, limit);
	}

}
