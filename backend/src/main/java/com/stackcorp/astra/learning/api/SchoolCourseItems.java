package com.stackcorp.astra.learning.api;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 학교 e-Class에서 가져온 과목 활동과 공지(ADR-0042, ADR-0043) 가운데 로그인 때·목록 화면에서 읽어 둔 행만 돌려준다. 학교에 요청하지
 * 않는다. 화면 AI 도우미(home, ADR-0044)가 이 학생의 목록을 만들 때 쓴다. 과목 권한은 판정하지 않는다. 부르는 쪽이 그 사용자가 학교 과목
 * 키가 있는 과목의 학생임을 확인한 과목만 넘긴다(학교 행은 그 학생에게만 보인다). 메서드마다 쿼리 한 번이다.
 */
public interface SchoolCourseItems {

	/**
	 * 한 과목의 학교 활동 가운데 기간 안의 것. 학교 주차 기간이 [from, to)와 겹치거나 시작·마감 일시가 그 안이다. 학교 화면 순서(주차,
	 * 화면 순)로 앞 limit개다.
	 */
	List<SchoolActivity> activities(long userId, long courseId, LocalDateTime from, LocalDateTime to, int limit);

	/** 과목들의 학교 공지. 게시 일시 역순(없으면 뒤)으로 앞 limit개다. */
	List<SchoolNotice> notices(Collection<Long> courseIds, int limit);

	/**
	 * @param schoolActivityId 학교 활동 ID. 화면 ID는 {@code "school-<id>"}다
	 * @param schoolType 학교 메뉴 ID(lecture_weeks, lecture_material, report, test, survey, zoom 등)
	 * @param done 그 학생의 가져온 완료 표시
	 * @param closed 마감이 지났거나 학교 화면에 만료 표시가 있다
	 * @param schoolUrl 학교 e-Class에서 여는 주소
	 */
	record SchoolActivity(long schoolActivityId, long courseId, String schoolType, String title, LocalDateTime openAt,
			LocalDateTime closeAt, boolean done, boolean closed, String schoolUrl) {
	}

	/**
	 * @param schoolNoticeId STack의 학교 공지 ID. 화면 ID는 {@code "school-<id>"}다
	 */
	record SchoolNotice(long schoolNoticeId, long courseId, String title, LocalDateTime postedAt, String schoolUrl) {
	}

}
