package com.stackcorp.astra.communication;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.catalog.api.SchoolCourseNotices;
import com.stackcorp.astra.catalog.api.UnreadCourseNotices;
import com.stackcorp.astra.communication.api.CourseNoticeDigest;

import org.springframework.stereotype.Service;

/**
 * 메인 화면의 공지 요약과, catalog가 내 과목 목록에 쓰는 안 읽은 공지 수({@link UnreadCourseNotices}). 두 응답의 수는 같은 집계다. 안 읽은
 * 수에는 학교 강의실 공지 가운데 학생 본인의 학교 공지 목록에 안 읽음으로 나오고 STack에서 열지 않은 것을 더한다({@link SchoolCourseNotices},
 * ADR-0043). 학교에 요청하지 않는다.
 */
@Service
class NoticeDigestQueries implements CourseNoticeDigest, UnreadCourseNotices {

	private final NoticeRepository notices;

	private final SchoolCourseNotices schoolNotices;

	NoticeDigestQueries(NoticeRepository notices, SchoolCourseNotices schoolNotices) {
		this.notices = notices;
		this.schoolNotices = schoolNotices;
	}

	@Override
	public Map<Long, Integer> unreadCounts(long userId, Collection<Long> courseIds, LocalDateTime now) {
		if (courseIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, Integer> counts = new HashMap<>(this.notices.unreadCounts(userId, courseIds, now));
		this.schoolNotices.unreadCounts(userId, courseIds).forEach((course, n) -> counts.merge(course, n, Integer::sum));
		return counts;
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
