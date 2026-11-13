package com.stackcorp.astra.communication.api;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 메인 화면에 쓰는 알림마당 공지 요약(EP-0191 aside 공지, EP-0190 진입 공지). 게시 일시가 지난 공지만 다룬다. 대상은 기관 역할로 정하고
 * 과목과 관계없다. 쿼리 두 번이다.
 */
public interface CommunityNoticeDigest {

	/**
	 * @param institutionRole users.user_role 이름(STUDENT, PROFESSOR, TA, STAFF). 모르는 값이면 빈 요약
	 * @param now DB 시계 값({@code SELECT CURRENT_TIMESTAMP(3)})
	 * @param latestLimit 최근 공지 수. 고정 공지 먼저, 그다음 게시 일시 역순
	 * @param forMeLimit 진입 공지 수. 아직 읽지 않은 고정 공지만
	 */
	Headlines headlines(long userId, String institutionRole, LocalDateTime now, int latestLimit, int forMeLimit);

	/**
	 * @param read 요청한 사용자가 읽음으로 표시했는지
	 */
	record Headline(long noticeId, String title, boolean pinned, LocalDateTime publishedAt, boolean read) {
	}

	/**
	 * @param latest 최근 공지(aside)
	 * @param forMe 읽지 않은 고정 공지(진입 공지). 읽음 명령을 보내면 다음 조회부터 빠진다
	 */
	record Headlines(List<Headline> latest, List<Headline> forMe) {
	}

}
