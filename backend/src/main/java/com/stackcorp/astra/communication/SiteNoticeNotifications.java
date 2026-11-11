package com.stackcorp.astra.communication;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

/**
 * 알림마당 공지의 알림 자리(ADR-0027, ADR-0035). 원본은 알림마당 공지를 "[전체공지사항] 새로운 공지사항이 있습니다."로 알린다(화면 04).
 * 공지를 쓰고 고치고 지우는 transaction 안에서 {@link NoticeNotifications}(강의실 공지)와 같은 모양으로 부른다.
 * <p>
 * 지금은 아무것도 보내지 않는다. catalog.api.Notifications에는 과목 단위 발송과 정한 사람 목록 발송만 있고, 기관 역할 단위로 모두에게 보내는
 * 발송과 그 알림 유형이 없다. 그 API는 다른 branch에서 catalog가 더하고, 병합할 때 이 두 메서드를 그 API에 잇는다(architecture.md 12절).
 * 대상 값은 posts.audience의 저장 값이다: COMMON(모두), PROFESSOR(users.user_role PROFESSOR·TA·STAFF), STUDENT(STUDENT·STAFF).
 * V1 이전 행의 ALL은 COMMON과 같다.
 */
@Component
class SiteNoticeNotifications {

	/**
	 * 공지의 지금 상태에 알림을 맞춘다. 게시 일시가 미래면 그 시각에 보내야 한다.
	 * @param audience COMMON, PROFESSOR, STUDENT
	 */
	void sync(long noticeId, String title, String audience, LocalDateTime publishedAt) {
		// 병합 때 catalog의 기관 단위 발송에 잇는다. 그 전까지 알림마당 공지는 메인 화면의 noticesForMe로만 알린다.
	}

	/** 공지를 지웠다. 보낸 알림과 예약을 거둬야 한다. */
	void withdraw(long noticeId) {
		// 병합 때 catalog의 Notifications.withdraw에 잇는다.
	}

}
