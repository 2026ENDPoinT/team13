package com.stackcorp.astra.communication;

import java.time.LocalDateTime;

import com.stackcorp.astra.catalog.api.NotificationKind;
import com.stackcorp.astra.catalog.api.Notifications;
import com.stackcorp.astra.catalog.api.Notifications.SiteNotification;
import com.stackcorp.astra.identity.api.UserAudiences.UserAudience;

import org.springframework.stereotype.Component;

/**
 * 알림마당 공지의 알림(ADR-0027, ADR-0035, ADR-0036). 원본은 알림마당 공지를 "[전체공지사항] 새로운 공지사항이 있습니다."로 알린다(화면 04).
 * 공지를 쓰고 고치고 지우는 transaction 안에서 {@link NoticeNotifications}(강의실 공지)와 같은 모양으로 부른다.
 * <p>
 * 받는 사람은 공지 대상(posts.audience)이 가리키는 사람이다. COMMON은 모두, STUDENT는 학생, PROFESSOR는 학생이 아닌 교직원(교수, 조교,
 * 직원)이다. 직원은 학생 대상 공지를 읽을 수 있지만 그 공지의 대상은 아니라 알림을 받지 않는다. V1 이전 행의 ALL은 COMMON과 같다.
 */
@Component
class SiteNoticeNotifications {

	private final Notifications notifications;

	SiteNoticeNotifications(Notifications notifications) {
		this.notifications = notifications;
	}

	/**
	 * 공지의 지금 상태에 알림을 맞춘다. 게시 일시가 미래면 그 시각에 worker가 보낸다. 게시 일시가 없으면 보낸 알림과 예약을 거둔다.
	 * @param audience COMMON, PROFESSOR, STUDENT
	 */
	void sync(long noticeId, String title, String audience, LocalDateTime publishedAt) {
		if (publishedAt == null) {
			withdraw(noticeId);
			return;
		}
		this.notifications
			.publishSite(new SiteNotification(NotificationKind.SITE_NOTICE, recipients(audience), noticeId, title, publishedAt));
	}

	/** 공지를 지웠다. 보낸 알림과 예약을 거둔다. */
	void withdraw(long noticeId) {
		this.notifications.withdraw(NotificationKind.SITE_NOTICE, noticeId);
	}

	private static UserAudience recipients(String audience) {
		return switch (audience) {
			case "STUDENT" -> UserAudience.STUDENT;
			case "PROFESSOR" -> UserAudience.STAFF;
			default -> UserAudience.ALL;
		};
	}

}
