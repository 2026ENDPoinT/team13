package com.stackcorp.astra.catalog.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.stackcorp.astra.identity.api.Actor;

/**
 * 학교 e-Class 강의실 공지(ADR-0043). 학교 공지는 {@code learning}이 학생의 학교 session으로 읽어 두고, 강의실 공지 목록과 안 읽은 공지
 * 수는 {@code communication}이 낸다. {@code communication}은 {@code learning}에 의존하지 않으므로({@link UnreadCourseNotices}와
 * 같은 방식) {@code catalog}가 이 port를 정의하고 {@code learning}이 구현한다.
 * <p>
 * 학교 공지는 그 과목의 학생(학교 과목 키가 있는 수강)에게만 있다. 담당자와 학교 과목이 아닌 과목은 비어 있다. 공지를 열지 않는다(학교에서
 * 읽음으로 바뀐다). 상세는 {@code GET /api/courses/{courseId}/notices/school-{id}}다.
 */
public interface SchoolCourseNotices {

	/**
	 * 학생에게 보이는 학교 공지. 학교 목록 순서(최근 게시순)다. 그 학생의 학교 공지 목록을 다시 읽을 때가 됐고 학교 session이 있으면 먼저
	 * 읽는다(목록 화면이라 학교 쪽 읽음은 바뀌지 않는다). 읽지 못하면 둔 목록을 쓴다.
	 */
	List<SchoolNoticeItem> list(Actor actor, long courseId);

	/**
	 * 과목별 안 읽은 학교 공지 수. 그 학생의 학교 공지 목록에 안 읽음으로 나온 공지 가운데 STack에서 열지 않은 것이다. 학교에 요청하지
	 * 않는다. 없는 과목은 넣지 않는다.
	 */
	Map<Long, Integer> unreadCounts(long userId, Collection<Long> courseIds);

	/**
	 * @param schoolNoticeId STack의 학교 공지 ID. 화면 ID는 {@code "school-<id>"}다
	 * @param postedAt 게시 일시(학교 화면). 모르면 null
	 * @param viewCount 학교 목록의 조회수. 모르면 null
	 * @param read 이 학생이 학교에서 읽었는지. 이 학생의 학교 목록을 읽은 적이 없으면 true
	 * @param expired 학교의 공지 만료 표시
	 * @param schoolUrl 학교 e-Class에서 여는 주소
	 */
	record SchoolNoticeItem(long schoolNoticeId, String title, String excerpt, LocalDateTime postedAt,
			Integer viewCount, boolean read, boolean expired, String schoolUrl) {
	}

}
